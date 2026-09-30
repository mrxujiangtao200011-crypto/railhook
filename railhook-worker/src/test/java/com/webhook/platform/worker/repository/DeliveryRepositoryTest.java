package com.webhook.platform.worker.repository;

import com.webhook.platform.common.enums.DeliveryStatus;
import com.webhook.platform.worker.attempt.OutgoingAttemptStore;
import com.webhook.platform.worker.domain.entity.Delivery;
import com.webhook.platform.worker.domain.entity.Endpoint;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"
})
class DeliveryRepositoryTest {

    // organization_id is NOT NULL and the worker copies it off the parent row.
    private static final UUID FIXTURE_ORG = UUID.randomUUID();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("webhook_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID sharedEndpointId;
    private UUID sharedProjectId;

    private void createSharedEndpoint() {
        sharedProjectId = UUID.randomUUID();
        sharedEndpointId = UUID.randomUUID();
        Endpoint endpoint = Endpoint.builder()
                .organizationId(FIXTURE_ORG)
                .id(sharedEndpointId)
                .projectId(sharedProjectId)
                .url("https://example.com/hook")
                .secretEncrypted("enc")
                .secretIv("iv")
                .enabled(true)
                .build();
        entityManager.persist(endpoint);
    }

    private static final UUID FROM_START = new UUID(0L, 0L);

    @Test
    void claimDue_takesDueRowsAndTimedOutClaimsOnly() {
        createSharedEndpoint();
        Instant now = Instant.now();
        Delivery due = createAndPersistDelivery(DeliveryStatus.PENDING, now.minusSeconds(1));
        Delivery timedOut = createAndPersistDelivery(DeliveryStatus.PROCESSING, now.minusSeconds(1));
        createAndPersistDelivery(DeliveryStatus.PENDING, now.plusSeconds(300));
        createAndPersistDelivery(DeliveryStatus.PROCESSING, now.plusSeconds(300));
        createAndPersistDelivery(DeliveryStatus.SUCCESS, null);
        entityManager.flush();
        entityManager.clear();

        List<Delivery> claimed = deliveryRepository.claimDue(now, now.plusSeconds(300), FROM_START, 10, 10, 10);

        assertEquals(Set.of(due.getId(), timedOut.getId()),
                claimed.stream().map(Delivery::getId).collect(Collectors.toSet()));
    }

    @Test
    void claimDue_stampsAFreshTokenAndTheClaimTimeout() {
        createSharedEndpoint();
        Instant now = Instant.now();
        Delivery timedOut = createAndPersistDelivery(DeliveryStatus.PROCESSING, now.minusSeconds(1));
        UUID lostHolder = UUID.randomUUID();
        timedOut.setClaimToken(lostHolder);
        entityManager.flush();
        entityManager.clear();
        Instant expiresAt = now.plusSeconds(300);

        Delivery claimed = deliveryRepository.claimDue(now, expiresAt, FROM_START, 10, 10, 10).get(0);

        assertEquals(DeliveryStatus.PROCESSING, claimed.getStatus());
        assertNotNull(claimed.getClaimToken());
        assertNotEquals(lostHolder, claimed.getClaimToken(), "the lost holder must not match the new fence");
        assertEquals(expiresAt.truncatedTo(ChronoUnit.MILLIS), claimed.getNextRetryAt().truncatedTo(ChronoUnit.MILLIS));
    }

    // One endpoint's backlog used to fill every batch while the others waited behind it.
    @Test
    void claimDue_capsEachEndpointSoOneBacklogCannotFillTheBatch() {
        createSharedEndpoint();
        UUID quietEndpoint = UUID.randomUUID();
        Instant now = Instant.now();
        for (int i = 0; i < 20; i++) {
            createAndPersistDelivery(DeliveryStatus.PENDING, now.minusSeconds(60 + i));
        }
        Delivery quiet = createAndPersistDelivery(DeliveryStatus.PENDING, now.minusSeconds(1));
        quiet.setEndpointId(quietEndpoint);
        entityManager.flush();
        entityManager.clear();

        List<Delivery> claimed = deliveryRepository.claimDue(now, now.plusSeconds(300), FROM_START, 10, 3, 10);

        assertEquals(3, claimed.stream().filter(d -> d.getEndpointId().equals(sharedEndpointId)).count());
        assertTrue(claimed.stream().anyMatch(d -> d.getId().equals(quiet.getId())));
    }

    // Claiming past an endpoint's concurrency deferred the extra rows for up to a minute.
    @Test
    void claimDue_takesOnlyTheEndpointsFreeConcurrency() {
        createSharedEndpoint();
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            createAndPersistDelivery(DeliveryStatus.PROCESSING, now.plusSeconds(300));
        }
        for (int i = 0; i < 10; i++) {
            createAndPersistDelivery(DeliveryStatus.PENDING, now.minusSeconds(1 + i));
        }
        entityManager.flush();
        entityManager.clear();

        assertEquals(2, deliveryRepository.claimDue(now, now.plusSeconds(300), FROM_START, 10, 5, 10).size());
    }

    @Test
    void claimDue_resumesAfterTheCursor() {
        createSharedEndpoint();
        Instant now = Instant.now();
        createAndPersistDelivery(DeliveryStatus.PENDING, now.minusSeconds(1));
        entityManager.flush();
        entityManager.clear();

        assertTrue(deliveryRepository.claimDue(now, now.plusSeconds(300), sharedEndpointId, 10, 10, 10).isEmpty(),
                "an endpoint at or before the cursor waits for the next pass");
    }

