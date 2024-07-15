package com.novabank.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
public class AuditLog extends BaseEntity {
    @Column(nullable = false, length = 64)
    private String actor; // username or system

    @Column(nullable = false, length = 100)
    private String action; // e.g., LOGIN, TRANSFER

    @Column(length = 64)
    private String accountNumber;

    @Column(length = 64)
    private String reference; // transaction ref

    @Column(nullable = false, length = 255)
    private String details;
}
