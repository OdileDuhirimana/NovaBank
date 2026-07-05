package com.novabank.core.controller;

import com.novabank.core.dto.auth.AuthResponse;
import com.novabank.core.dto.auth.LoginRequest;
import com.novabank.core.dto.auth.RefreshRequest;
import com.novabank.core.dto.auth.RegisterRequest;
import com.novabank.core.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Endpoints for user registration, login, and token refresh/revocation")
public class AuthController {

    private final UserService userService;

    @Operation(summary = "Register a new user and return an access + refresh token pair",
            description = "Creates a new user account with a role (default CUSTOMER) and returns a short-lived access token plus a longer-lived, revocable refresh token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User registered",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class),
                            examples = @ExampleObject(value = "{\n  \"token\": \"eyJhbGciOiJIUzI1NiIs...\",\n  \"refreshToken\": \"9f2c...\"\n}"))),
            @ApiResponse(responseCode = "400", description = "Validation error or duplicate username/email",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class)))
    })
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(userService.register(request));
    }

    @Operation(summary = "Login with username and password",
            description = "Authenticates a user and returns a short-lived access token (Authorization: Bearer <token>) plus a refresh token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class))),
            @ApiResponse(responseCode = "401", description = "Invalid credentials",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class)))
    })
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(userService.login(request));
    }

    @Operation(summary = "Exchange a refresh token for a new access + refresh token pair",
            description = "Rotates the refresh token: the presented token is revoked and a new one is issued. "
                    + "Presenting an already-used (revoked) refresh token is treated as a signal of token theft "
                    + "and revokes every active refresh token for that user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New token pair issued",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class))),
            @ApiResponse(responseCode = "401", description = "Invalid, expired, or already-used refresh token",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Refresh token reuse detected — all sessions revoked",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class)))
    })
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(userService.refresh(request.getRefreshToken()));
    }

    @Operation(summary = "Revoke a refresh token (logout)",
            description = "Idempotent: always returns 204, regardless of whether the token was valid, to avoid leaking token validity.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Refresh token revoked (or was already invalid/unknown)")
    })
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        userService.logout(request.getRefreshToken());
        return ResponseEntity.noContent().build();
    }
}
