package com.novabank.core.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "fraud_logs")
@Getter
@Setter
@NoArgsConstructor
public class FraudLog extends BaseEntity {
    @Column(nullable = false, length = 100)
    private String eventType;

    @Column(length = 64)
    private String username;

    @Column(length = 64)
    private String accountNumber;

    @Column(nullable = false, length = 255)
    private String details;

    @Column(nullable = false)
    private boolean flagged = true;
}
