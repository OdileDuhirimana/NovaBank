package com.novabank.core.dto.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Standard error envelope returned by NOVA Bank APIs")
public class ErrorResponse {

    @Schema(description = "Machine-readable error code", example = "BAD_REQUEST")
    private String code;

    @Schema(description = "Human-readable message", example = "Invalid username or password")
    private String message;

    @Schema(description = "Optional map of additional details (e.g., field errors)")
    private Map<String, Object> details;

    @Schema(description = "Timestamp of the error in ISO-8601", example = "2025-01-01T12:00:00Z")
    private OffsetDateTime timestamp;
}
