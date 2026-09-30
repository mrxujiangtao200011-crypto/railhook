package com.webhook.platform.api.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.scheduling.support.CronExpression;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

public record WorkflowSchedule(CronExpression cron, ZoneId zone) {

    public static WorkflowSchedule parse(String cron, String timezone) {
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("A scheduled workflow needs a cron expression");
        }
        // Five fields, so the seconds are always 0 and nothing fires more than once a minute.
        if (cron.trim().split("\\s+").length != 5) {
            throw new IllegalArgumentException(
                    "Cron expression must have 5 fields: minute hour day-of-month month day-of-week");
        }
        CronExpression expression;
        try {
            expression = CronExpression.parse("0 " + cron.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cron expression: " + e.getMessage());
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Unknown time zone: " + timezone);
        }
        WorkflowSchedule schedule = new WorkflowSchedule(expression, zone);
        if (schedule.nextAfter(Instant.now()) == null) {
            throw new IllegalArgumentException("Cron expression never fires: " + cron);
        }
        return schedule;
    }

    public static WorkflowSchedule of(JsonNode triggerConfig) {
        return parse(triggerConfig.path("cron").asText(null), triggerConfig.path("timezone").asText(null));
    }

    public Instant nextAfter(Instant instant) {
        ZonedDateTime next = cron.next(instant.atZone(zone));
        return next == null ? null : next.toInstant();
    }

    public List<Instant> nextRuns(Instant after, int count) {
        List<Instant> runs = new ArrayList<>();
        for (Instant next = nextAfter(after); next != null && runs.size() < count; next = nextAfter(next)) {
            runs.add(next);
        }
        return runs;
    }
}
