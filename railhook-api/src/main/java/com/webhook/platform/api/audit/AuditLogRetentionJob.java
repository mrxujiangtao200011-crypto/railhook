package com.webhook.platform.api.audit;

import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.domain.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogRetentionJob {

    private final AuditLogRepository auditLogRepository;
    @Value("${audit.retention-days:90}")
    private final int retentionDays;

    // A cron fires on every replica at once. The delete is idempotent; the lock only saves
    // duplicate work.
    @SystemTenant
    @Scheduled(cron = "${audit.retention-cron:0 0 3 * * *}")
    @SchedulerLock(name = "audit-log-retention", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void purgeOldAuditLogs() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = auditLogRepository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Audit log retention: deleted {} entries older than {} days", deleted, retentionDays);
        }
    }
}
