package com.novabank.core.repository;

import com.novabank.core.model.TransferIdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransferIdempotencyRecordRepository extends JpaRepository<TransferIdempotencyRecord, Long> {
    Optional<TransferIdempotencyRecord> findByActorUsernameAndIdempotencyKey(String actorUsername, String idempotencyKey);
}
