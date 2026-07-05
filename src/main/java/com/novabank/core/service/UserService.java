package com.novabank.core.service;

import com.novabank.core.dto.auth.LoginRequest;
import com.novabank.core.dto.auth.RegisterRequest;
import com.novabank.core.dto.auth.AuthResponse;
import com.novabank.core.model.Role;
import com.novabank.core.model.User;
import com.novabank.core.repository.UserRepository;
import com.novabank.core.security.JwtService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final AuditService auditService;
    private final FraudService fraudService;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("Username already taken");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already registered");
        }
        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        // Security: the role is never taken from client input on public self-registration.
        // Elevated roles (ADMIN, AUDITOR) can only be granted by an existing ADMIN through a
        // separate authenticated administrative action (not yet implemented as of this pass);
        // this closes a full privilege-escalation vulnerability where any caller could
        // previously POST {"role":"ADMIN"} to self-provision an administrator account.
        user.setRole(Role.CUSTOMER);
        userRepository.save(user);
        auditService.log(user.getUsername(), "REGISTER", null, null, "User registered");
        return issueTokens(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getUsername(), request.getPassword()
                    )
            );
        } catch (BadCredentialsException ex) {
            fraudService.logFailedLogin(request.getUsername());
            auditService.log(request.getUsername(), "LOGIN_FAILED", null, null, "Bad credentials");
            throw ex;
        }
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        auditService.log(user.getUsername(), "LOGIN", null, null, "User logged in");
        return issueTokens(user);
    }

    /**
     * Exchanges a valid, not-yet-used refresh token for a new access token and a rotated refresh
     * token. See {@code RefreshTokenService#rotate} for the reuse-detection contract.
     */
    @Transactional
    public AuthResponse refresh(String refreshToken) {
        RefreshTokenService.RotationResult rotation = refreshTokenService.rotate(refreshToken);
        User user = rotation.user();
        auditService.log(user.getUsername(), "TOKEN_REFRESH", null, null, "Access token refreshed");
        String accessToken = jwtService.generateToken(user);
        return new AuthResponse(accessToken, rotation.issuedRefreshToken().rawToken());
    }

    /**
     * Revokes a refresh token (logout). The short-lived access token that may still be in the
     * client's possession is not itself revocable (see {@code RefreshToken} Javadoc for why that
     * tradeoff was made) but will expire on its own within 15 minutes.
     */
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    private AuthResponse issueTokens(User user) {
        String accessToken = jwtService.generateToken(user);
        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.issue(user);
        return new AuthResponse(accessToken, refreshToken.rawToken());
    }
}
