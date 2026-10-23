# Motos Rewards — Backend

Spring Boot backend for the QR-cashback Android app.

An admin mints batches of QR codes, each worth a fixed amount. The codes are
printed and distributed physically. A user scans one in the app and — provided
their UPI ID has been verified — a cashback claim is raised. An admin pays the
money manually over UPI, records the transaction reference, uploads a receipt
screenshot, and marks the claim paid.

## Stack

| Concern | Choice |
| --- | --- |
| Runtime | Java 17, Spring Boot 3.5.6 |
| Database | PostgreSQL (Neon free tier), Flyway migrations |
| Auth | Email OTP + Google Sign-In, JWT access tokens, 4-digit PIN app-lock |
| Receipts | Cloudinary (local disk in dev) |
| QR rendering | ZXing |
| Docs | OpenAPI / Swagger UI at `/swagger-ui.html` |

## Running locally

```bash
cp .env.example .env          # then fill it in
docker compose up -d          # local Postgres on :5432
./mvnw spring-boot:run
```

Swagger UI: <http://localhost:8080/swagger-ui.html>

In the `dev` profile `OTP_DEV_MODE` defaults to `true`, so `POST /auth/otp/request`
returns the code in the `devCode` field instead of emailing it. This is forced
off in the `prod` profile.

### Build requirements

The build targets Java 17 but compiles fine on newer JDKs. Lombok is pinned to
1.18.42 because earlier versions break on JDK 24+.

## The auth model

This is the part worth reading before touching the Android client.

### Roles of each token

| Token | Lifetime | Stored where | Purpose |
| --- | --- | --- | --- |
| Access token (JWT) | 15 min | memory | Sent as `Authorization: Bearer …` |
| Refresh token (opaque) | 30 days | Android `EncryptedSharedPreferences` | Represents the logged-in device |

Every access token carries a **scope** that says how far it is trusted:

- `PIN_SETUP` — identity proven, but no PIN exists yet. Can *only* call
  `POST /auth/pin/setup`.
- `PIN_REQUIRED` — valid session whose PIN grace window has lapsed. Can only log
  out. This is what forces the app-lock screen.
- `FULL` — PIN verified recently (or a fresh interactive login). Everything else
  requires this.

### Flows

**First login**

```
POST /api/v1/auth/otp/request   { email }
POST /api/v1/auth/otp/verify    { email, code, deviceId, deviceName }
   → { accessToken, refreshToken, scope: "PIN_SETUP", pinSet: false }
POST /api/v1/auth/pin/setup     { pin: "4271" }
   → { accessToken, scope: "FULL" }
```

Google Sign-In replaces the first two calls with `POST /api/v1/auth/google
{ idToken }`. If the verified Google email already has an account, the two are
linked rather than duplicated.

**Returning to the app (cold start)**

The client still holds the refresh token, so there is no second OTP:

```
POST /api/v1/auth/session/unlock  { refreshToken, pin }
   → { accessToken, scope: "FULL" }
```

**While the app is in use**

```
POST /api/v1/auth/token/refresh   { refreshToken }
   → scope FULL          while the PIN grace window (30 min) is open
   → scope PIN_REQUIRED  once it lapses → show the PIN screen
```

**Logout**

```
POST /api/v1/auth/logout  { refreshToken, allDevices: false }
```

This revokes the session row. After it, the PIN can mint nothing — a full
OTP/Google login is required. That is what "logged out completely" means here.

### Why the PIN is not decorative

A 4-digit PIN is 10,000 possibilities, so throttling is what secures it, not
length:

- BCrypt-hashed, never stored or logged in the clear.
- 5 wrong attempts → 15-minute lockout.
- Obvious PINs (`1234`, `0000`, `1111`, …) are rejected at setup.
- Forgetting it requires an emailed code, and resetting it revokes every session.
- Crucially, a **stolen refresh token alone is not enough**: once the grace
  window lapses, refresh only returns `PIN_REQUIRED` tokens.

Refresh tokens are deliberately *not* rotated. Without the PIN a stolen one is
near-useless, and rotation risks stranding a client that loses the response to a
refresh call.

## Domain flows

### Minting codes (admin)

```
POST /api/v1/admin/qr/batches
  { "label": "March campaign", "amount": 100.00, "quantity": 500,
    "expiresAt": "2027-03-31T00:00:00Z" }

GET  /api/v1/admin/qr/batches/{id}/images.zip   → one PNG per code, for the printer
GET  /api/v1/admin/qr/batches/{id}/export.csv   → code list for mail-merge
```

