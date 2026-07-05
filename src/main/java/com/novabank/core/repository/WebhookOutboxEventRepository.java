package com.novabank.core.repository;

import com.novabank.core.model.WebhookOutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebhookOutboxEventRepository extends JpaRepository<WebhookOutboxEvent, Long> {

    List<WebhookOutboxEvent> findByStatusOrderByCreatedAtAsc(WebhookOutboxEvent.Status status, Pageable pageable);
}
