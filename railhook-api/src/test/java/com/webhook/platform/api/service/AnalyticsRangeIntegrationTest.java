package com.webhook.platform.api.service;

import com.webhook.platform.api.AbstractIntegrationTest;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.PlanRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.AnalyticsResponse;
import com.webhook.platform.api.dto.AnalyticsResponse.EndpointPerformance;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.enums.DeliveryStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class AnalyticsRangeIntegrationTest extends AbstractIntegrationTest {

    private static final Instant FROM = Instant.parse("2026-01-10T10:30:00Z");
    private static final Instant TO = Instant.parse("2026-01-11T09:30:00Z");

    @Autowired private AnalyticsService analyticsService;
    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EndpointRepository endpointRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;

    @BeforeEach
    void seedOrganization() {
        Plan plan = planRepository.findByName("self_hosted")
                .orElseGet(() -> planRepository.findAll().stream().findFirst().orElseThrow());
        orgId = organizationRepository.save(
                Organization.builder().name("Acme " + UUID.randomUUID()).plan(plan).build()).getId();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void aCustomRangeCountsEachDeliveryOfItsOwnProjectInItsOwnHourOnce() {
        UUID projectId = project("Payments API");
        UUID neighbourId = project("Shipping");
        Scope own = scope(projectId, "https://payments.test/hook");
        Scope neighbour = scope(neighbourId, "https://shipping.test/hook");

        delivery(own, DeliveryStatus.SUCCESS, "2026-01-10T10:29:59Z", null);
        delivery(own, DeliveryStatus.SUCCESS, "2026-01-10T10:45:00Z", 100);
        delivery(own, DeliveryStatus.FAILED, "2026-01-10T11:00:00Z", 300);
        delivery(own, DeliveryStatus.SUCCESS, "2026-01-10T11:59:59Z", null);
        delivery(own, DeliveryStatus.SUCCESS, "2026-01-11T09:30:01Z", null);
        delivery(neighbour, DeliveryStatus.SUCCESS, "2026-01-10T10:45:00Z", 5000);
        delivery(neighbour, DeliveryStatus.FAILED, "2026-01-10T11:30:00Z", null);

        TenantContext.set(orgId);
        AnalyticsResponse analytics = analyticsService.getAnalytics(projectId,
                new AnalyticsService.Window(FROM, TO, "HOUR"));

        assertThat(analytics.getDeliveryTimeSeries())
                .extracting("timestamp", "total", "success", "failed")
                .containsExactly(
                        tuple("2026-01-10T10:00:00Z", 1L, 1L, 0L),
                        tuple("2026-01-10T11:00:00Z", 2L, 1L, 1L));
        assertThat(analytics.getOverview().getTotalDeliveries()).isEqualTo(3);
        assertThat(analytics.getLatencyTimeSeries())
                .extracting("timestamp", "total", "avgLatencyMs")
                .containsExactly(
                        tuple("2026-01-10T10:00:00Z", 1L, 100.0),
                        tuple("2026-01-10T11:00:00Z", 1L, 300.0));
        assertThat(analytics.getEndpointPerformance())
                .extracting(EndpointPerformance::getUrl, EndpointPerformance::getTotalDeliveries)
                .containsExactly(tuple("https://payments.test/hook", 3L));
    }

    @Test
    void anEndpointCountsEachDeliveryOnceWhateverItsSubscriptionsAndAttempts() {
        UUID projectId = project("Payments API");
        Scope own = scope(projectId, "https://payments.test/hook");
        for (String type : new String[] {"order.*", "invoice.*", "customer.*"}) {
            jdbc.update("INSERT INTO subscriptions (project_id, endpoint_id, event_type, organization_id) VALUES (?, ?, ?, ?)",
                    projectId, own.endpointId(), type, orgId);
        }
        delivery(own, DeliveryStatus.SUCCESS, "2026-01-10T10:45:00Z", 100);
        UUID retried = delivery(own, DeliveryStatus.DLQ, "2026-01-10T11:00:00Z", 300);
        attempt(retried, 2, 400, "2026-01-10T11:01:00Z");
        attempt(retried, 3, 500, "2026-01-10T11:06:00Z");

        TenantContext.set(orgId);
        AnalyticsResponse analytics = analyticsService.getAnalytics(projectId,
                new AnalyticsService.Window(FROM, TO, "HOUR"));

        assertThat(analytics.getEndpointPerformance())
                .extracting(EndpointPerformance::getTotalDeliveries, EndpointPerformance::getSuccessfulDeliveries,
                        EndpointPerformance::getFailedDeliveries)
                .containsExactly(tuple(2L, 1L, 1L));
    }

    @Test
    void theExportIsNamedAfterTheProjectAndItsRange() {
        UUID projectId = project("Payments API");

        TenantContext.set(orgId);
        String filename = analyticsService.exportFilename(projectId, new AnalyticsService.Window(FROM, TO, "HOUR"));

        assertThat(filename).isEqualTo("analytics-payments-api-20260110T1030Z-20260111T0930Z.csv");
    }

    private record Scope(UUID projectId, UUID endpointId) {
    }

    private UUID project(String name) {
        return projectRepository.save(Project.builder().organizationId(orgId).name(name).build()).getId();
    }

    private Scope scope(UUID projectId, String url) {
        UUID endpointId = endpointRepository.save(Endpoint.builder()
                .organizationId(orgId).projectId(projectId).url(url)
                .secretEncrypted("encrypted").secretIv("iv").build()).getId();
        return new Scope(projectId, endpointId);
    }

    private UUID delivery(Scope scope, DeliveryStatus status, String createdAt, Integer attemptMs) {
        UUID eventId = eventRepository.save(Event.builder()
                .organizationId(orgId).projectId(scope.projectId())
                .eventType("payment.succeeded").payload("{}").build()).getId();
        Delivery delivery = deliveryRepository.saveAndFlush(Delivery.builder()
                .organizationId(orgId).eventId(eventId).endpointId(scope.endpointId())
                .status(status).build());
        jdbc.update("UPDATE deliveries SET created_at = ? WHERE id = ?",
                Instant.parse(createdAt).atOffset(ZoneOffset.UTC), delivery.getId());
        if (attemptMs != null) {
            attempt(delivery.getId(), 1, attemptMs, createdAt);
        }
        return delivery.getId();
    }

    private void attempt(UUID deliveryId, int number, int durationMs, String createdAt) {
        jdbc.update("""
                INSERT INTO delivery_attempts (id, organization_id, delivery_id, attempt_number, http_status_code,
                                               duration_ms, created_at)
                VALUES (?, ?, ?, ?, 200, ?, ?)""",
                UUID.randomUUID(), orgId, deliveryId, number, durationMs, Instant.parse(createdAt).atOffset(ZoneOffset.UTC));
    }
}
