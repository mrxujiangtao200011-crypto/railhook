package com.webhook.platform.worker.config;

import com.webhook.platform.worker.domain.entity.Delivery;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.worker.service.BoundedAsyncExecutor;
import com.webhook.platform.worker.service.ClaimPoller;
import com.webhook.platform.worker.service.IncomingForwardService;
import com.webhook.platform.worker.service.WebhookDeliveryService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Separate pools so a flood in one direction cannot starve the other.
@Configuration
public class ExecutorConfig {

    @Bean
    public BoundedAsyncExecutor outgoingDeliveryExecutor(
            MeterRegistry meterRegistry,
            @Value("${webhook.outgoing-pool-size:50}") int poolSize,
            @Value("${webhook.async-shutdown-timeout-seconds:60}") long shutdownTimeoutSeconds) {
        return new BoundedAsyncExecutor("outgoing-delivery", poolSize, shutdownTimeoutSeconds, meterRegistry);
    }

    @Bean
    public BoundedAsyncExecutor incomingForwardExecutor(
            MeterRegistry meterRegistry,
            @Value("${webhook.incoming-pool-size:20}") int poolSize,
            @Value("${webhook.async-shutdown-timeout-seconds:60}") long shutdownTimeoutSeconds) {
        return new BoundedAsyncExecutor("incoming-forward", poolSize, shutdownTimeoutSeconds, meterRegistry);
    }

    @Bean
    public ClaimPoller<Delivery> deliveryClaimPoller(
            @Qualifier("outgoingDeliveryExecutor") BoundedAsyncExecutor executor,
            WebhookDeliveryService deliveries,
            @Value("${webhook.claim.idle-poll-ms:200}") long idlePollMs,
            @Value("${webhook.claim.enabled:true}") boolean enabled) {
        return new ClaimPoller<>("delivery-claims", executor, deliveries::claimDue, deliveries::attempt,
                idlePollMs, enabled);
    }

    @Bean
    public ClaimPoller<IncomingForwardAttempt> forwardClaimPoller(
            @Qualifier("incomingForwardExecutor") BoundedAsyncExecutor executor,
            IncomingForwardService forwards,
            @Value("${webhook.claim.idle-poll-ms:200}") long idlePollMs,
            @Value("${webhook.claim.enabled:true}") boolean enabled) {
        return new ClaimPoller<>("forward-claims", executor, forwards::claimDue, forwards::attempt,
                idlePollMs, enabled);
    }
}
