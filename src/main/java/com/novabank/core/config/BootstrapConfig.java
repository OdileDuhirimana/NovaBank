package com.novabank.core.config;

import com.novabank.core.model.Role;
import com.novabank.core.model.User;
import com.novabank.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * Seeds a default ADMIN user on first startup so a freshly-provisioned environment has at least
 * one administrator able to log in.
 *
 * SECURITY-CRITICAL DESIGN NOTE (see also JwtService.validateSecret for the same fail-fast
 * pattern applied to the JWT signing secret): this class previously fell back to a hardcoded
 * default password ({@code admin12345}) whenever {@code app.bootstrap.admin.password} was not
 * set, and that fallback applied unconditionally — including in a real deployment, since
 * {@code render.yaml} never set an override. Any environment that forgot to set the property
 * (which was every environment, since nothing enforced it) would silently seed a well-known,
 * publicly-documented admin credential. That is a critical vulnerability for a banking-domain
 * system and is fixed here as follows:
 *
 * <ul>
 *   <li>The {@code app.bootstrap.admin.password} property no longer has a default value.</li>
 *   <li>If the active Spring profile is {@code dev} or {@code local}, a missing password is
 *       tolerated: a cryptographically random password is generated per startup and logged at
 *       WARN level so a developer can still log in locally. This mirrors the well-established
 *       convention Spring Boot itself uses for its own default user/password, and is safe
 *       specifically because those profiles are never used for a shared or production
 *       deployment.</li>
 *   <li>For every other case — an explicit non-dev/non-local profile (e.g. {@code prod},
 *       {@code staging}), <em>or no active profile at all</em> (fail closed on the ambiguous
 *       case rather than assuming safety) — a missing password throws {@link IllegalStateException}
 *       during application startup, refusing to seed (and refusing to start the application at
 *       all, since this bean is initialized eagerly during context refresh). This is the same
 *       fail-fast contract {@code JwtService} already applies to a weak/missing JWT secret.</li>
 * </ul>
 *
 * See {@code render.yaml} (sets {@code SPRING_PROFILES_ACTIVE=prod} and provisions
 * {@code APP_BOOTSTRAP_ADMIN_PASSWORD} via Render's {@code generateValue: true}) and
 * {@code docker-compose.yml} (sets {@code SPRING_PROFILES_ACTIVE=dev} and intentionally leaves
 * the admin password unset to demonstrate the safe random-generation path).
 */
@Configuration
@RequiredArgsConstructor
public class BootstrapConfig {

    private static final Logger log = LoggerFactory.getLogger(BootstrapConfig.class);

    /**
     * Profiles under which a missing explicit admin password is tolerated (random password
     * generated and logged instead of failing startup). Deliberately does NOT include a "test"
     * profile: the test suite sets {@code app.bootstrap.admin.password} explicitly in
     * {@code src/test/resources/application.yml}, exercising the same "must be explicit" contract
     * production does, rather than relying on this safety-valve.
     */
    private static final Set<String> SAFE_DEFAULT_PROFILES = Set.of("dev", "local");

    private static final int GENERATED_PASSWORD_BYTES = 18;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    @Value("${app.bootstrap.admin.username:admin}")
    private String adminUsername;
    @Value("${app.bootstrap.admin.email:admin@nova.local}")
    private String adminEmail;

    /**
     * No default value on purpose — see class Javadoc. An unset property resolves to {@code ""},
     * which {@link #resolveAdminPassword()} treats as "not explicitly configured".
     */
    @Value("${app.bootstrap.admin.password:}")
    private String adminPassword;

    @Bean
    public CommandLineRunner seedAdminUser() {
        return args -> {
            if (userRepository.existsByUsername(adminUsername)) {
                log.info("ADMIN user '{}' exists, skipping seeding.", adminUsername);
                return;
            }
            String resolvedPassword = resolveAdminPassword();
            User u = new User();
            u.setUsername(adminUsername);
            u.setEmail(adminEmail);
            u.setPasswordHash(passwordEncoder.encode(resolvedPassword));
            u.setRole(Role.ADMIN);
            userRepository.save(u);
            log.info("Seeded default ADMIN user: {}", adminUsername);
        };
    }

    /**
     * Resolves the password to seed the default admin account with, enforcing that a
     * non-dev/non-local environment can never fall back to a guessable default. Public
     * (rather than private) so it is directly unit-testable without booting a Spring context —
     * see {@code BootstrapConfigTest}.
     *
     * @throws IllegalStateException if no explicit password is configured and the active profile
     *                                is not dev/local.
     */
    public String resolveAdminPassword() {
        if (adminPassword != null && !adminPassword.isBlank()) {
            return adminPassword;
        }

        if (isSafeDefaultProfileActive()) {
            String generated = generateRandomPassword();
            log.warn(
                    "app.bootstrap.admin.password was not set. Because the active Spring profile "
                            + "({}) is dev/local, a random ADMIN password was generated for this run "
                            + "instead of failing startup: user='{}' password='{}'. This value changes "
                            + "on every restart and is ONLY safe because this is a local/dev profile — "
                            + "set APP_BOOTSTRAP_ADMIN_PASSWORD explicitly for any shared or "
                            + "production environment.",
                    activeProfilesDescription(), adminUsername, generated
            );
            return generated;
        }

        throw new IllegalStateException(
                "Refusing to start: app.bootstrap.admin.password is not set and the active Spring "
                        + "profile (" + activeProfilesDescription() + ") is not 'dev' or 'local'. "
                        + "Seeding a default ADMIN account with a hardcoded or absent password is not "
                        + "permitted outside a local/dev profile. Set the APP_BOOTSTRAP_ADMIN_PASSWORD "
                        + "environment variable (or app.bootstrap.admin.password property) to an "
                        + "explicit, strong value before starting this application."
        );
    }

    private boolean isSafeDefaultProfileActive() {
        String[] activeProfiles = environment.getActiveProfiles();
        // No active profile at all is treated as NOT safe (fail closed) — an unconfigured
        // deployment is exactly the scenario this fix exists to protect against.
        for (String profile : activeProfiles) {
            if (SAFE_DEFAULT_PROFILES.contains(profile.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String activeProfilesDescription() {
        String[] activeProfiles = environment.getActiveProfiles();
        return activeProfiles.length == 0 ? "none (default)" : String.join(",", activeProfiles);
    }

    private String generateRandomPassword() {
        byte[] randomBytes = new byte[GENERATED_PASSWORD_BYTES];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
