package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.TunnelRequestLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TunnelRequestLogRepository extends JpaRepository<TunnelRequestLog, UUID> {

    Page<TunnelRequestLog> findByTunnelSessionIdOrderByCreatedAtDesc(UUID tunnelSessionId, Pageable pageable);
}
