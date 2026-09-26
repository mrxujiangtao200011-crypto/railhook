package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.IncomingSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface IncomingSourceRepository extends JpaRepository<IncomingSource, UUID> {
    Optional<IncomingSource> findByIdAndProjectId(UUID id, UUID projectId);

    Optional<IncomingSource> findByIngressPathToken(String ingressPathToken);

    Page<IncomingSource> findByProjectId(UUID projectId, Pageable pageable);

    boolean existsByProjectIdAndSlug(UUID projectId, String slug);

    boolean existsByIngressPathToken(String ingressPathToken);

    long countByProjectId(UUID projectId);
    boolean existsByProjectId(UUID projectId);
}
