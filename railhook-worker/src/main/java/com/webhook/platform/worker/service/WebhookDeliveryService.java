package com.webhook.platform.worker.service;

import com.webhook.platform.worker.attempt.AttemptRunner;
import com.webhook.platform.worker.attempt.DeliveryAttemptMetrics;
import com.webhook.platform.worker.attempt.OutgoingAttemptStoreFactory;
import com.webhook.platform.worker.domain.entity.Delivery;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class WebhookDeliveryService {

    private final AttemptRunner attemptRunner;
    private final OutgoingAttemptStoreFactory storeFactory;
    private final DeliveryAttemptMetrics metrics;
    private final DeliveryRepository deliveryRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Duration claimTimeout;
    private final int maxEndpoints;
    private final int perEndpoint;

    private UUID after = ClaimCursor.START;

    public WebhookDeliveryService(
            AttemptRunner attemptRunner,
            OutgoingAttemptStoreFactory storeFactory,
            DeliveryAttemptMetrics metrics,
            DeliveryRepository deliveryRepository,
            TransactionTemplate transactionTemplate,
            Clock clock,
            @Value("${webhook.claim.timeout-seconds:300}") long claimTimeoutSeconds,
            @Value("${webhook.claim.max-targets:500}") int maxEndpoints,
            @Value("${webhook.claim.per-target:5}") int perEndpoint) {
        this.attemptRunner = attemptRunner;
        this.storeFactory = storeFactory;
        this.metrics = metrics;
        this.deliveryRepository = deliveryRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.claimTimeout = Duration.ofSeconds(claimTimeoutSeconds);
        this.maxEndpoints = maxEndpoints;
        this.perEndpoint = perEndpoint;
    }

    /** Called from the poller thread only. */
    public List<Delivery> claimDue(int limit) {
        Instant now = clock.instant();
        List<Delivery> claimed = transactionTemplate.execute(tx -> deliveryRepository.claimDue(
                now, now.plus(claimTimeout), after, maxEndpoints, perEndpoint, limit));
        after = ClaimCursor.next(claimed.stream().map(Delivery::getEndpointId).toList(), limit);
        return claimed;
    }

    public void attempt(Delivery claimed) {
        try {
            attemptRunner.run(storeFactory.create(claimed), metrics);
        } catch (Exception e) {
            log.error("Unexpected error in delivery {}: {}", claimed.getId(), e.getMessage(), e);
        }
    }
}
