package com.webhook.platform.worker.repository;

import com.webhook.platform.worker.domain.entity.Delivery;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"
})
class DeliveryClaimConcurrencyTest {

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
    private PlatformTransactionManager transactionManager;

    @Test
    void twoPollersNeverClaimTheSameRow() throws Exception {
        UUID endpointId = UUID.randomUUID();
        Instant now = Instant.now();
        for (int i = 0; i < 8; i++) {
            deliveryRepository.saveAndFlush(Delivery.builder()
                    .id(UUID.randomUUID())
                    .organizationId(UUID.randomUUID())
                    .eventId(UUID.randomUUID())
                    .endpointId(endpointId)
                    .status(Delivery.DeliveryStatus.PENDING)
                    .attemptCount(0)
                    .maxAttempts(7)
                    .nextRetryAt(now.minusSeconds(60 + i))
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);

        CompletableFuture<List<Delivery>> first = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            List<Delivery> claimed = claim(now);
            firstClaimed.countDown();
            try {
                secondDone.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return claimed;
        }));
        firstClaimed.await(10, TimeUnit.SECONDS);
        List<Delivery> second = tx.execute(status -> claim(now));
        secondDone.countDown();

        Set<UUID> firstIds = ids(first.get(10, TimeUnit.SECONDS));
        Set<UUID> secondIds = ids(second);
        assertThat(firstIds).hasSize(5);
        assertThat(secondIds).as("the second poller skips the rows the first still holds").hasSize(3)
                .doesNotContainAnyElementsOf(firstIds);
    }

    private List<Delivery> claim(Instant now) {
        return deliveryRepository.claimDue(now, now.plusSeconds(300), new UUID(0L, 0L), 10, 5, 5);
    }

    private static Set<UUID> ids(List<Delivery> deliveries) {
        Set<UUID> ids = new HashSet<>();
        deliveries.forEach(d -> ids.add(d.getId()));
        return ids;
    }
}
