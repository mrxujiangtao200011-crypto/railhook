package com.webhook.platform.worker.service;

import com.webhook.platform.worker.attempt.AttemptRunner;
import com.webhook.platform.worker.attempt.ForwardAttemptMetrics;
import com.webhook.platform.worker.attempt.IncomingAttemptStoreFactory;
import com.webhook.platform.worker.domain.entity.IncomingDestination;
import com.webhook.platform.worker.domain.entity.IncomingEvent;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.worker.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.worker.domain.repository.IncomingEventRepository;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
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
public class IncomingForwardService {

    private final IncomingEventRepository eventRepository;
    private final IncomingDestinationRepository destinationRepository;
    private final IncomingForwardAttemptRepository attemptRepository;
    private final TransactionTemplate transactionTemplate;
    private final AttemptRunner attemptRunner;
    private final IncomingAttemptStoreFactory storeFactory;
    private final ForwardAttemptMetrics metrics;
    private final Clock clock;
    private final Duration claimTimeout;
    private final int maxDestinations;
    private final int perDestination;

    private UUID after = ClaimCursor.START;

    public IncomingForwardService(
            IncomingEventRepository eventRepository,
            IncomingDestinationRepository destinationRepository,
            IncomingForwardAttemptRepository attemptRepository,
            TransactionTemplate transactionTemplate,
            AttemptRunner attemptRunner,
            IncomingAttemptStoreFactory storeFactory,
            ForwardAttemptMetrics metrics,
            Clock clock,
            @Value("${webhook.claim.timeout-seconds:300}") long claimTimeoutSeconds,
            @Value("${webhook.claim.max-targets:500}") int maxDestinations,
            @Value("${webhook.claim.per-target:5}") int perDestination) {
        this.eventRepository = eventRepository;
        this.destinationRepository = destinationRepository;
        this.attemptRepository = attemptRepository;
        this.transactionTemplate = transactionTemplate;
        this.attemptRunner = attemptRunner;
        this.storeFactory = storeFactory;
        this.metrics = metrics;
        this.clock = clock;
        this.claimTimeout = Duration.ofSeconds(claimTimeoutSeconds);
        this.maxDestinations = maxDestinations;
        this.perDestination = perDestination;
    }

    /** Called from the poller thread only. */
    public List<IncomingForwardAttempt> claimDue(int limit) {
        Instant now = clock.instant();
        List<IncomingForwardAttempt> claimed = transactionTemplate.execute(tx -> attemptRepository.claimDue(
                now, now.plus(claimTimeout), after, maxDestinations, perDestination, limit));
        after = ClaimCursor.next(claimed.stream().map(IncomingForwardAttempt::getDestinationId).toList(), limit);
        return claimed;
    }

    public void attempt(IncomingForwardAttempt claimed) {
        try {
            IncomingEvent event = eventRepository.findById(claimed.getIncomingEventId()).orElse(null);
            if (event == null) {
                fail(claimed, "Incoming event not found");
                return;
            }
            IncomingDestination destination = destinationRepository.findById(claimed.getDestinationId()).orElse(null);
            if (destination == null) {
                fail(claimed, "Incoming destination not found");
                return;
            }
            attemptRunner.run(storeFactory.create(claimed, event, destination), metrics);
        } catch (Exception e) {
            log.error("Unexpected error forwarding attempt {}: {}", claimed.getId(), e.getMessage(), e);
        }
    }

    private void fail(IncomingForwardAttempt claimed, String reason) {
        log.error("{}: eventId={}, destId={}", reason, claimed.getIncomingEventId(), claimed.getDestinationId());
        transactionTemplate.execute(tx -> attemptRepository.failIfStillClaimed(
                claimed.getId(), claimed.getClaimToken(), reason, clock.instant()));
    }
}
