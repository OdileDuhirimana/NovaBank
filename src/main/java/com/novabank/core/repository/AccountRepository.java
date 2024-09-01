package com.novabank.core.repository;

import com.novabank.core.model.Account;
import com.novabank.core.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findByUser(User user);
    Optional<Account> findByAccountNumber(String accountNumber);
    boolean existsByAccountNumber(String accountNumber);
    Page<Account> findByActive(boolean active, Pageable pageable);
    Page<Account> findByUser_UsernameContainingIgnoreCase(String username, Pageable pageable);
    Page<Account> findByActiveAndUser_UsernameContainingIgnoreCase(boolean active, String username, Pageable pageable);
}
