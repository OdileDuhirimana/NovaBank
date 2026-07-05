package com.novabank.core.unit;

import com.novabank.core.config.BootstrapConfig;
import com.novabank.core.model.Role;
import com.novabank.core.model.User;
import com.novabank.core.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Isolated unit test (no Spring context) for the admin-credential fail-fast fix.
 *
 * This directly regression-tests the critical finding from the security audit: BootstrapConfig
 * previously fell back to a hardcoded default password ({@code admin12345}) in every environment,
 * including production, because {@code render.yaml} never overrode it. These tests assert the
 * fixed contract: an explicit password is always honored; a missing password is only tolerated
 * under a dev/local profile (with a freshly-generated random value, never a fixed default); and a
 * missing password under any other profile — including no active profile at all — fails startup
 * with a clear {@link IllegalStateException} rather than silently seeding a guessable credential.
 */
@ExtendWith(MockitoExtension.class)
class BootstrapConfigTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private Environment environment;

    private BootstrapConfig bootstrapConfig;

    @BeforeEach
    void setUp() {
        bootstrapConfig = new BootstrapConfig(userRepository, passwordEncoder, environment);
        ReflectionTestUtils.setField(bootstrapConfig, "adminUsername", "admin");
        ReflectionTestUtils.setField(bootstrapConfig, "adminEmail", "admin@nova.local");
    }

    @Test
    void explicitPasswordIsAlwaysHonoredRegardlessOfProfile() {
        ReflectionTestUtils.setField(bootstrapConfig, "adminPassword", "explicitStrongPassword1");

        String resolved = bootstrapConfig.resolveAdminPassword();

        assertThat(resolved).isEqualTo("explicitStrongPassword1");
        // The profile must never even be consulted once an explicit password is present.
        verify(environment, never()).getActiveProfiles();
    }

    @Test
    void missingPasswordWithNoActiveProfileFailsStartup() {
        ReflectionTestUtils.setField(bootstrapConfig, "adminPassword", "");
        when(environment.getActiveProfiles()).thenReturn(new String[]{});

        assertThatThrownBy(() -> bootstrapConfig.resolveAdminPassword())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_BOOTSTRAP_ADMIN_PASSWORD");
    }

    @Test
    void missingPasswordUnderProdProfileFailsStartup() {
        ReflectionTestUtils.setField(bootstrapConfig, "adminPassword", null);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        assertThatThrownBy(() -> bootstrapConfig.resolveAdminPassword())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingPasswordUnderStagingProfileFailsStartup() {
        ReflectionTestUtils.setField(bootstrapConfig, "adminPassword", "   ");
        when(environment.getActiveProfiles()).thenReturn(new String[]{"staging"});

        assertThatThrownBy(() -> bootstrapConfig.resolveAdminPassword())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingPasswordUnderDevProfileGeneratesARandomPasswordInsteadOfFailing() {
        ReflectionTestUtils.setField(bootstrapConfig, "adminPassword", "");
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});

        String first = bootstrapConfig.resolveAdminPassword();
        String second = bootstrapConfig.resolveAdminPassword();

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank();
        // Never a fixed/guessable default: a fresh random value every time it must be generated.
        assertThat(first).isNotEqualTo(second);
        assertThat(first).isNotEqualTo("admin12345");
    }

    @Test
    void missingPasswordUnderLocalProfileIsCaseInsensitiveAndGeneratesAPassword() {
        ReflectionTestUtils.setField(bootstrapConfig, "adminPassword", null);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"LOCAL"});

        assertThat(bootstrapConfig.resolveAdminPassword()).isNotBlank();
    }

    @Test
    void seedAdminUserSkipsEntirelyWhenAdminAlreadyExists() throws Exception {
        when(userRepository.existsByUsername("admin")).thenReturn(true);

        CommandLineRunner runner = bootstrapConfig.seedAdminUser();
        runner.run();

        verify(userRepository, never()).save(any());
        // Password resolution (and any profile check) must never even be attempted once the
        // admin user already exists — nothing to guard.
        verify(environment, never()).getActiveProfiles();
    }

    @Test
    void seedAdminUserPersistsAnAdminRoleUserWithTheEncodedExplicitPassword() throws Exception {
        ReflectionTestUtils.setField(bootstrapConfig, "adminPassword", "explicitStrongPassword1");
        when(userRepository.existsByUsername("admin")).thenReturn(false);
        when(passwordEncoder.encode("explicitStrongPassword1")).thenReturn("encoded-hash");

        CommandLineRunner runner = bootstrapConfig.seedAdminUser();
        runner.run();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getUsername()).isEqualTo("admin");
        assertThat(saved.getEmail()).isEqualTo("admin@nova.local");
        assertThat(saved.getPasswordHash()).isEqualTo("encoded-hash");
        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void seedAdminUserPropagatesTheFailFastExceptionWhenPasswordIsMissingInProd() {
        ReflectionTestUtils.setField(bootstrapConfig, "adminPassword", "");
        when(userRepository.existsByUsername("admin")).thenReturn(false);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        CommandLineRunner runner = bootstrapConfig.seedAdminUser();

        assertThatThrownBy(runner::run).isInstanceOf(IllegalStateException.class);
        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }
}
