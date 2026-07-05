package com.novabank.core.repository;

import com.novabank.core.model.FraudLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FraudLogRepository extends JpaRepository<FraudLog, Long>, JpaSpecificationExecutor<FraudLog> {
}
