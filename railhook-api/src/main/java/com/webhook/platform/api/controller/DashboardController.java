package com.webhook.platform.api.controller;

import com.webhook.platform.api.dto.AnalyticsResponse;
import com.webhook.platform.api.dto.DashboardStatsResponse;
import com.webhook.platform.api.dto.OnboardingStatusResponse;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.service.AnalyticsCsv;
import com.webhook.platform.api.service.AnalyticsService;
import com.webhook.platform.api.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "Project dashboard, statistics and analytics")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
@RequiredArgsConstructor
public class DashboardController {
    
    private final DashboardService dashboardService;
    private final AnalyticsService analyticsService;
    
    @Operation(summary = "Get project dashboard", description = "Returns delivery statistics for a project")
    @GetMapping("/projects/{projectId}")
    public ResponseEntity<DashboardStatsResponse> getProjectDashboard(
            @PathVariable("projectId") UUID projectId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        log.debug("Dashboard stats request for projectId: {}", projectId);
        DashboardStatsResponse stats = dashboardService.getProjectStats(projectId);
        return ResponseEntity.ok(stats);
    }

    @Operation(summary = "Get onboarding status", description = "Returns server-derived onboarding checklist status")
    @GetMapping("/projects/{projectId}/onboarding")
    public ResponseEntity<OnboardingStatusResponse> getOnboardingStatus(
            @PathVariable("projectId") UUID projectId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        OnboardingStatusResponse status = dashboardService.getOnboardingStatus(projectId);
        return ResponseEntity.ok(status);
    }

    @Operation(summary = "Get project analytics",
            description = "Returns detailed analytics with time series data for a preset period or a custom "
                    + "from/to range of at most 90 days. Buckets are hourly up to two days, daily beyond.")
    @GetMapping("/projects/{projectId}/analytics")
    public ResponseEntity<AnalyticsResponse> getProjectAnalytics(
            @PathVariable("projectId") UUID projectId,
            @Parameter(description = "Time period: 24h, 7d, 30d. Defaults to 24h") @RequestParam(name = "period", required = false) String period,
            @Parameter(description = "Range start, ISO-8601 instant") @RequestParam(name = "from", required = false) Instant from,
            @Parameter(description = "Range end, ISO-8601 instant") @RequestParam(name = "to", required = false) Instant to,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        AnalyticsService.Window window = AnalyticsService.Window.of(period, from, to, Instant.now());
        return ResponseEntity.ok(analyticsService.getAnalytics(projectId, window));
    }

    @Operation(operationId = "exportProjectAnalytics", summary = "Export project analytics as CSV",
            description = "The same period or range as the analytics endpoint: one row per time bucket, "
                    + "then a blank line and one row per endpoint.")
    @GetMapping(value = "/projects/{projectId}/analytics/export", produces = "text/csv")
    public void exportProjectAnalytics(
            @PathVariable("projectId") UUID projectId,
            @Parameter(description = "Time period: 24h, 7d, 30d. Defaults to 24h") @RequestParam(name = "period", required = false) String period,
            @Parameter(description = "Range start, ISO-8601 instant") @RequestParam(name = "from", required = false) Instant from,
            @Parameter(description = "Range end, ISO-8601 instant") @RequestParam(name = "to", required = false) Instant to,
            AuthContext auth,
            HttpServletResponse response) throws IOException {
        auth.validateProjectAccess(projectId);
        AnalyticsService.Window window = AnalyticsService.Window.of(period, from, to, Instant.now());
        AnalyticsResponse analytics = analyticsService.getAnalytics(projectId, window);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(analyticsService.exportFilename(projectId, window)).build().toString());
        AnalyticsCsv.write(analytics, response.getWriter());
    }
}
