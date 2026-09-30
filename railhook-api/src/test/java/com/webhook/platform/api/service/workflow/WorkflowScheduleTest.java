package com.webhook.platform.api.service.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowScheduleTest {

    @ParameterizedTest(name = "[{0}] in [{1}] is refused")
    @CsvSource(delimiter = '|', value = {
            "*/10 * * * * *|UTC|5 fields",
            "0 25 * * *|UTC|Invalid cron expression",
            "0 0 30 2 *|UTC|never fires",
            "''|UTC|needs a cron expression",
            "0 9 * * *|Mars/Olympus|Unknown time zone"
    })
    void refusesAScheduleItCannotRun(String cron, String zone, String message) {
        assertThatThrownBy(() -> WorkflowSchedule.parse(cron, zone))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    @Test
    @DisplayName("a daily run keeps its local hour across the daylight-saving change")
    void followsDaylightSavingInItsZone() {
        WorkflowSchedule nineInNewYork = WorkflowSchedule.parse("0 9 * * *", "America/New_York");

        assertThat(nineInNewYork.nextAfter(Instant.parse("2026-03-07T15:00:00Z")))
                .isEqualTo(Instant.parse("2026-03-08T13:00:00Z"));
        assertThat(nineInNewYork.nextAfter(Instant.parse("2026-03-06T15:00:00Z")))
                .isEqualTo(Instant.parse("2026-03-07T14:00:00Z"));
    }
}
