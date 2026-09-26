package com.webhook.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
public class TestEndpointIsolationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String orgAJwt;
    private String orgBJwt;
    private UUID projectAId;
    private UUID testEndpointAId;
    private String testEndpointASlug;

    private UUID projectA2Id;
    private String apiKeyForProjectA;

    @BeforeEach
    void setup() throws Exception {
        // The capture endpoint's Redis limiter is mocked; stub it open or the capture answers 429.
        when(redisRateLimiterService.tryAcquireForSlug(anyString(), anyInt())).thenReturn(true);

        String suffix = UUID.randomUUID().toString().substring(0, 8);

        orgAJwt = registerAndGetJwt("orgA-" + suffix + "@test.com", "Org A " + suffix);
        orgBJwt = registerAndGetJwt("orgB-" + suffix + "@test.com", "Org B " + suffix);

        projectAId = createProject(orgAJwt, "Project A");
        projectA2Id = createProject(orgAJwt, "Project A2");

        MvcResult createResult = mockMvc.perform(post("/api/v1/projects/" + projectAId + "/test-endpoints")
                        .header("Authorization", "Bearer " + orgAJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        testEndpointAId = UUID.fromString(created.get("id").asText());
        testEndpointASlug = created.get("slug").asText();

        mockMvc.perform(post("/hook/" + testEndpointASlug)
                        .header("Authorization", "Bearer super-secret-provider-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hello\":\"world\"}"))
                .andExpect(status().isOk());

        MvcResult apiKeyResult = mockMvc.perform(post("/api/v1/projects/" + projectAId + "/api-keys")
                        .header("Authorization", "Bearer " + orgAJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ApiKeyRequest.builder()
                                .name("test-endpoint-key")
                                .build())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode apiKeyJson = objectMapper.readTree(apiKeyResult.getResponse().getContentAsString());
        apiKeyForProjectA = apiKeyJson.get("key").asText();
    }

    private String registerAndGetJwt(String email, String orgName) throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email(email)
                .password("Test1234!")
                .organizationName(orgName)
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("accessToken").asText();
    }

    private UUID createProject(String jwt, String name) throws Exception {
        ProjectRequest request = ProjectRequest.builder()
                .name(name)
                .description("Test project")
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(json.get("id").asText());
    }

    // The body was once read twice and stored empty.
    @Test
    public void capturedRequest_keepsTheBodyAsSent() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/projects/" + projectAId + "/test-endpoints/" + testEndpointAId + "/requests")
                        .header("Authorization", "Bearer " + orgAJwt))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode captured = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode first = captured.isArray() ? captured.get(0) : captured.path("content").get(0);
        assertThat(first.get("body").asText()).isEqualTo("{\"hello\":\"world\"}");
    }

    // Not found rather than forbidden: 403 would confirm the id exists.
    @Test
    void anotherOrganizationGetsNotFoundOnEveryRoute() throws Exception {
        String base = "/api/v1/projects/" + projectAId + "/test-endpoints";
        String one = base + "/" + testEndpointAId;
        List<MockHttpServletRequestBuilder> requests = List.of(
                post(base).contentType(MediaType.APPLICATION_JSON).content("{}"),
                get(base),
                get(one),
                delete(one),
                get(one + "/requests"),
                delete(one + "/requests"));
        for (MockHttpServletRequestBuilder request : requests) {
            MockHttpServletResponse response = mockMvc.perform(request.header("Authorization", "Bearer " + orgBJwt))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).as(request.toString()).isEqualTo(404);
        }
    }

    @Test
    void apiKeyIsForbiddenOnAnotherProjectOfTheSameOrganization() throws Exception {
        String otherProject = "/api/v1/projects/" + projectA2Id + "/test-endpoints";
        mockMvc.perform(get(otherProject).header("X-API-Key", apiKeyForProjectA))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(otherProject)
                        .header("X-API-Key", apiKeyForProjectA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerManagesTheTestEndpoint() throws Exception {
        String base = "/api/v1/projects/" + projectAId + "/test-endpoints";
        String one = base + "/" + testEndpointAId;
        String auth = "Bearer " + orgAJwt;

        mockMvc.perform(get(base).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get(one).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(testEndpointAId.toString()));
        mockMvc.perform(get(one + "/requests").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
        mockMvc.perform(delete(one + "/requests").header("Authorization", auth))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(base).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete(one).header("Authorization", auth))
                .andExpect(status().isNoContent());
    }
}
