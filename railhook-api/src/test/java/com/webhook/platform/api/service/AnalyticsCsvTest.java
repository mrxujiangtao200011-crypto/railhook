package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.enums.EndpointHealth;
import com.webhook.platform.api.dto.AnalyticsResponse;
import com.webhook.platform.api.dto.AnalyticsResponse.EndpointPerformance;
import com.webhook.platform.api.dto.AnalyticsResponse.TimeSeriesPoint;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsCsvTest {

    @Test
    void writesOneRowPerBucketWithTheAttemptsOfThatBucket() {
        AnalyticsResponse analytics = AnalyticsResponse.builder()
                .deliveryTimeSeries(List.of(
                        point("2026-09-29T10:00:00Z", 5, 4, 1, null),
                        point("2026-09-29T11:00:00Z", 2, 2, 0, null)))
                .latencyTimeSeries(List.of(
                        point("2026-09-29T11:00:00Z", 3, 0, 0, 120.5),
                        point("2026-09-29T12:00:00Z", 1, 0, 0, 80.0)))
                .endpointPerformance(List.of())
                .build();

        assertThat(csv(analytics)).startsWith("""
                bucket_start,deliveries,delivered,failed,attempts,avg_latency_ms\r
                2026-09-29T10:00:00Z,5,4,1,0,\r
                2026-09-29T11:00:00Z,2,2,0,3,120.5\r
                2026-09-29T12:00:00Z,0,0,0,1,80.0\r
                """);
    }

    @Test
    void anEndpointUrlASpreadsheetWouldEvaluateIsWrittenAsText() {
        AnalyticsResponse analytics = AnalyticsResponse.builder()
                .deliveryTimeSeries(List.of())
                .latencyTimeSeries(List.of())
                .endpointPerformance(List.of(
                        endpoint("=HYPERLINK(\"http://evil.test\",\"x\")"),
                        endpoint("+1"), endpoint("-1"), endpoint("@SUM(A1)"),
                        endpoint("https://example.test/a,b")))
                .build();

        assertThat(csv(analytics)).contains(
                ",\"'=HYPERLINK(\"\"http://evil.test\"\",\"\"x\"\")\",",
                ",'+1,", ",'-1,", ",'@SUM(A1),",
                ",\"https://example.test/a,b\",");
    }

    private static String csv(AnalyticsResponse analytics) {
        StringWriter out = new StringWriter();
        AnalyticsCsv.write(analytics, new PrintWriter(out));
        return out.toString();
    }

    private static TimeSeriesPoint point(String timestamp, long total, long success, long failed, Double latency) {
        return TimeSeriesPoint.builder().timestamp(timestamp).total(total).success(success).failed(failed)
                .avgLatencyMs(latency).build();
    }

    private static EndpointPerformance endpoint(String url) {
        return EndpointPerformance.builder().endpointId("ep").url(url).enabled(true)
                .totalDeliveries(1).successfulDeliveries(1).successRate(100)
                .status(EndpointHealth.HEALTHY).build();
    }
}
