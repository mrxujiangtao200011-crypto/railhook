package com.webhook.platform.common.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatusTransitionTest {

    @Test
    void aSucceededDeliveryNeverMovesAgain() {
        for (DeliveryStatus next : DeliveryStatus.values()) {
            assertThat(DeliveryStatus.SUCCESS.canMoveTo(next)).as("SUCCESS -> " + next).isFalse();
        }
    }

    @Test
    void onlyAClaimedDeliveryReachesAnOutcome() {
        assertThat(DeliveryStatus.PENDING.canMoveTo(DeliveryStatus.SUCCESS)).isFalse();
        assertThat(DeliveryStatus.PENDING.canMoveTo(DeliveryStatus.FAILED)).isFalse();
        assertThat(DeliveryStatus.PROCESSING.canMoveTo(DeliveryStatus.SUCCESS)).isTrue();
        assertThat(DeliveryStatus.PROCESSING.canMoveTo(DeliveryStatus.CANCELLED)).isTrue();
    }

    @Test
    void aPersonCanPutAFinishedDeliveryBackOnItsLadder() {
        assertThat(DeliveryStatus.DLQ.canMoveTo(DeliveryStatus.PENDING)).isTrue();
        assertThat(DeliveryStatus.FAILED.canMoveTo(DeliveryStatus.PENDING)).isTrue();
        assertThat(DeliveryStatus.DLQ.canMoveTo(DeliveryStatus.PROCESSING)).isFalse();
    }

    @Test
    void aForwardAttemptInTheDlqIsOnlySupersededByARetry() {
        assertThat(ForwardAttemptStatus.DLQ.canMoveTo(ForwardAttemptStatus.FAILED)).isTrue();
        assertThat(ForwardAttemptStatus.DLQ.canMoveTo(ForwardAttemptStatus.PENDING)).isFalse();
        assertThatThrownBy(() -> ForwardAttemptStatus.SUCCESS.moveTo(ForwardAttemptStatus.PENDING))
                .isInstanceOf(IllegalStateException.class);
    }
}
