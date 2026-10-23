package com.js.motos.clientApp.config;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.js.motos.clientApp.audit.AuditService;
import com.js.motos.clientApp.user.domain.User;
import com.js.motos.clientApp.user.domain.UserRole;
import com.js.motos.clientApp.user.repo.UserRepository;

/**
 * Promotes {@code app.admin.bootstrap-email} to ADMIN on startup, creating the
 * account if it does not exist yet.
 *
 * <p>This is how the first admin comes into being — there is no password to seed
 * because admins sign in through the same email-OTP flow as everyone else. Set
 * the variable once, start the app, then sign in with that address.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AppProperties properties;

    public AdminBootstrap(UserRepository userRepository,
                          AuditService auditService,
                          AppProperties properties) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String email = properties.admin().bootstrapEmail();

        if (email == null || email.isBlank()) {
            if (!userRepository.existsByRole(UserRole.ADMIN)) {
                log.warn("No admin exists and ADMIN_BOOTSTRAP_EMAIL is unset — "
                        + "the admin APIs are unreachable. Set it and restart.");
            }
            return;
        }

        String normalized = email.trim().toLowerCase();
        User admin = userRepository.findByEmailIgnoreCase(normalized).orElseGet(() -> {
            User created = new User();
            created.setEmail(normalized);
            created.setEmailVerified(true);
            log.info("Bootstrapping new admin account {}", normalized);
            return created;
        });

        if (admin.getRole() != UserRole.ADMIN) {
            admin.setRole(UserRole.ADMIN);
            userRepository.save(admin);
            auditService.record(null, "ADMIN_BOOTSTRAPPED", "User", admin.getId(),
                    Map.of("email", normalized));
            log.info("Granted ADMIN to {}", normalized);
        } else {
            userRepository.save(admin);
        }
    }
}