Each code is 16 characters over a 30-symbol alphabet (~78 bits) with no visually
ambiguous characters (no `O`/`0`, `I`/`1`). Guessing a live code is not feasible.
The QR image encodes `{APP_BASE_URL}/r/{CODE}`.

> `APP_BASE_URL` is baked into every printed code. Changing it after a print run
> breaks those codes. The API accepts a bare code as well as the full URL, which
> softens this, but set it correctly before printing.

### Claiming (user)

```
GET  /api/v1/qr/preview?scan=<decoded QR content>   → claimable? amount? why not?
POST /api/v1/redemptions   { "scan": "<decoded QR content>" }
```

A claim requires a **verified** payout account. `preview` is non-destructive so
the app can show a confirmation screen before burning the code.

Double-spend is prevented at two levels: the code row is locked `FOR UPDATE`
during a claim, and `redemptions.qr_code_id` carries a UNIQUE constraint.

### Paying out (admin)

```
GET  /api/v1/admin/redemptions?status=PENDING
POST /api/v1/admin/redemptions/{id}/approve
POST /api/v1/admin/redemptions/{id}/complete    (multipart: receipt, utrReference)
POST /api/v1/admin/redemptions/{id}/reject      { reason, releaseCode }
```

`complete` uploads the receipt *before* flipping the status, so a storage failure
leaves the claim unpaid rather than marked paid with no proof.

`reject` takes `releaseCode`: `true` returns the sticker to circulation so the
user can retry after fixing the problem, `false` (the default) burns it — use
that for suspected fraud.

Payout details are **snapshotted** onto the claim when it is made. If a user
later edits their UPI ID, historical claims still show where the money actually
went.

### Payout account verification

Users add a UPI ID or bank account; both land in `PENDING` and appear in the
admin queue at `GET /api/v1/admin/payout-accounts`. Nothing can be claimed until
an admin verifies one. A verified account cannot be edited — the user adds a new
one, which goes back through review, so an approved payout target can never be
silently swapped.

Bank account numbers are AES-256-GCM encrypted at rest and never returned in
full; clients see `****4321`.

## Notifications

Admins get a shared feed (`GET /api/v1/admin/notifications`) covering new claims
and payout accounts awaiting verification. Users get their own feed for approved,
paid and rejected claims, plus a transactional email on the important ones.

The feed is DB-backed and polled. If you later want true push, add FCM alongside
`NotificationService` — every write already goes through one place.

## Becoming an admin

There is no admin password. Set `ADMIN_BOOTSTRAP_EMAIL` and restart: that address
is created (if needed) and promoted to `ADMIN`. Sign in with the normal email-OTP
flow. Admin endpoints require both `ROLE_ADMIN` **and** a `FULL`-scope token.

## Deploying

Set every variable in `.env.example`, and `APP_PROFILE=prod`. Non-negotiable:

- `JWT_SECRET` — ≥32 bytes, `openssl rand -base64 48`
- `DATA_ENCRYPTION_KEY` — exactly 32 bytes, `openssl rand -base64 32`.
  **Rotating this makes stored bank account numbers undecryptable.**
- `CLOUDINARY_ENABLED=true` — free-tier hosts have ephemeral disks, so local
  receipt storage would vanish on each deploy.
- `GOOGLE_CLIENT_IDS` — include the **Web** client ID, not just the Android one;
  Google Sign-In on Android issues tokens audienced to the Web client ID.

The app starts on `$PORT` and reads `X-Forwarded-For`, so it works as-is behind
Render/Railway/Fly proxies.

### Known limits

- **Rate limiting is per-instance** (in-memory Caffeine). The durable OTP quotas
  are DB-backed and remain correct, but if you scale past one instance, move the
  rest to Redis or the database.
- **Notifications are polled**, not pushed.
- **Payouts are manual by design** — no payment gateway is integrated. The system
  records intent and proof; a human moves the money.

## Layout

```
src/main/java/com/js/motos/clientApp/
├─ auth/          OTP, Google, PIN, sessions
├─ user/          profile, UPI/bank payout accounts
├─ qr/            batches, codes, PNG/ZIP rendering
├─ redemption/    claims and the payout workflow
├─ notification/  in-app feeds
├─ admin/         admin-only controllers
├─ security/      JWT, scopes, rate limiting
├─ storage/       Cloudinary / local receipt storage
├─ mail/          transactional email
├─ audit/         append-only trail of money/privilege actions
└─ common/        errors, paging, shared utilities
```

Migrations live in `src/main/resources/db/migration`. `ddl-auto` is `validate` —
schema changes go in a new Flyway migration, never by letting Hibernate alter
tables.