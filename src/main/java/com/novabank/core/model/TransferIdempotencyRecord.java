package com.novabank.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "transfer_idempotency_records",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_transfer_idempotency_actor_key",
                        columnNames = {"actorUsername", "idempotencyKey"}
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
public class TransferIdempotencyRecord extends BaseEntity {

    @Column(nullable = false, length = 64)
    private String actorUsername;

    @Column(nullable = false, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false, length = 36)
    private String transferReference;
}
