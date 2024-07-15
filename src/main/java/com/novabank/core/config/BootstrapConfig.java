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
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@RequiredArgsConstructor
public class BootstrapConfig {

    private static final Logger log = LoggerFactory.getLogger(BootstrapConfig.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap.admin.username:admin}")
    private String adminUsername;
    @Value("${app.bootstrap.admin.email:admin@nova.local}")
    private String adminEmail;
    @Value("${app.bootstrap.admin.password:admin12345}")
    private String adminPassword;

    @Bean
    CommandLineRunner seedAdminUser() {
        return args -> {
            if (!userRepository.existsByUsername(adminUsername)) {
                User u = new User();
                u.setUsername(adminUsername);
                u.setEmail(adminEmail);
                u.setPasswordHash(passwordEncoder.encode(adminPassword));
                u.setRole(Role.ADMIN);
                userRepository.save(u);
                log.info("Seeded default ADMIN user: {}", adminUsername);
            } else {
                log.info("ADMIN user '{}' exists, skipping seeding.", adminUsername);
            }
        };
    }
}
