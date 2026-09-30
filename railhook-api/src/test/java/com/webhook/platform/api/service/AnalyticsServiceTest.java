package com.webhook.platform.api.service;

import com.webhook.platform.common.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.AnalyticsResponse;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalyticsServiceTest {

    @Mock private DeliveryRepository deliveryRepository;
    @Mock private DeliveryAttemptRepository attemptRepository;
    @Mock private EventRepository eventRepository;
    @Mock private EndpointRepository endpointRepository;
    @Mock private ProjectRepository projectRepository;

    private AnalyticsService analyticsService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.set(orgId);
        analyticsService = new AnalyticsService(
                deliveryRepository, attemptRepository, eventRepository, endpointRepository, projectRepository);

        when(eventRepository.countByProjectIdAndCreatedAtBetween(eq(projectId), any(), any())).thenReturn(10L);
        when(deliveryRepository.countByProjectIdAndCreatedAtBetween(eq(projectId), any(), any())).thenReturn(30L);
        when(deliveryRepository.findDeliveryTimeSeriesByHour(any(), eq(projectId), any(), any()))
                .thenReturn(List.of());
        when(deliveryRepository.findDeliveryTimeSeriesByDay(any(), eq(projectId), any(), any()))
                .thenReturn(List.of());
        when(deliveryRepository.findEndpointPerformanceByProjectId(any(), eq(projectId), any(), any()))
                .thenReturn(List.of());
        when(eventRepository.findEventTypeBreakdownByProjectId(any(), eq(projectId), any(), any()))
                .thenReturn(List.of());
        when(attemptRepository.findLatencyTimeSeriesByHour(any(), eq(projectId), any(), any()))
                .thenReturn(List.of());
        when(attemptRepository.findLatencyTimeSeriesByDay(any(), eq(projectId), any(), any()))
                .thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // The time series already counted DLQ as failed, so the overview card contradicted the chart.
    @Test
    void overviewCountsDlqAsFailed() {
        when(deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(
                eq(projectId), eq(DeliveryStatus.SUCCESS), any(), any())).thenReturn(20L);
        when(deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(
                eq(projectId), eq(DeliveryStatus.FAILED), any(), any())).thenReturn(3L);
        when(deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(
                eq(projectId), eq(DeliveryStatus.DLQ), any(), any())).thenReturn(7L);

        AnalyticsResponse response = analyticsService.getAnalytics(projectId,
                AnalyticsService.Window.of("24h", null, null, Instant.now()));

        assertThat(response.getOverview().getFailedDeliveries()).isEqualTo(10L);
    }
    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    @Test
    void presetsKeepTheirWindowAndBuckets() {
        assertThat(AnalyticsService.Window.of("24h", null, null, NOW))
                .isEqualTo(new AnalyticsService.Window(NOW.minus(Duration.ofHours(24)), NOW, "HOUR"));
        assertThat(AnalyticsService.Window.of("30d", null, null, NOW))
                .isEqualTo(new AnalyticsService.Window(NOW.minus(Duration.ofDays(30)), NOW, "DAY"));
    }

    @Test
    void aCustomRangeIsBucketedByHourUpToTwoDaysAndByDayBeyond() {
        Instant from = NOW.minus(Duration.ofDays(5));
        assertThat(AnalyticsService.Window.of(null, from, from.plus(Duration.ofDays(2)), NOW).granularity())
                .isEqualTo("HOUR");
        assertThat(AnalyticsService.Window.of(null, from, from.plus(Duration.ofDays(2)).plusSeconds(1), NOW)
                .granularity()).isEqualTo("DAY");
    }

    @Test
    void refusesARangeThatDoesNotEndAfterItStarts() {
        assertThatThrownBy(() -> AnalyticsService.Window.of(null, NOW.minusSeconds(60), NOW.minusSeconds(60), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from must be before to");
    }

    @Test
    void refusesARangeLongerThanAttemptsAreKept() {
        assertThatThrownBy(() -> AnalyticsService.Window.of(null,
                NOW.minus(Duration.ofDays(91)), NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("90 days");
    }

    @Test
    void refusesARangeEndingInTheFuture() {
        assertThatThrownBy(() -> AnalyticsService.Window.of(null,
                NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofHours(1)), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }

    @Test
    void refusesARangeWithOneEndOrMixedWithAPeriod() {
        assertThatThrownBy(() -> AnalyticsService.Window.of(null, NOW.minusSeconds(60), null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnalyticsService.Window.of("7d", NOW.minusSeconds(60), NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAnUnknownPeriod() {
        assertThatThrownBy(() -> AnalyticsService.Window.of("1y", null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("24h, 7d or 30d");
    }
}
