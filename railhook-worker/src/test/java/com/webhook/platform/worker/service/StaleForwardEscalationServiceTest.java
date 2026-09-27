package com.webhook.platform.worker.service;

import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Nothing used to give up on a Forward whose Destination stayed unreachable. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StaleForwardEscalationServiceTest {

    @Mock
    private IncomingForwardAttemptRepository attemptRepository;
    @Mock
    private TransactionTemplate transactionTemplate;

    private MeterRegistry meterRegistry;
    private StaleForwardEscalationService service;

    private static final long HARD_CAP_HOURS = 24;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                inv.getArgument(0, TransactionCallback.class).doInTransaction(null));
        meterRegistry = new SimpleMeterRegistry();
        service = new StaleForwardEscalationService(
                attemptRepository, transactionTemplate, meterRegistry, HARD_CAP_HOURS, 100);
    }

    private IncomingForwardAttempt pendingAttempt(UUID eventId, UUID destinationId, int attemptNumber) {
        return IncomingForwardAttempt.builder()
                .id(UUID.randomUUID())
                .incomingEventId(eventId)
                .destinationId(destinationId)
                .attemptNumber(attemptNumber)
                .status(ForwardAttemptStatus.PENDING)
                .nextRetryAt(Instant.now().plusSeconds(60))
                .build();
    }

    @Test
    @DisplayName("a Forward outstanding past the cap is moved to DLQ with a reason")
    void escalatesPastTheCap() {
        UUID eventId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        IncomingForwardAttempt attempt = pendingAttempt(eventId, destinationId, 3);

        when(attemptRepository.findStaleForwardAttemptIds(any(Instant.class), anyInt()))
                .thenReturn(List.of(attempt.getId()));
        when(attemptRepository.findAllById(any())).thenReturn(List.of(attempt));

        service.runEscalation();

        ArgumentCaptor<List<IncomingForwardAttempt>> saved = ArgumentCaptor.forClass(List.class);
        verify(attemptRepository).saveAll(saved.capture());
        IncomingForwardAttempt escalated = saved.getValue().get(0);
        assertThat(escalated.getStatus()).isEqualTo(ForwardAttemptStatus.DLQ);
        assertThat(escalated.getFinishedAt()).isNotNull();
        assertThat(escalated.getNextRetryAt())
                .as("a DLQ'd Forward must not stay claimable by the retry scheduler")
                .isNull();
        assertThat(escalated.getErrorMessage()).contains("Hard-cap escalation").contains("24h");
    }

    @Test
    @DisplayName("the cutoff handed to the query is the cap behind now")
    void cutoffIsTheCapBehindNow() {
        when(attemptRepository.findStaleForwardAttemptIds(any(Instant.class), anyInt())).thenReturn(List.of());

        Instant before = Instant.now();
        service.runEscalation();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(attemptRepository).findStaleForwardAttemptIds(cutoff.capture(), eq(100));
        // Bracketed: toHours() truncates and read 23 for an exact 24h cutoff.
        Duration cap = Duration.ofHours(HARD_CAP_HOURS);
        assertThat(cutoff.getValue())
                .isBetween(before.minus(cap), after.minus(cap));
    }

    @Test
    @DisplayName("the gauge the stale-forward alert reads is the age of the oldest pending Forward")
    void oldestPendingAgeGaugeIsPublished() {
        Instant received = Instant.now().minus(Duration.ofHours(5));
        when(attemptRepository.findOldestPendingForwardStartedAt()).thenReturn(received);
        when(attemptRepository.findStaleForwardAttemptIds(any(Instant.class), anyInt())).thenReturn(List.of());

        service.runEscalation();

        double ageSeconds = meterRegistry.get("forward_oldest_pending_age_seconds").gauge().value();
        assertThat(ageSeconds).isCloseTo(Duration.ofHours(5).getSeconds(), offset(60.0));
    }

    @Test
    @DisplayName("no pending Forwards reports zero rather than leaving the last value standing")
    void gaugeResetsWhenNothingPending() {
        when(attemptRepository.findOldestPendingForwardStartedAt()).thenReturn(null);
        when(attemptRepository.findStaleForwardAttemptIds(any(Instant.class), anyInt())).thenReturn(List.of());

        service.runEscalation();

        assertThat(meterRegistry.get("forward_oldest_pending_age_seconds").gauge().value()).isZero();
    }
}
