package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.WorkflowStepExecution.StepStatus;
import com.webhook.platform.api.service.workflow.StepResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

class SlackNodeExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SlackNodeExecutor executor = new SlackNodeExecutor(WebClient.builder(), mapper);

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    void missingWebhookUrl_returnsFailed() throws Exception {
        StepResult result = executor.execute(json("{}"), json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("webhookUrl is required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example.com/webhook", "http://hooks.slack.com/services/x"})
    void anythingButHttpsSlackIsRejected(String webhookUrl) throws Exception {
        JsonNode config = mapper.createObjectNode().put("webhookUrl", webhookUrl);

        StepResult result = executor.execute(config, json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("must start with https://hooks.slack.com/");
    }
}
