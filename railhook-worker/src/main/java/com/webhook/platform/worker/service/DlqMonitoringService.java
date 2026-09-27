package com.webhook.platform.worker.service;

import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

@Service
@Slf4j
public class DlqMonitoringService {

    private final DeliveryRepository deliveryRepository;
    private final IncomingForwardAttemptRepository incomingForwardAttemptRepository;
    private final AtomicLong deliveryDlqDepth = new AtomicLong(0);
    private final AtomicLong forwardDlqDepth = new AtomicLong(0);

    public DlqMonitoringService(
            DeliveryRepository deliveryRepository,
            IncomingForwardAttemptRepository incomingForwardAttemptRepository,
            MeterRegistry meterRegistry) {
        this.deliveryRepository = deliveryRepository;
        this.incomingForwardAttemptRepository = incomingForwardAttemptRepository;

        Gauge.builder("webhook_dlq_depth", deliveryDlqDepth, AtomicLong::get)
                .description("Deliveries in DLQ status awaiting a manual retry or purge")
                .register(meterRegistry);
        Gauge.builder("incoming_forward_dlq_depth", forwardDlqDepth, AtomicLong::get)
                .description("Forwards in DLQ status awaiting a manual retry or purge")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${dlq.monitoring.interval-ms:60000}")
    public void monitorDlqDepth() {
        refresh("Deliveries", deliveryRepository::countDlqTotal, deliveryDlqDepth);
        refresh("Forwards", incomingForwardAttemptRepository::countDlqTotal, forwardDlqDepth);
    }

    private void refresh(String what, LongSupplier count, AtomicLong gaugeValue) {
        try {
            long depth = count.getAsLong();
            long previous = gaugeValue.getAndSet(depth);
            if (depth > 0) {
                log.warn("{} {} in DLQ status - manual intervention may be required", depth, what);
            } else if (previous > 0) {
                log.info("DLQ backlog cleared - 0 {} remain in DLQ status", what);
            }
        } catch (Exception e) {
            log.error("Failed to refresh DLQ depth for {}: {}", what, e.getMessage());
        }
    }
}
