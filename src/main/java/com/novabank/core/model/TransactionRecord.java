package com.novabank.core.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_tx_from", columnList = "from_account_id"),
        @Index(name = "idx_tx_to", columnList = "to_account_id")
})
@Getter
@Setter
@NoArgsConstructor
public class TransactionRecord extends BaseEntity {

    public enum Type { DEPOSIT, WITHDRAWAL, TRANSFER }

    @Column(nullable = false, unique = true, length = 36)
    private String reference = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_account_id")
    private Account fromAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_account_id")
    private Account toAccount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private Instant occurredAt = Instant.now();

    @Column(length = 255)
    private String note;
}
