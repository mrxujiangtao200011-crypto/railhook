package com.webhook.platform.worker.service;

import com.webhook.platform.common.retry.RetryableStatuses;
import com.webhook.platform.common.retry.RetryAfter;
import com.webhook.platform.worker.attempt.TargetFailureRecorder;
import org.springframework.dao.QueryTimeoutException;
import com.webhook.platform.worker.attempt.AttemptRunner;
import com.webhook.platform.worker.attempt.ForwardAttemptMetrics;
import com.webhook.platform.worker.attempt.IncomingAttemptStoreFactory;
import com.webhook.platform.worker.attempt.ProjectStatusLookup;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.common.enums.IncomingAuthType;
import com.webhook.platform.worker.domain.entity.IncomingDestination;
import com.webhook.platform.worker.domain.entity.IncomingEvent;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.worker.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.worker.domain.repository.IncomingEventRepository;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import com.webhook.platform.worker.exception.PayloadTransformException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncomingForwardServiceTest {

    // A mocked repository carries no token across, so stampClaimToken does what the claiming UPDATE would.
    private final AtomicReference<UUID> claimedToken = new AtomicReference<>(UUID.randomUUID());

    private List<IncomingForwardAttempt> asClaimed(IncomingForwardAttempt attempt) {
        if (attempt.getStatus() == ForwardAttemptStatus.PROCESSING && attempt.getClaimToken() == null) {
            attempt.setClaimToken(claimedToken.get());
        }
        return List.of(attempt);
    }

    @Mock
    private IncomingEventRepository eventRepository;
    @Mock
    private IncomingDestinationRepository destinationRepository;
    @Mock
    private IncomingForwardAttemptRepository attemptRepository;
    @Mock
    private TransformationCacheService transformationCacheService;
    @Mock
    private PayloadTransformService payloadTransformService;
    @Mock
    private EncryptionKeyRegistry encryptionKeyRegistry;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private RedisConcurrencyControlService concurrencyControlService;
    @Mock
    private CircuitBreakerService circuitBreakerService;
    @Mock
    private ProjectRateLimiterService projectRateLimiterService;


    // Incoming Destinations have no per-target rate limit; present only for the constructor.
    @Mock
    private RedisRateLimiterService redisRateLimiterService;

    private IncomingForwardService service;

    private final UUID eventId = UUID.randomUUID();
    private final UUID destinationId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private void stubTransactionTemplate() {
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            var callback = inv.getArgument(0, TransactionCallback.class);
            return callback.doInTransaction(null);
        });
        lenient().doAnswer(inv -> {
            Consumer<Object> callback = inv.getArgument(0, Consumer.class);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @BeforeEach
    void setUp() {
        stubTransactionTemplate();
        // Nobody else is writing: the row lock finds the row as the read left it.
        lenient().when(attemptRepository.holdIfStillClaimed(any(), any(), any())).thenReturn(1);

        when(projectRateLimiterService.tryAcquire(any(UUID.class))).thenReturn(true);
        when(circuitBreakerService.isCallPermitted(any(UUID.class))).thenReturn(true);
        when(concurrencyControlService.tryAcquireForTenant(any(UUID.class))).thenReturn(true);
        when(concurrencyControlService.tryAcquireForTarget(any(UUID.class))).thenReturn(true);

        service = newService(WebClient.builder().build(), new SimpleMeterRegistry(), newAttemptRunner(true));
    }

    private IncomingForwardService newService(WebClient webClient, MeterRegistry registry, AttemptRunner runner) {
        IncomingAttemptStoreFactory storeFactory = new IncomingAttemptStoreFactory(
                attemptRepository, activeProjects(), transactionTemplate, transformationCacheService,
                payloadTransformService, encryptionKeyRegistry, new ObjectMapper(),
                webClient, mock(TargetFailureRecorder.class));
        return new IncomingForwardService(eventRepository, destinationRepository, attemptRepository,
                transactionTemplate, runner, storeFactory, new ForwardAttemptMetrics(registry),
                Clock.systemUTC(), 300, 500, 5);
    }

    private IncomingForwardAttempt claimedRow(int attemptNumber, UUID replaySessionId) {
        return IncomingForwardAttempt.builder()
                .id(UUID.randomUUID()).incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(attemptNumber).replaySessionId(replaySessionId)
                .status(ForwardAttemptStatus.PROCESSING).claimToken(claimedToken.get())
                .build();
    }


    // The attempt lifecycle lives in the Runner, so wire a real one.
    private AttemptRunner newAttemptRunner(boolean allowPrivateIps) {
        return new AttemptRunner(
                projectRateLimiterService, redisRateLimiterService, concurrencyControlService,
                circuitBreakerService, new ObjectMapper(), allowPrivateIps, List.of(), RetryAfter.DEFAULT_MAX_SECONDS);
    }

    private IncomingEvent buildEvent() {
        return IncomingEvent.builder()
                .id(eventId).incomingSourceId(sourceId)
                .requestId("req-1").method("POST")
                .bodyRaw("{\"data\":1}").contentType("application/json")
                .receivedAt(Instant.now())
                .build();
    }

    private IncomingDestination buildDestination() {
        return IncomingDestination.builder()
                .id(destinationId).incomingSourceId(sourceId)
                .url("https://example.com/hook")
                .authType(IncomingAuthType.NONE)
                .enabled(true).maxAttempts(5).timeoutSeconds(30)
                .retryDelays("60,300")
                .retryableStatuses(RetryableStatuses.DEFAULT_SPEC)
                .build();
    }

    @Test
    void ssrfFailure_claimsAndUpdatesExistingRow() {
        IncomingDestination dest = buildDestination();
        dest.setUrl("http://169.254.169.254/latest/meta-data");

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(dest));

        IncomingForwardAttempt existingAttempt = IncomingForwardAttempt.builder()
                .id(UUID.randomUUID()).incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(1).status(ForwardAttemptStatus.PROCESSING)
                .build();
        when(attemptRepository.findForwardAttempts(eventId, destinationId, null))
                .thenAnswer(inv -> asClaimed(existingAttempt));

        // allowPrivateIps=false so the SSRF check triggers.
        IncomingForwardService ssrfService = newService(
                WebClient.builder().build(), new SimpleMeterRegistry(), newAttemptRunner(false));

        IncomingForwardAttempt message = claimedRow(1, null);

        ssrfService.attempt(message);

        verify(attemptRepository, never()).saveAndFlush(any(IncomingForwardAttempt.class));

        ArgumentCaptor<IncomingForwardAttempt> captor = ArgumentCaptor.forClass(IncomingForwardAttempt.class);
        verify(attemptRepository).save(captor.capture());
        IncomingForwardAttempt saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ForwardAttemptStatus.FAILED);
        assertThat(saved.getErrorMessage()).contains("SSRF_PROTECTION");
    }

    @Test
    void eventNotFound_failsTheClaimedRow() {
        when(eventRepository.findById(eventId)).thenReturn(Optional.empty());

        IncomingForwardAttempt message = claimedRow(1, null);

        service.attempt(message);

        verify(attemptRepository).failIfStillClaimed(eq(message.getId()), eq(claimedToken.get()),
                eq("Incoming event not found"), any(Instant.class));
    }

    @Test
    void configuredTransformationMissing_failsAttemptAsRetryable_doesNotForwardRawBody() {
        IncomingDestination dest = buildDestination();
        UUID transformationId = UUID.randomUUID();
        dest.setTransformationId(transformationId);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(dest));
        // Deleted or disabled after being configured.
        when(transformationCacheService.findEnabled(transformationId)).thenReturn(null);

        IncomingForwardAttempt existingAttempt = IncomingForwardAttempt.builder()
                .id(UUID.randomUUID()).incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(2).status(ForwardAttemptStatus.PROCESSING)
                .build();
        when(attemptRepository.findForwardAttempts(eventId, destinationId, null))
                .thenAnswer(inv -> asClaimed(existingAttempt));

        WebClient mockWebClient = mock(WebClient.class);
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        IncomingForwardService localService = newService(
                mockWebClient, meterRegistry, newAttemptRunner(true));

        IncomingForwardAttempt message = claimedRow(2, null);

        localService.attempt(message);

        // The raw, untransformed body must never reach the destination.
        verifyNoInteractions(mockWebClient);
        verifyNoInteractions(payloadTransformService);

        ArgumentCaptor<IncomingForwardAttempt> captor = ArgumentCaptor.forClass(IncomingForwardAttempt.class);
        verify(attemptRepository, times(2)).save(captor.capture());
        List<IncomingForwardAttempt> saved = captor.getAllValues();

        // FAILED, not DLQ: attempt 2 of 5 is retryable.
        IncomingForwardAttempt failedUpdate = saved.stream()
                .filter(a -> a.getStatus() == ForwardAttemptStatus.FAILED)
                .findFirst().orElseThrow();
        assertThat(failedUpdate.getErrorMessage()).contains("TRANSFORM_FAILED");
        assertThat(failedUpdate.getResponseCode()).isNull();

        assertThat(saved.stream().anyMatch(a -> a.getStatus() == ForwardAttemptStatus.PENDING
                && a.getAttemptNumber() == 3)).isTrue();

        assertThat(meterRegistry.get("transform_failed_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    void configuredTransformationMissing_atMaxAttempts_goesToDlq_doesNotForwardRawBody() {
        IncomingDestination dest = buildDestination();
        dest.setMaxAttempts(2);
        UUID transformationId = UUID.randomUUID();
        dest.setTransformationId(transformationId);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(dest));
        when(transformationCacheService.findEnabled(transformationId)).thenReturn(null);

        IncomingForwardAttempt existingAttempt = IncomingForwardAttempt.builder()
                .id(UUID.randomUUID()).incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(2).status(ForwardAttemptStatus.PROCESSING)
                .build();
        when(attemptRepository.findForwardAttempts(eventId, destinationId, null))
                .thenAnswer(inv -> asClaimed(existingAttempt));

        WebClient mockWebClient = mock(WebClient.class);
        IncomingForwardService localService = newService(
                mockWebClient, new SimpleMeterRegistry(), newAttemptRunner(true));

        // The last attempt.
        IncomingForwardAttempt message = claimedRow(2, null);

        localService.attempt(message);

        verifyNoInteractions(mockWebClient);

        ArgumentCaptor<IncomingForwardAttempt> captor = ArgumentCaptor.forClass(IncomingForwardAttempt.class);
        verify(attemptRepository).save(captor.capture());
        IncomingForwardAttempt saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ForwardAttemptStatus.DLQ);
        assertThat(saved.getErrorMessage()).contains("Max attempts reached");
    }

    @Test
    void inlineJsonPathTransformFails_failsAttemptAsRetryable_doesNotForwardRawBody() {
        IncomingDestination dest = buildDestination();
        dest.setPayloadTransform("$.this.path.does.not.exist");

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(dest));

        IncomingForwardAttempt existingAttempt = IncomingForwardAttempt.builder()
                .id(UUID.randomUUID()).incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(1).status(ForwardAttemptStatus.PROCESSING)
                .build();
        when(attemptRepository.findForwardAttempts(eventId, destinationId, null))
                .thenAnswer(inv -> asClaimed(existingAttempt));

        WebClient mockWebClient = mock(WebClient.class);
        IncomingForwardService localService = newService(
                mockWebClient, new SimpleMeterRegistry(), newAttemptRunner(true));

        IncomingForwardAttempt message = claimedRow(1, null);

        localService.attempt(message);

        verifyNoInteractions(mockWebClient);

        ArgumentCaptor<IncomingForwardAttempt> captor = ArgumentCaptor.forClass(IncomingForwardAttempt.class);
        verify(attemptRepository, times(2)).save(captor.capture());
        List<IncomingForwardAttempt> saved = captor.getAllValues();
        IncomingForwardAttempt failedUpdate = saved.stream()
                .filter(a -> a.getStatus() == ForwardAttemptStatus.FAILED)
                .findFirst().orElseThrow();
        assertThat(failedUpdate.getErrorMessage()).contains("TRANSFORM_FAILED");
    }

    // A late writer once overwrote the terminal row and queued a successor, forwarding twice.
    @Test
    void updateAttempt_rowAlreadyTerminal_doesNotOverwriteAndDoesNotScheduleSuccessor() {
        IncomingForwardAttempt alreadySucceeded = IncomingForwardAttempt.builder()
                .id(UUID.randomUUID())
                .incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(1)
                .status(ForwardAttemptStatus.SUCCESS)
                .build();

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(buildDestination()));
        when(attemptRepository.findForwardAttempts(eventId, destinationId, null))
                .thenAnswer(inv -> asClaimed(alreadySucceeded));
        when(transformationCacheService.findById(any())).thenReturn(Optional.empty());
        when(payloadTransformService.transform(anyString(), any()))
                .thenThrow(new PayloadTransformException("boom"));

        IncomingForwardAttempt message = claimedRow(1, null);

        service.attempt(message);

        // Neither the overwrite nor the duplicate successor row.
        verify(attemptRepository, never()).save(any(IncomingForwardAttempt.class));
        assertThat(alreadySucceeded.getStatus()).isEqualTo(ForwardAttemptStatus.SUCCESS);
    }

    @Test
    void malformedRetryLadder_failsTerminallyWithoutForwarding() {
        // Retrying cannot fix an unparseable ladder; throwing after the call would leave the row PROCESSING forever.
        IncomingDestination destination = buildDestination();
        destination.setRetryDelays("60,oops,900");

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(destination));

        IncomingForwardAttempt claimed = IncomingForwardAttempt.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(1).status(ForwardAttemptStatus.PROCESSING)
                .build();
        when(attemptRepository.findForwardAttempts(eventId, destinationId, null)).thenAnswer(inv -> asClaimed(claimed));

        IncomingForwardAttempt message = claimedRow(1, null);

        service.attempt(message);

        ArgumentCaptor<IncomingForwardAttempt> saved = ArgumentCaptor.forClass(IncomingForwardAttempt.class);
        verify(attemptRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus())
                .as("an unusable ladder must terminate the forward")
                .isEqualTo(ForwardAttemptStatus.FAILED);
        assertThat(saved.getValue().getErrorMessage())
                .as("the attempt row must say why")
                .contains("INVALID_RETRY_LADDER");

        // Checked after the claim but before admission: no permit taken, nothing sent.
        verify(concurrencyControlService, never()).tryAcquireForTarget(destinationId);
    }

    @Test
    void ladderExhausted_goesToDlq() {
        IncomingDestination destination = buildDestination();
        destination.setMaxAttempts(1); // exhausted by the first failure

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(destination));

        IncomingForwardAttempt claimed = IncomingForwardAttempt.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(1).status(ForwardAttemptStatus.PROCESSING)
                .build();
        when(attemptRepository.findForwardAttempts(eventId, destinationId, null)).thenAnswer(inv -> asClaimed(claimed));
        // Nothing listens there, so with maxAttempts=1 the attempt is abandoned.
        destination.setUrl("http://127.0.0.1:1/hook");

        IncomingForwardAttempt message = claimedRow(1, null);

        service.attempt(message);

        assertThat(claimed.getStatus()).isEqualTo(ForwardAttemptStatus.DLQ);
    }

    @Test
    void attemptReclaimedMidFlight_doesNotFinaliseOrQueueASuccessor() {
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(buildDestination()));

        // PROCESSING again under a different token: recovered and re-claimed while this POST was in flight.
        IncomingForwardAttempt reclaimed = IncomingForwardAttempt.builder()
                .id(UUID.randomUUID()).incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(1).status(ForwardAttemptStatus.PROCESSING)
                .claimToken(UUID.randomUUID())
                .build();
        when(attemptRepository.findForwardAttempts(eventId, destinationId, null))
                .thenReturn(List.of(reclaimed));

        service.attempt(claimedRow(1, null));

        // The status guard alone would pass; only the fence stops this overwriting another claim.
        assertThat(reclaimed.getStatus())
                .as("a reclaimed row must be left exactly as its current owner left it")
                .isEqualTo(ForwardAttemptStatus.PROCESSING);
        verify(attemptRepository, never()).save(argThat(a ->
                a != null && a.getAttemptNumber() == 2));
    }

    @Test
    void aFailedLookupDoesNotEscapeOntoThePollerThread() {
        when(eventRepository.findById(eventId))
                .thenThrow(new QueryTimeoutException("statement timeout"));

        IncomingForwardAttempt message = claimedRow(1, null);

        assertThatNoException().isThrownBy(() -> service.attempt(message));
    }

    // Every Project active: project status is not what this test is about.
    private static ProjectStatusLookup activeProjects() {
        return new ProjectStatusLookup(null) {
            @Override
            public ProjectStatus forProject(UUID projectId) {
                return ProjectStatus.ACTIVE;
            }

            @Override
            public ProjectStatus forSource(UUID sourceId) {
                return ProjectStatus.ACTIVE;
            }
        };
    }
}
