package com.novabank.core.dto.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AccountStatusUpdateRequest {

    @NotNull
    private Boolean active;

    @Size(max = 200, message = "Reason must be at most 200 characters")
    private String reason;
}