    @Test
    void attemptStarting_aReclaimedAttemptDoesNotSpendItsSuccessorsRung() {
        // Matched by id alone, the stale Attempt's increment spent a rung of the successor's ladder.
        createSharedEndpoint();
        Delivery delivery = createAndPersistDelivery(DeliveryStatus.PROCESSING, null);
        UUID sweptFence = UUID.randomUUID();
        delivery.setClaimToken(UUID.randomUUID());
        entityManager.flush();
        entityManager.clear();

        OutgoingAttemptStore store = new OutgoingAttemptStore(deliveryRepository, null, null, null, null,
                new TransactionTemplate(transactionManager), null, null, null, null, null, null, null, null,
                null, null, 0, null);
        store.attemptStarting(new OutgoingAttemptStore.Claim(delivery.getId(), sweptFence, delivery));
        entityManager.clear();

        assertEquals(1, deliveryRepository.findById(delivery.getId()).orElseThrow().getAttemptCount(),
                "the rung belongs to the Attempt that holds the row now");
    }

    @Test
    void findStaleDeliveryIds_measuresAgeFromWhenTheDeliveryWasLastPutBackOnItsLadder() {
        // A retried Delivery keeps its created_at, so age from it escalated it straight back to DLQ.
        createSharedEndpoint();
        Instant now = Instant.now();
        Delivery neverResumed = persistPendingDelivery(now.minus(100, ChronoUnit.HOURS), null);
        Delivery retriedJustNow = persistPendingDelivery(
                now.minus(100, ChronoUnit.HOURS), now.minusSeconds(60));
        Delivery retriedLongAgo = persistPendingDelivery(
                now.minus(200, ChronoUnit.HOURS), now.minus(100, ChronoUnit.HOURS));
        entityManager.flush();
        entityManager.clear();

        List<UUID> stale = deliveryRepository.findStaleDeliveryIds(now.minus(96, ChronoUnit.HOURS), 10);

        assertTrue(stale.contains(neverResumed.getId()), "an old Delivery nobody touched is still escalated");
        assertFalse(stale.contains(retriedJustNow.getId()), "a Delivery retried a minute ago is not stale");
        assertTrue(stale.contains(retriedLongAgo.getId()), "a retry is not a permanent exemption from the cap");
    }

    private Delivery persistPendingDelivery(Instant createdAt, Instant ladderResumedAt) {
        return entityManager.persist(Delivery.builder()
                .organizationId(FIXTURE_ORG)
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .endpointId(sharedEndpointId)
                .subscriptionId(UUID.randomUUID())
                .status(DeliveryStatus.PENDING)
                .attemptCount(7)
                .maxAttempts(10)
                .orderingEnabled(false)
                .ladderResumedAt(ladderResumedAt)
                .createdAt(createdAt)
                .updatedAt(ladderResumedAt != null ? ladderResumedAt : createdAt)
                .build());
    }

    // A Delivery between rungs will still be attempted, so its successors keep waiting.
    @Test
    void countGapClosingBefore_countsWhatIsInFlightOrDueAndNothingElse() {
        createSharedEndpoint();
        Instant now = Instant.now();

        Delivery dueSoon = orderedDelivery(3L, DeliveryStatus.PENDING, now.plusSeconds(20), now);
        Delivery inFlight = orderedDelivery(4L, DeliveryStatus.PROCESSING, null, now.minusSeconds(5));
        // A later rung: nothing will touch this one for an hour.
        orderedDelivery(5L, DeliveryStatus.PENDING, now.plusSeconds(3600), now);
        // Claimed, then abandoned by whoever held it: PROCESSING, but nobody is attempting it.
        orderedDelivery(6L, DeliveryStatus.PROCESSING, null, now.minusSeconds(600));
        // Resolved, so not outstanding at all.
        orderedDelivery(7L, DeliveryStatus.SUCCESS, null, now);
        // Due, but outside the gap being asked about.
        orderedDelivery(20L, DeliveryStatus.PENDING, now, now);

        entityManager.flush();
        entityManager.clear();

        long closing = deliveryRepository.countGapClosingBefore(sharedEndpointId, 3L, 7L,
                now.minusSeconds(60), now.plusSeconds(60));

        assertEquals(2, closing,
                "only the Delivery due inside the window (" + dueSoon.getSequenceNumber()
                        + ") and the one being attempted now (" + inFlight.getSequenceNumber() + ") count");
    }

    private Delivery orderedDelivery(long sequenceNumber, DeliveryStatus status,
            Instant nextRetryAt, Instant updatedAt) {
        Delivery delivery = Delivery.builder()
                .organizationId(FIXTURE_ORG)
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .endpointId(sharedEndpointId)
                .subscriptionId(UUID.randomUUID())
                .status(status)
                .attemptCount(1)
                .maxAttempts(7)
                .orderingEnabled(true)
                .sequenceNumber(sequenceNumber)
                .nextRetryAt(nextRetryAt)
                .createdAt(Instant.now())
                .updatedAt(updatedAt)
                .build();
        return entityManager.persist(delivery);
    }

    private Delivery createAndPersistDelivery(DeliveryStatus status, Instant nextRetryAt) {
        return createAndPersistDelivery(status, nextRetryAt, Instant.now());
    }

    private Delivery createAndPersistDelivery(DeliveryStatus status, Instant nextRetryAt, Instant updatedAt) {
        Delivery delivery = Delivery.builder()
                .organizationId(FIXTURE_ORG)
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .endpointId(sharedEndpointId)
                .subscriptionId(UUID.randomUUID())
                .status(status)
                .attemptCount(1)
                .maxAttempts(7)
                .orderingEnabled(false)
                .nextRetryAt(nextRetryAt)
                .createdAt(Instant.now())
                .updatedAt(updatedAt)
                .build();

        return entityManager.persist(delivery);
    }
}
