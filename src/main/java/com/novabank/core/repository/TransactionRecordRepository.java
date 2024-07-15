package com.novabank.core.repository;

import com.novabank.core.model.TransactionRecord;
import com.novabank.core.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface TransactionRecordRepository extends JpaRepository<TransactionRecord, Long> {
    List<TransactionRecord> findByFromAccount_UserOrToAccount_User(User fromUser, User toUser);

    Page<TransactionRecord> findByFromAccount_UserOrToAccount_User(User fromUser, User toUser, Pageable pageable);
}
