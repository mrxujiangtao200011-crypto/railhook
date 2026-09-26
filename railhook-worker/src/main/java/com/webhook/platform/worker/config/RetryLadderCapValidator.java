package com.webhook.platform.worker.config;

import com.webhook.platform.common.retry.RetryLadderDefaults;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when a Retry Ladder outlives its direction's hard cap, which would silently
 * drop the last tiers. Each direction is checked against its own cap.
 */
@Component
@RequiredArgsConstructor
public class RetryLadderCapValidator {

    @Value("${delivery.escalation.hard-cap-hours:96}")
    private final long deliveryHardCapHours;
    @Value("${forward.escalation.hard-cap-hours:24}")
    private final long forwardHardCapHours;

    @PostConstruct
    public void validate() {
        RetryLadderDefaults.outgoing().requireFitsWithin(
                deliveryHardCapHours * 3600L, "outgoing default", "delivery.escalation.hard-cap-hours");
        RetryLadderDefaults.incoming().requireFitsWithin(
                forwardHardCapHours * 3600L, "incoming default", "forward.escalation.hard-cap-hours");
    }
}
