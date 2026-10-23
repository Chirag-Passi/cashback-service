-- =====================================================================
-- Initial schema: users, auth, payout accounts, QR codes, redemptions.
-- Target: PostgreSQL 14+ (Neon).
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------- users
CREATE TABLE users (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email                VARCHAR(320) NOT NULL,
    full_name            VARCHAR(150),
    phone                VARCHAR(20),
    avatar_url           TEXT,
    role                 VARCHAR(20)  NOT NULL DEFAULT 'USER',
    status               VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    email_verified       BOOLEAN      NOT NULL DEFAULT FALSE,
    google_subject       VARCHAR(64),
    pin_hash             VARCHAR(100),
    pin_updated_at       TIMESTAMPTZ,
    pin_failed_attempts  INT          NOT NULL DEFAULT 0,
    pin_locked_until     TIMESTAMPTZ,
    last_login_at        TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT users_role_chk   CHECK (role   IN ('USER', 'ADMIN')),
    CONSTRAINT users_status_chk CHECK (status IN ('ACTIVE', 'BLOCKED', 'DELETED'))
);

-- Email is the identity key for both OTP and Google login; store it lowercased.
CREATE UNIQUE INDEX users_email_uk         ON users (lower(email));
CREATE UNIQUE INDEX users_google_subject_uk ON users (google_subject) WHERE google_subject IS NOT NULL;

-- ------------------------------------------------------- payout accounts
CREATE TABLE payout_accounts (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type                  VARCHAR(10)  NOT NULL,
    account_holder_name   VARCHAR(150) NOT NULL,
    upi_id                VARCHAR(120),
    bank_account_number   VARCHAR(512),   -- AES-encrypted at rest
    bank_account_last4    VARCHAR(4),
    ifsc                  VARCHAR(11),
    bank_name             VARCHAR(120),
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    is_primary            BOOLEAN      NOT NULL DEFAULT FALSE,
    rejection_reason      TEXT,
    verified_by           UUID REFERENCES users (id),
    verified_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT payout_type_chk   CHECK (type   IN ('UPI', 'BANK')),
    CONSTRAINT payout_status_chk CHECK (status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    -- A UPI account needs a VPA; a bank account needs number + IFSC.
    CONSTRAINT payout_shape_chk  CHECK (
        (type = 'UPI'  AND upi_id IS NOT NULL) OR
        (type = 'BANK' AND bank_account_number IS NOT NULL AND ifsc IS NOT NULL)
    )
);

CREATE INDEX payout_accounts_user_idx ON payout_accounts (user_id);
-- At most one primary payout account per user.
CREATE UNIQUE INDEX payout_accounts_primary_uk ON payout_accounts (user_id) WHERE is_primary;
-- The same VPA must not be claimed by two different people.
CREATE UNIQUE INDEX payout_accounts_upi_uk ON payout_accounts (lower(upi_id))
    WHERE upi_id IS NOT NULL AND status = 'VERIFIED';

-- ----------------------------------------------------------- otp tokens
CREATE TABLE otp_tokens (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email        VARCHAR(320) NOT NULL,
    code_hash    VARCHAR(100) NOT NULL,
    purpose      VARCHAR(30)  NOT NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    expires_at   TIMESTAMPTZ  NOT NULL,
    consumed_at  TIMESTAMPTZ,
    request_ip   VARCHAR(45),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT otp_purpose_chk CHECK (purpose IN ('LOGIN', 'EMAIL_CHANGE', 'PIN_RESET'))
);

CREATE INDEX otp_tokens_lookup_idx  ON otp_tokens (lower(email), purpose, created_at DESC);
CREATE INDEX otp_tokens_expiry_idx  ON otp_tokens (expires_at) WHERE consumed_at IS NULL;

-- ------------------------------------------------------- refresh tokens
-- One row per logged-in device. Revoking the row is what "full logout" means:
-- the PIN alone can no longer mint access tokens.
CREATE TABLE refresh_tokens (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash     VARCHAR(64)  NOT NULL UNIQUE,
    device_id      VARCHAR(128),
    device_name    VARCHAR(150),
    user_agent     VARCHAR(255),
    ip             VARCHAR(45),
    issued_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at   TIMESTAMPTZ,
    -- Drives the PIN grace window: once this goes stale, a plain refresh can
    -- only mint a PIN_REQUIRED token, never a full-access one.
    pin_verified_at TIMESTAMPTZ,
    expires_at     TIMESTAMPTZ  NOT NULL,
    revoked_at     TIMESTAMPTZ,
    revoke_reason  VARCHAR(60)
);

CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id) WHERE revoked_at IS NULL;

