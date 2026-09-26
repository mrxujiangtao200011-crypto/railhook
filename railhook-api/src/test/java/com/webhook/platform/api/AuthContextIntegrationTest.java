package com.webhook.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class AuthContextIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String jwtToken;
    private String apiKey;
    private UUID projectId;
    private UUID organizationId;

    @BeforeEach
    void setup() throws Exception {
        String email = "authctx-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email(email)
                .password("Test1234!")
                .organizationName("AuthCtx Test Org")
                .build();

        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode authJson = objectMapper.readTree(registerResult.getResponse().getContentAsString());
        jwtToken = authJson.get("accessToken").asText();

        MvcResult meResult = mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode meJson = objectMapper.readTree(meResult.getResponse().getContentAsString());
        organizationId = UUID.fromString(meJson.get("organization").get("id").asText());

        ProjectRequest projectRequest = ProjectRequest.builder()
                .name("AuthCtx Test Project")
                .description("Test project")
                .build();

        MvcResult projectResult = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(projectRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode projectJson = objectMapper.readTree(projectResult.getResponse().getContentAsString());
        projectId = UUID.fromString(projectJson.get("id").asText());

        ApiKeyRequest apiKeyRequest = ApiKeyRequest.builder()
                .name("test-sdk-key")
                .build();

        MvcResult apiKeyResult = mockMvc.perform(post("/api/v1/projects/" + projectId + "/api-keys")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(apiKeyRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode apiKeyJson = objectMapper.readTree(apiKeyResult.getResponse().getContentAsString());
        apiKey = apiKeyJson.get("key").asText();
    }

    @Test
    void jwtReachesProjectAndAccountRoutes() throws Exception {
        expectGetStatus(200, "Authorization", "Bearer " + jwtToken,
                "/api/v1/projects",
                "/api/v1/projects/" + projectId + "/endpoints",
                "/api/v1/projects/" + projectId + "/subscriptions",
                "/api/v1/projects/" + projectId + "/events",
                "/api/v1/projects/" + projectId + "/api-keys",
                "/api/v1/projects/" + projectId + "/dlq",
                "/api/v1/projects/" + projectId + "/incoming-sources",
                "/api/v1/dashboard/projects/" + projectId,
                "/api/v1/orgs",
                "/api/v1/orgs/" + organizationId + "/members",
                "/api/v1/billing/organization",
                "/api/v1/billing/usage",
                "/api/v1/audit-log");
    }

    @Test
    void jwtCurrentUserCarriesUserAndOrganization() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").exists())
                .andExpect(jsonPath("$.organization").exists());
    }

    @Test
    void apiKeyReachesItsProjectRoutes() throws Exception {
        expectGetStatus(200, "X-API-Key", apiKey,
                "/api/v1/projects/" + projectId + "/endpoints",
                "/api/v1/projects/" + projectId + "/subscriptions",
                "/api/v1/projects/" + projectId + "/events",
                "/api/v1/projects/" + projectId + "/api-keys",
                "/api/v1/projects/" + projectId + "/dlq",
                "/api/v1/projects/" + projectId + "/incoming-sources",
                "/api/v1/dashboard/projects/" + projectId);
    }

    @Test
    void apiKeyCannotReachAccountRoutes() throws Exception {
        expectGetStatus(403, "X-API-Key", apiKey,
                "/api/v1/auth/me",
                "/api/v1/orgs",
                "/api/v1/orgs/" + organizationId + "/members",
                "/api/v1/billing/organization",
                "/api/v1/billing/usage",
                "/api/v1/billing/invoices",
                "/api/v1/audit-log",
                "/api/v1/audit-log/export");
    }

    // A leaked READ_WRITE key could otherwise mint keys and outlive its own revocation.
    @Test
    void readWriteApiKeyCannotManageApiKeys() throws Exception {
        String key = readWriteApiKey();
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/api-keys")
                        .header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ApiKeyRequest.builder().name("minted-by-key").build())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/api-keys/" + createApiKeyWithJwt("rotate-target") + "/rotate")
                        .header("X-API-Key", key))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/projects/" + projectId + "/api-keys/" + createApiKeyWithJwt("revoke-target"))
                        .header("X-API-Key", key))
                .andExpect(status().isForbidden());
    }

    @Test
    void jwtRotatesAndRevokesApiKeys() throws Exception {
        UUID target = createApiKeyWithJwt("jwt-managed");
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/api-keys/" + target + "/rotate")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isCreated());
        mockMvc.perform(delete("/api/v1/projects/" + projectId + "/api-keys/" + target)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void apiKeyIsForbiddenOnAnotherProject() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + UUID.randomUUID() + "/endpoints")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isForbidden());
    }

    @Test
    void jwtIsForbiddenOnAnotherOrganizationsMembers() throws Exception {
        mockMvc.perform(get("/api/v1/orgs/" + UUID.randomUUID() + "/members")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void noCredentialsIsUnauthorized() throws Exception {
        expectGetStatus(401, null, null,
                "/api/v1/projects",
                "/api/v1/projects/" + UUID.randomUUID() + "/endpoints",
                "/api/v1/auth/me",
                "/api/v1/orgs");
    }

    @Test
    void billingWebhooksNeedNoCredentials() throws Exception {
        for (String[] webhook : new String[][]{
                {"stripe", "{\"type\":\"invoice.paid\"}"},
                {"wayforpay", "{\"transactionStatus\":\"Approved\"}"}}) {
            int status = mockMvc.perform(post("/api/v1/billing/webhook/" + webhook[0])
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(webhook[1]))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as("POST /api/v1/billing/webhook/%s", webhook[0]).isNotEqualTo(401);
        }
    }

    private void expectGetStatus(int expected, String header, String value, String... paths) throws Exception {
        for (String path : paths) {
            MockHttpServletRequestBuilder request = get(path);
            if (header != null) {
                request.header(header, value);
            }
            int actual = mockMvc.perform(request).andReturn().getResponse().getStatus();
            assertThat(actual).as("GET %s", path).isEqualTo(expected);
        }
    }

    private String readWriteApiKey() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/api-keys")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ApiKeyRequest.builder()
                                .name("read-write-key").scope(ApiKeyScope.READ_WRITE).build())))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("key").asText();
    }

    private UUID createApiKeyWithJwt(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/api-keys")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ApiKeyRequest.builder().name(name).build())))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
}
