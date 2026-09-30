package com.webhook.platform.api.service;

import com.webhook.platform.api.dto.AnalyticsResponse;
import com.webhook.platform.api.dto.AnalyticsResponse.*;
import com.webhook.platform.api.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.webhook.platform.api.domain.enums.EndpointHealth;
import com.webhook.platform.common.enums.DeliveryStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private static final Duration HOURLY_UP_TO = Duration.ofDays(2);
    // Attempts and events are kept 90 days by default; a longer range would chart deleted data as zero.
    private static final Duration MAX_SPAN = Duration.ofDays(90);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Map<String, Duration> PERIODS = Map.of(
            "24h", Duration.ofHours(24), "7d", Duration.ofDays(7), "30d", Duration.ofDays(30));
    private static final DateTimeFormatter FILENAME_INSTANT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'").withZone(ZoneOffset.UTC);

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository attemptRepository;
    private final EventRepository eventRepository;
    private final EndpointRepository endpointRepository;
    private final ProjectRepository projectRepository;

    public record Window(Instant from, Instant to, String granularity) {

        public static Window of(String period, Instant from, Instant to, Instant now) {
            if (from == null && to == null) {
                Duration span = PERIODS.get(period == null ? "24h" : period.toLowerCase());
                if (span == null) {
                    throw new IllegalArgumentException("period must be 24h, 7d or 30d");
                }
                return between(now.minus(span), now);
            }
            if (period != null) {
                throw new IllegalArgumentException("Pass either period or from and to, not both");
            }
            if (from == null || to == null) {
                throw new IllegalArgumentException("A custom range needs both from and to");
            }
            if (!from.isBefore(to)) {
                throw new IllegalArgumentException("from must be before to");
            }
            if (to.isAfter(now.plus(CLOCK_SKEW))) {
                throw new IllegalArgumentException("to must not be in the future");
            }
            if (Duration.between(from, to).compareTo(MAX_SPAN) > 0) {
                throw new IllegalArgumentException("A custom range can span at most 90 days");
            }
            return between(from, to);
        }

        private static Window between(Instant from, Instant to) {
            return new Window(from, to,
                    Duration.between(from, to).compareTo(HOURLY_UP_TO) <= 0 ? "HOUR" : "DAY");
        }
    }

    public AnalyticsResponse getAnalytics(UUID projectId, Window window) {
        Instant from = window.from();
        Instant to = window.to();
        String granularity = window.granularity();

        TimeRange timeRange = TimeRange.builder()
                .from(from.toString())
                .to(to.toString())
                .granularity(granularity)
                .build();

        OverviewMetrics overview = calculateOverviewMetrics(projectId, from, to);
        List<TimeSeriesPoint> deliveryTimeSeries = calculateDeliveryTimeSeries(projectId, from, to, granularity);
        List<TimeSeriesPoint> latencyTimeSeries = calculateLatencyTimeSeries(projectId, from, to, granularity);
        List<EventTypeBreakdown> eventTypeBreakdown = calculateEventTypeBreakdown(projectId, from, to);
        List<EndpointPerformance> endpointPerformance = calculateEndpointPerformance(projectId, from, to);
        LatencyPercentiles latencyPercentiles = calculateLatencyPercentiles(projectId, from, to);

        return AnalyticsResponse.builder()
                .timeRange(timeRange)
                .overview(overview)
                .deliveryTimeSeries(deliveryTimeSeries)
                .latencyTimeSeries(latencyTimeSeries)
                .eventTypeBreakdown(eventTypeBreakdown)
                .endpointPerformance(endpointPerformance)
                .latencyPercentiles(latencyPercentiles)
                .build();
    }

    public String exportFilename(UUID projectId, Window window) {
        String name = projectRepository.findById(projectId)
                .map(project -> project.getName().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", ""))
                .filter(slug -> !slug.isEmpty())
                .orElse(projectId.toString());
        return "analytics-" + name + "-" + FILENAME_INSTANT.format(window.from())
                + "-" + FILENAME_INSTANT.format(window.to()) + ".csv";
    }

    private OverviewMetrics calculateOverviewMetrics(UUID projectId, Instant from, Instant to) {
        long totalEvents = eventRepository.countByProjectIdAndCreatedAtBetween(projectId, from, to);
        long totalDeliveries = deliveryRepository.countByProjectIdAndCreatedAtBetween(projectId, from, to);
        long successfulDeliveries = deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(
                projectId, DeliveryStatus.SUCCESS, from, to);
        // FAILED and DLQ together, as the time series counts them, so the card agrees with the chart.
        long failedDeliveries = deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(
                projectId, DeliveryStatus.FAILED, from, to)
                + deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(
                projectId, DeliveryStatus.DLQ, from, to);

        double successRate = totalDeliveries > 0 
                ? (double) successfulDeliveries / totalDeliveries * 100 
                : 0;

        Double avgLatency = attemptRepository.findAverageLatencyByProjectIdAndAttemptedAtBetween(TenantContext.require(), 
                projectId, from, to);
        Long p50Latency = attemptRepository.findLatencyPercentileByProjectId(TenantContext.require(), projectId, from, to, 0.50);
        Long p95Latency = attemptRepository.findLatencyPercentileByProjectId(TenantContext.require(), projectId, from, to, 0.95);
        Long p99Latency = attemptRepository.findLatencyPercentileByProjectId(TenantContext.require(), projectId, from, to, 0.99);

        long durationSeconds = ChronoUnit.SECONDS.between(from, to);
        double eventsPerSecond = durationSeconds > 0 ? (double) totalEvents / durationSeconds : 0;
        double deliveriesPerSecond = durationSeconds > 0 ? (double) totalDeliveries / durationSeconds : 0;

        return OverviewMetrics.builder()
                .totalEvents(totalEvents)
                .totalDeliveries(totalDeliveries)
                .successfulDeliveries(successfulDeliveries)
                .failedDeliveries(failedDeliveries)
                .successRate(Math.round(successRate * 100.0) / 100.0)
                .avgLatencyMs(avgLatency != null ? Math.round(avgLatency * 100.0) / 100.0 : 0)
                .p50LatencyMs(p50Latency != null ? p50Latency : 0)
                .p95LatencyMs(p95Latency != null ? p95Latency : 0)
                .p99LatencyMs(p99Latency != null ? p99Latency : 0)
                .eventsPerSecond(Math.round(eventsPerSecond * 1000.0) / 1000.0)
                .deliveriesPerSecond(Math.round(deliveriesPerSecond * 1000.0) / 1000.0)
                .build();
    }

    private List<TimeSeriesPoint> calculateDeliveryTimeSeries(
            UUID projectId, Instant from, Instant to, String granularity) {
        List<Object[]> rawData = "HOUR".equals(granularity)
                ? deliveryRepository.findDeliveryTimeSeriesByHour(TenantContext.require(), projectId, from, to)
                : deliveryRepository.findDeliveryTimeSeriesByDay(TenantContext.require(), projectId, from, to);

        return rawData.stream()
                .map(row -> TimeSeriesPoint.builder()
                        .timestamp((String) row[0])
                        .total(((Number) row[1]).longValue())
                        .success(((Number) row[2]).longValue())
                        .failed(((Number) row[3]).longValue())
                        .build())
                .collect(Collectors.toList());
    }

    private List<TimeSeriesPoint> calculateLatencyTimeSeries(
            UUID projectId, Instant from, Instant to, String granularity) {
        List<Object[]> rawData = "HOUR".equals(granularity)
                ? attemptRepository.findLatencyTimeSeriesByHour(TenantContext.require(), projectId, from, to)
                : attemptRepository.findLatencyTimeSeriesByDay(TenantContext.require(), projectId, from, to);

        return rawData.stream()
                .map(row -> TimeSeriesPoint.builder()
                        .timestamp((String) row[0])
                        .total(((Number) row[1]).longValue())
                        .avgLatencyMs(row[2] != null ? ((Number) row[2]).doubleValue() : null)
                        .build())
                .collect(Collectors.toList());
    }

    private List<EventTypeBreakdown> calculateEventTypeBreakdown(UUID projectId, Instant from, Instant to) {
        List<Object[]> rawData = eventRepository.findEventTypeBreakdownByProjectId(TenantContext.require(), projectId, from, to);
        long total = rawData.stream().mapToLong(row -> ((Number) row[1]).longValue()).sum();

        return rawData.stream()
                .map(row -> {
                    long count = ((Number) row[1]).longValue();
                    long successCount = row[2] != null ? ((Number) row[2]).longValue() : 0;
                    return EventTypeBreakdown.builder()
                            .eventType((String) row[0])
                            .count(count)
                            .percentage(total > 0 ? Math.round((double) count / total * 10000.0) / 100.0 : 0)
                            .successCount(successCount)
                            .successRate(count > 0 ? Math.round((double) successCount / count * 10000.0) / 100.0 : 0)
                            .build();
                })
                .collect(Collectors.toList());
    }

    private List<EndpointPerformance> calculateEndpointPerformance(UUID projectId, Instant from, Instant to) {
        List<Object[]> rawData = deliveryRepository.findEndpointPerformanceByProjectId(TenantContext.require(), projectId, from, to);

        return rawData.stream()
                .map(row -> {
                    long totalDeliveries = ((Number) row[3]).longValue();
                    long successfulDeliveries = ((Number) row[4]).longValue();
                    long failedDeliveries = ((Number) row[5]).longValue();
                    double successRate = totalDeliveries > 0 
                            ? (double) successfulDeliveries / totalDeliveries * 100 
                            : 0;

                    return EndpointPerformance.builder()
                            .endpointId((String) row[0])
                            .url((String) row[1])
                            .enabled((Boolean) row[2])
                            .totalDeliveries(totalDeliveries)
                            .successfulDeliveries(successfulDeliveries)
                            .failedDeliveries(failedDeliveries)
                            .successRate(Math.round(successRate * 100.0) / 100.0)
                            .avgLatencyMs(row[6] != null ? ((Number) row[6]).doubleValue() : 0)
                            .p95LatencyMs(row[7] != null ? ((Number) row[7]).longValue() : 0)
                            .lastDeliveryAt(row[8] != null ? row[8].toString() : null)
                            .status(EndpointHealth.of(totalDeliveries, successRate))
                            .build();
                })
                .collect(Collectors.toList());
    }

    private LatencyPercentiles calculateLatencyPercentiles(UUID projectId, Instant from, Instant to) {
        return LatencyPercentiles.builder()
                .p50(getPercentile(projectId, from, to, 0.50))
                .p75(getPercentile(projectId, from, to, 0.75))
                .p90(getPercentile(projectId, from, to, 0.90))
                .p95(getPercentile(projectId, from, to, 0.95))
                .p99(getPercentile(projectId, from, to, 0.99))
                .max(getPercentile(projectId, from, to, 1.0))
                .build();
    }

    private long getPercentile(UUID projectId, Instant from, Instant to, double percentile) {
        Long value = attemptRepository.findLatencyPercentileByProjectId(TenantContext.require(), projectId, from, to, percentile);
        return value != null ? value : 0;
    }
}
