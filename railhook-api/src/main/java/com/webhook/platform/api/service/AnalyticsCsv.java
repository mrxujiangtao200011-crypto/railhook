package com.webhook.platform.api.service;

import com.webhook.platform.api.dto.AnalyticsResponse;
import com.webhook.platform.api.dto.AnalyticsResponse.EndpointPerformance;
import com.webhook.platform.api.dto.AnalyticsResponse.TimeSeriesPoint;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class AnalyticsCsv {

    private AnalyticsCsv() {
    }

    public static void write(AnalyticsResponse analytics, PrintWriter out) {
        Map<String, TimeSeriesPoint> deliveries = byTimestamp(analytics.getDeliveryTimeSeries());
        Map<String, TimeSeriesPoint> attempts = byTimestamp(analytics.getLatencyTimeSeries());
        TreeSet<String> buckets = new TreeSet<>(deliveries.keySet());
        buckets.addAll(attempts.keySet());

        row(out, "bucket_start", "deliveries", "delivered", "failed", "attempts", "avg_latency_ms");
        for (String ts : buckets) {
            TimeSeriesPoint d = deliveries.getOrDefault(ts, new TimeSeriesPoint());
            TimeSeriesPoint a = attempts.getOrDefault(ts, new TimeSeriesPoint());
            row(out, ts, d.getTotal(), d.getSuccess(), d.getFailed(), a.getTotal(), a.getAvgLatencyMs());
        }

        out.print("\r\n");
        row(out, "endpoint_id", "endpoint_url", "status", "deliveries", "delivered", "failed",
                "success_rate", "avg_latency_ms", "p95_latency_ms", "last_delivery_at");
        for (EndpointPerformance e : analytics.getEndpointPerformance()) {
            row(out, e.getEndpointId(), e.getUrl(), e.getStatus(), e.getTotalDeliveries(),
                    e.getSuccessfulDeliveries(), e.getFailedDeliveries(), e.getSuccessRate(),
                    e.getAvgLatencyMs(), e.getP95LatencyMs(), e.getLastDeliveryAt());
        }
        out.flush();
    }

    private static Map<String, TimeSeriesPoint> byTimestamp(List<TimeSeriesPoint> points) {
        return points.stream().collect(Collectors.toMap(TimeSeriesPoint::getTimestamp, p -> p));
    }

    private static void row(PrintWriter out, Object... cells) {
        out.print(Stream.of(cells).map(AnalyticsCsv::cell).collect(Collectors.joining(",")));
        out.print("\r\n");
    }

    private static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
