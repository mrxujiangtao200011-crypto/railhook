package com.webhook.platform.worker.attempt;

import com.webhook.platform.worker.domain.entity.Delivery;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import com.webhook.platform.worker.service.OrderingBufferService;
import io.micrometer.core.instrument.Counter;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Per-endpoint FIFO for Outgoing. Incoming enforces no ordering. */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class OrderingGate {

    private final OrderingBufferService orderingBufferService;
    private final DeliveryRepository deliveryRepository;
    private final TransactionTemplate transactionTemplate;
    private final Counter gapTimeoutCounter;
    private final long rescheduleDelaySeconds;

    /** Null if the Delivery may proceed now. Parking ends the Claim and clears its token. */
    Instant holdUntil(Delivery delivery, UUID fence) {
        UUID endpointId = delivery.getEndpointId();
        long sequenceNumber = delivery.getSequenceNumber();

        if (orderingBufferService.canDeliver(endpointId, sequenceNumber)) {
            return null;
        }

        // Check the whole missing range: checking only sequenceNumber - 1 let a Delivery through
        // whenever its immediate predecessor was already terminal.
        Long lastDelivered = orderingBufferService.getLastDeliveredSequence(endpointId);
        long rangeStart = (lastDelivered == null ? 0 : lastDelivered) + 1;
        long rangeEnd = sequenceNumber - 1;

        Instant oldestPendingInRange = rangeStart <= rangeEnd
                ? deliveryRepository.findOldestPendingCreatedAt(endpointId, rangeStart, rangeEnd)
                : null;

        if (oldestPendingInRange == null) {
            log.debug("No outstanding deliveries in gap [{}, {}] for endpoint {}, proceeding with seq={}",
                    rangeStart, rangeEnd, endpointId, sequenceNumber);
            return null;
        }

        // Timed from when this Delivery was first buffered; the blocking row's timestamp made the
        // timeout always true for an old backlog. The timeout is for a gap that never closes, so
        // a gap that is still closing keeps holding.
        if (orderingBufferService.isGapTimedOut(delivery.getOrderingFirstBufferedAt())
                && !gapIsClosing(endpointId, rangeStart, rangeEnd)) {
            log.warn("Gap timeout for endpoint {}, proceeding with seq={} despite outstanding range [{}, {}]",
                    endpointId, sequenceNumber, rangeStart, rangeEnd);
            gapTimeoutCounter.increment();
            return null;
        }

        return park(delivery, fence, endpointId, sequenceNumber, rangeStart, rangeEnd);
    }

    /**
     * True if anything in the gap is being attempted now or is due within one more gap timeout.
     * The default first rung and the default gap timeout are both a minute, so without this check
     * FIFO broke on the first ordinary retry. A due Delivery that nothing dispatches keeps the gap
     * open until the stranded-PENDING escalation abandons it.
     */
    private boolean gapIsClosing(UUID endpointId, long rangeStart, long rangeEnd) {
        Instant now = Instant.now();
        Duration gapTimeout = orderingBufferService.gapTimeout();
        return deliveryRepository.countGapClosingBefore(endpointId, rangeStart, rangeEnd,
                now.minus(gapTimeout), now.plus(gapTimeout)) > 0;
    }

    private Instant park(Delivery delivery, UUID fence, UUID endpointId, long sequenceNumber,
            long rangeStart, long rangeEnd) {
        log.debug("Buffering delivery {} (seq={}) waiting for range [{}, {}]",
                delivery.getId(), sequenceNumber, rangeStart, rangeEnd);
        orderingBufferService.bufferDelivery(endpointId, delivery.getId(), sequenceNumber);

        Instant now = Instant.now();
        Instant until = now.plusSeconds(rescheduleDelaySeconds);
        Integer parked = transactionTemplate.execute(tx ->
                deliveryRepository.parkIfStillClaimed(delivery.getId(), fence, until, now));
        if (parked == null || parked == 0) {
            log.debug("Delivery {} (seq={}) was reclaimed before it could be buffered",
                    delivery.getId(), sequenceNumber);
        }
        return until;
    }

    /** Called for every terminal outcome, or the cursor parks the endpoint forever. */
    void release(Delivery delivery, boolean removeFromBuffer) {
        if (!Boolean.TRUE.equals(delivery.getOrderingEnabled()) || delivery.getSequenceNumber() == null) {
            return;
        }
        try {
            if (removeFromBuffer) {
                orderingBufferService.removeFromBuffer(delivery.getEndpointId(), delivery.getId());
            }
            orderingBufferService.markDelivered(delivery.getEndpointId(), delivery.getSequenceNumber());
            triggerBufferedDeliveries(delivery.getEndpointId());
        } catch (Exception e) {
            log.error("Failed to release ordering buffer for delivery {}: {}", delivery.getId(), e.getMessage(), e);
        }
    }

    /** Only unclaimed rows are touched, so this cannot overtake an Attempt in flight. */
    private void triggerBufferedDeliveries(UUID endpointId) {
        List<UUID> ready = orderingBufferService.getReadyDeliveries(endpointId);
        for (UUID id : ready) {
            transactionTemplate.execute(tx -> deliveryRepository.scheduleIfUnclaimed(id, Instant.now()));
        }
    }
}