-- ----------------------------------------------------------- qr batches
CREATE TABLE qr_batches (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    label       VARCHAR(150) NOT NULL,
    amount      NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    quantity    INT NOT NULL CHECK (quantity > 0),
    expires_at  TIMESTAMPTZ,
    notes       TEXT,
    created_by  UUID NOT NULL REFERENCES users (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ------------------------------------------------------------- qr codes
CREATE TABLE qr_codes (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id     UUID REFERENCES qr_batches (id) ON DELETE SET NULL,
    code         VARCHAR(32)  NOT NULL UNIQUE,
    amount       NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    expires_at   TIMESTAMPTZ,
    redeemed_by  UUID REFERENCES users (id),
    redeemed_at  TIMESTAMPTZ,
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT qr_status_chk CHECK (status IN ('ACTIVE', 'REDEEMED', 'EXPIRED', 'VOID'))
);

CREATE INDEX qr_codes_batch_idx  ON qr_codes (batch_id);
CREATE INDEX qr_codes_status_idx ON qr_codes (status);

-- ---------------------------------------------------------- redemptions
CREATE SEQUENCE redemption_ref_seq START 1000;

CREATE TABLE redemptions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference_no            VARCHAR(24) NOT NULL UNIQUE,
    user_id                 UUID NOT NULL REFERENCES users (id),
    -- UNIQUE enforces "one QR redeems exactly once" at the DB level.
    qr_code_id              UUID NOT NULL UNIQUE REFERENCES qr_codes (id),
    amount                  NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payout_account_id       UUID REFERENCES payout_accounts (id),
    -- Snapshot the payout target so later edits can't rewrite history.
    payout_type_snapshot    VARCHAR(10),
    upi_id_snapshot         VARCHAR(120),
    bank_last4_snapshot     VARCHAR(4),
    ifsc_snapshot           VARCHAR(11),
    holder_name_snapshot    VARCHAR(150),
    requested_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_at             TIMESTAMPTZ,
    approved_by             UUID REFERENCES users (id),
    paid_at                 TIMESTAMPTZ,
    paid_by                 UUID REFERENCES users (id),
    utr_reference           VARCHAR(50),
    receipt_url             TEXT,
    receipt_public_id       VARCHAR(200),
    rejection_reason        TEXT,
    admin_notes             TEXT,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT redemption_status_chk CHECK (status IN ('PENDING', 'APPROVED', 'PAID', 'REJECTED'))
);

CREATE INDEX redemptions_user_idx        ON redemptions (user_id, requested_at DESC);
CREATE INDEX redemptions_status_idx      ON redemptions (status, requested_at DESC);

-- --------------------------------------------------------- notifications
CREATE TABLE notifications (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audience          VARCHAR(10) NOT NULL,
    -- NULL recipient + audience=ADMIN means "any admin sees it".
    recipient_user_id UUID REFERENCES users (id) ON DELETE CASCADE,
    type              VARCHAR(50) NOT NULL,
    title             VARCHAR(150) NOT NULL,
    body              TEXT,
    data              JSONB,
    read_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT notification_audience_chk CHECK (audience IN ('USER', 'ADMIN'))
);

CREATE INDEX notifications_user_idx  ON notifications (recipient_user_id, created_at DESC);
CREATE INDEX notifications_admin_idx ON notifications (created_at DESC) WHERE audience = 'ADMIN';

-- ------------------------------------------------------------ audit log
CREATE TABLE audit_logs (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id  UUID REFERENCES users (id),
    action         VARCHAR(60) NOT NULL,
    entity_type    VARCHAR(60),
    entity_id      VARCHAR(64),
    details        JSONB,
    ip             VARCHAR(45),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX audit_logs_entity_idx ON audit_logs (entity_type, entity_id, created_at DESC);
CREATE INDEX audit_logs_actor_idx  ON audit_logs (actor_user_id, created_at DESC);