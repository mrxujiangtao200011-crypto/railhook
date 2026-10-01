package com.webhook.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.entity.Incident;
import com.webhook.platform.api.domain.repository.AlertRuleRepository;
import com.webhook.platform.api.dto.ProjectRequest;
import com.webhook.platform.api.dto.RegisterRequest;
import com.webhook.platform.api.service.AlertNotificationService;
import com.webhook.platform.api.service.AlertService;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AlertIncidentIntegrationTest extends AbstractIntegrationTest {

    private static final String ROUTING_KEY = "R0UTING-KEY-5b1d8e2f";

    @MockitoBean
    private AlertNotificationService notificationService;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AlertService alertService;
    @Autowired private AlertRuleRepository ruleRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String token;
    private UUID organizationId;
    private UUID projectId;

    @BeforeEach
    void setup() throws Exception {
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(RegisterRequest.builder()
                                .email("alert-incidents-" + UUID.randomUUID() + "@test.com")
                                .password("Test1234!")
                                .organizationName("Alert Incidents Org")
                                .build())))
                .andExpect(status().isCreated())
                .andReturn();
        token = json(registered).get("accessToken").asText();

        MvcResult project = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ProjectRequest.builder().name("Alert Incidents Project").build())))
                .andExpect(status().isCreated())
                .andReturn();
        projectId = UUID.fromString(json(project).get("id").asText());
        organizationId = jdbcTemplate.queryForObject(
                "SELECT organization_id FROM projects WHERE id = ?", UUID.class, projectId);
    }

    @Test
    void aSecondFiringWhileTheIncidentIsOpenAddsToItInsteadOfOpeningAnother() throws Exception {
        AlertRule rule = pagerDutyRule();

        fire(rule, "Failure rate 80.0%");
        fire(rule, "Failure rate 95.0%");

        JsonNode incidents = incidents();
        assertThat(incidents).hasSize(1);
        JsonNode incident = incidents.get(0);
        assertThat(incident.get("alertRuleId").asText()).isEqualTo(rule.getId().toString());
        assertThat(incident.get("alertRuleName").asText()).isEqualTo("Payments failing");
        assertThat(incident.get("status").asText()).isEqualTo("OPEN");

        JsonNode timeline = incidentDetail(incident.get("id").asText()).get("timeline");
        assertThat(timeline).hasSize(2);
        assertThat(timeline.toString()).contains("Failure rate 95.0%").doesNotContainIgnoringCase("looking into");
    }

    @Test
    void clearingTheConditionResolvesTheIncident_andClosesThePageTheFiringOpened() throws Exception {
        AlertRule rule = pagerDutyRule();
        fire(rule, "Failure rate 80.0%");

        TenantContext.runAs(organizationId, () -> alertService.resolveRecovered(rule));

        JsonNode incident = incidents().get(0);
        assertThat(incident.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(incident.get("autoResolved").asBoolean()).isTrue();
        assertThat(incidentDetail(incident.get("id").asText()).get("timeline")).hasSize(2);

        ArgumentCaptor<Incident> fired = ArgumentCaptor.forClass(Incident.class);
        ArgumentCaptor<Incident> resolved = ArgumentCaptor.forClass(Incident.class);
        verify(notificationService).dispatch(eq(rule), any(), fired.capture());
        verify(notificationService).dispatchResolved(eq(rule), resolved.capture());
        assertThat(resolved.getValue().getId()).isEqualTo(fired.getValue().getId());

        fire(rule, "Failure rate 70.0%");
        assertThat(incidents()).as("the next breach is a new incident").hasSize(2);
    }

    @Test
    void theRoutingKeyIsStoredEncryptedAndNeverReturned() throws Exception {
        MvcResult created = createRule("{\"name\":\"Payments failing\",\"alertType\":\"FAILURE_RATE\","
                + "\"severity\":\"CRITICAL\",\"thresholdValue\":10,\"channel\":\"PAGERDUTY\","
                + "\"channelConfig\":{\"routingKey\":\"" + ROUTING_KEY + "\"}}");
        assertThat(created.getResponse().getContentAsString()).doesNotContain(ROUTING_KEY);
        assertThat(json(created).get("configuredSecrets").toString()).isEqualTo("[\"routingKey\"]");

        String listed = mockMvc.perform(get("/api/v1/projects/" + projectId + "/alerts/rules")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain(ROUTING_KEY);

        String stored = jdbcTemplate.queryForObject(
                "SELECT channel_config_encrypted::text FROM alert_rules WHERE id = ?", String.class,
                UUID.fromString(json(created).get("id").asText()));
        assertThat(stored).isNotBlank().doesNotContain(ROUTING_KEY);
    }

    @Test
    void anOnCallRuleWithoutAKeyIsRefused_ratherThanPagingNobody() throws Exception {
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/alerts/rules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"r\",\"alertType\":\"FAILURE_RATE\",\"thresholdValue\":10,"
                                + "\"channel\":\"OPSGENIE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anInfoRuleFiringOpensNoIncident() throws Exception {
        MvcResult created = createRule("{\"name\":\"Traffic note\",\"alertType\":\"FAILURE_RATE\","
                + "\"severity\":\"INFO\",\"thresholdValue\":10}");
        UUID ruleId = UUID.fromString(json(created).get("id").asText());
        AlertRule rule = TenantContext.callAs(organizationId, () -> ruleRepository.findById(ruleId).orElseThrow());

        fire(rule, "Failure rate 12.0%");

        assertThat(incidents()).isEmpty();
    }

    @Test
    void anInfoRuleCannotPage() throws Exception {
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/alerts/rules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"r\",\"alertType\":\"FAILURE_RATE\",\"severity\":\"INFO\","
                                + "\"thresholdValue\":10,\"channel\":\"PAGERDUTY\",\"channelConfig\":{\"routingKey\":\""
                                + ROUTING_KEY + "\"}}"))
                .andExpect(status().isBadRequest());
    }

    private AlertRule pagerDutyRule() throws Exception {
        MvcResult created = createRule("{\"name\":\"Payments failing\",\"alertType\":\"FAILURE_RATE\","
                + "\"severity\":\"WARNING\",\"thresholdValue\":10,\"channel\":\"PAGERDUTY\","
                + "\"channelConfig\":{\"routingKey\":\"" + ROUTING_KEY + "\"}}");
        UUID ruleId = UUID.fromString(json(created).get("id").asText());
        return TenantContext.callAs(organizationId, () -> ruleRepository.findById(ruleId).orElseThrow());
    }

    private MvcResult createRule(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/projects/" + projectId + "/alerts/rules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private void fire(AlertRule rule, String message) {
        TenantContext.runAs(organizationId, () -> alertService.fireAlert(rule, 80.0, message));
    }

    private JsonNode incidents() throws Exception {
        return json(mockMvc.perform(get("/api/v1/projects/" + projectId + "/incidents")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()).get("content");
    }

    private JsonNode incidentDetail(String incidentId) throws Exception {
        return json(mockMvc.perform(get("/api/v1/projects/" + projectId + "/incidents/" + incidentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
