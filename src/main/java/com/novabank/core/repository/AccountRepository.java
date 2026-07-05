package com.novabank.core.repository;

import com.novabank.core.model.Account;
import com.novabank.core.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findByUser(User user);
    Optional<Account> findByAccountNumber(String accountNumber);
    boolean existsByAccountNumber(String accountNumber);

    /**
     * Overridden (rather than relying on the inherited {@code JpaRepository.findAll(Pageable)})
     * so {@code @EntityGraph} can force an eager fetch of {@code Account.user}.
     *
     * Why this exists: {@code AdminController.accounts()} maps each {@code Account} to an
     * {@code AdminAccountResponse} by calling {@code a.getUser().getUsername()} per row.
     * {@code Account.user} is {@code FetchType.LAZY}, so without this eager-fetch hint, listing
     * a page of N accounts triggered 1 (page query) + N (one lazy-load per row) queries — a
     * textbook N+1 pattern that gets proportionally worse as the page size grows. The three
     * derived query methods below carry the same annotation for the same reason, since
     * {@code AdminController.accounts()} dispatches to whichever one matches the requested
     * filter combination.
     */
    @Override
    @EntityGraph(attributePaths = "user")
    Page<Account> findAll(Pageable pageable);

    @EntityGraph(attributePaths = "user")
    Page<Account> findByActive(boolean active, Pageable pageable);

    @EntityGraph(attributePaths = "user")
    Page<Account> findByUser_UsernameContainingIgnoreCase(String username, Pageable pageable);

    @EntityGraph(attributePaths = "user")
    Page<Account> findByActiveAndUser_UsernameContainingIgnoreCase(boolean active, String username, Pageable pageable);
}
