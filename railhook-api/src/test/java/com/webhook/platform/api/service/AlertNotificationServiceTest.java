package com.webhook.platform.api.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.AlertEvent;
import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.entity.Incident;
import com.webhook.platform.api.domain.enums.AlertChannel;
import com.webhook.platform.api.domain.enums.AlertSeverity;
import com.webhook.platform.api.domain.enums.AlertType;
import com.webhook.platform.api.domain.enums.OpsgenieRegion;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlertNotificationServiceTest {

    private static final String KEY = "R0UTING-KEY-7f3a9c1e";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EncryptionKeyRegistry registry = mock(EncryptionKeyRegistry.class);
    private final List<ClientRequest> sent = new ArrayList<>();
    private HttpStatus answer = HttpStatus.ACCEPTED;
    private final Incident incident = Incident.builder().id(UUID.randomUUID()).build();

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        when(registry.decrypt("cipher", "iv", 1)).thenReturn(KEY);
        logger = (Logger) LoggerFactory.getLogger(AlertNotificationService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void pagerDutyResolveCarriesTheDedupKeyItsTriggerOpened() throws Exception {
        AlertRule rule = rule(AlertChannel.PAGERDUTY);

        service(true).dispatch(rule, event(), incident);
        service(true).dispatchResolved(rule, incident);

        assertThat(sent).hasSize(2);
        assertThat(sent).allSatisfy(request ->
                assertThat(request.url().toString()).isEqualTo("https://events.pagerduty.com/v2/enqueue"));
        JsonNode trigger = body(sent.get(0));
        JsonNode resolve = body(sent.get(1));
        assertThat(trigger.get("routing_key").asText()).isEqualTo(KEY);
        assertThat(trigger.get("event_action").asText()).isEqualTo("trigger");
        assertThat(trigger.at("/payload/severity").asText()).isEqualTo("critical");
        assertThat(trigger.at("/payload/summary").asText()).contains("Payments failing");
        assertThat(resolve.get("event_action").asText()).isEqualTo("resolve");
        assertThat(resolve.get("routing_key").asText()).isEqualTo(KEY);
        assertThat(resolve.get("dedup_key").asText())
                .isEqualTo(trigger.get("dedup_key").asText())
                .contains(incident.getId().toString());
    }

    @Test
    void opsgenieClosesTheAlertItCreated_inTheRegionTheRuleNames() throws Exception {
        AlertRule rule = rule(AlertChannel.OPSGENIE);
        rule.setOpsgenieRegion(OpsgenieRegion.EU);

        service(true).dispatch(rule, event(), incident);
        service(true).dispatchResolved(rule, incident);

        assertThat(sent).hasSize(2);
        assertThat(sent).allSatisfy(request ->
                assertThat(request.headers().getFirst("Authorization")).isEqualTo("GenieKey " + KEY));
        JsonNode create = body(sent.get(0));
        String alias = create.get("alias").asText();
        assertThat(alias).contains(incident.getId().toString());
        assertThat(create.get("priority").asText()).isEqualTo("P1");
        assertThat(sent.get(0).url().toString()).isEqualTo("https://api.eu.opsgenie.com/v2/alerts");
        assertThat(sent.get(1).url().toString())
                .isEqualTo("https://api.eu.opsgenie.com/v2/alerts/" + alias + "/close?identifierType=alias");
    }

    @Test
    void theKeyNeverReachesTheLog_inDryRunOrWhenTheProviderRefuses() {
        service(false).dispatch(rule(AlertChannel.PAGERDUTY), event(), incident);
        answer = HttpStatus.BAD_REQUEST;
        service(true).dispatch(rule(AlertChannel.OPSGENIE), event(), incident);
        service(true).dispatchResolved(rule(AlertChannel.PAGERDUTY), incident);

        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list).noneSatisfy(logged ->
                assertThat(logged.getFormattedMessage()).contains(KEY));
    }

    private AlertNotificationService service(boolean enabled) {
        WebClient.Builder stub = WebClient.builder().exchangeFunction(request -> {
            sent.add(request);
            return Mono.just(ClientResponse.create(answer).build());
        });
        return new AlertNotificationService(stub, mock(EmailService.class), registry, enabled, false, List.of());
    }

    private AlertRule rule(AlertChannel channel) {
        return AlertRule.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .name("Payments failing")
                .alertType(AlertType.FAILURE_RATE)
                .severity(AlertSeverity.CRITICAL)
                .channel(channel)
                .thresholdValue(10.0)
                .integrationKeyEncrypted("cipher")
                .integrationKeyIv("iv")
                .encryptionKeyVersion(1)
                .build();
    }

    private AlertEvent event() {
        return AlertEvent.builder()
                .id(UUID.randomUUID())
                .severity(AlertSeverity.CRITICAL)
                .title("Payments failing")
                .message("Failure rate 80.0% over the last 5 minutes")
                .currentValue(80.0)
                .thresholdValue(10.0)
                .createdAt(Instant.now())
                .build();
    }

    private JsonNode body(ClientRequest request) throws Exception {
        MockClientHttpRequest captured = new MockClientHttpRequest(request.method(), request.url());
        @SuppressWarnings("unchecked")
        BodyInserter<Object, MockClientHttpRequest> inserter =
                (BodyInserter<Object, MockClientHttpRequest>) (BodyInserter<?, ?>) request.body();
        ExchangeStrategies strategies = ExchangeStrategies.withDefaults();
        inserter.insert(captured, new BodyInserter.Context() {
            @Override public List<HttpMessageWriter<?>> messageWriters() { return strategies.messageWriters(); }
            @Override public Optional<ServerHttpRequest> serverRequest() { return Optional.empty(); }
            @Override public Map<String, Object> hints() { return Map.of(); }
        }).block();
        return objectMapper.readTree(captured.getBodyAsString().block());
    }
}
