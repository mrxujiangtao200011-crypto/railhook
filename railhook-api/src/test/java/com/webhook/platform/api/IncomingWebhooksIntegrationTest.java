package com.webhook.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.dto.*;
import com.webhook.platform.common.enums.IncomingAuthType;
import com.webhook.platform.common.enums.IncomingSourceStatus;
import com.webhook.platform.common.enums.ProviderType;
import com.webhook.platform.common.enums.VerificationMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.webhook.platform.api.domain.entity.IncomingEvent;
import com.webhook.platform.api.domain.repository.IncomingEventRepository;
import com.webhook.platform.api.service.verification.ReplayDetectionService;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
public class IncomingWebhooksIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IncomingEventRepository incomingEventRepository;

    // Redis-backed and there is no Redis here; unstubbed it answers "not seen before".
    @MockitoBean
    private ReplayDetectionService replayDetectionService;

    private String accessToken;
    private UUID projectId;

    private record Source(UUID id, String ingressPathToken) {
    }

    @BeforeEach
    void registerAndCreateProject() throws Exception {
        // Every Source has a fail-closed rate limit, and the limiter is a mock here.
        when(redisRateLimiterService.tryAcquireForSourceFailClosed(any(UUID.class), anyInt())).thenReturn(true);

        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("incoming-test-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com")
                .password("Test1234!")
                .organizationName("Incoming Test Org")
                .build();
        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();
        accessToken = objectMapper.readValue(registerResult.getResponse().getContentAsString(), AuthResponse.class)
                .getAccessToken();

        MvcResult projectResult = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ProjectRequest.builder().name("Incoming Test Project").build())))
                .andExpect(status().isCreated())
                .andReturn();
        projectId = UUID.fromString(objectMapper.readTree(projectResult.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void sourceLifecycle() throws Exception {
        IncomingSourceRequest request = IncomingSourceRequest.builder()
                .name("GitHub Webhooks")
                .slug("github-webhooks")
                .providerType(ProviderType.GITHUB)
                .verificationMode(VerificationMode.NONE)
                .build();
        MvcResult created = mockMvc.perform(post(sources())
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("GitHub Webhooks"))
                .andExpect(jsonPath("$.slug").value("github-webhooks"))
                .andExpect(jsonPath("$.providerType").value("GITHUB"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.verificationMode").value("NONE"))
                .andExpect(jsonPath("$.ingressPathToken").isNotEmpty())
                .andExpect(jsonPath("$.ingressUrl").isNotEmpty())
                .andExpect(jsonPath("$.hmacSecretConfigured").value(false))
                .andReturn();
        JsonNode json = objectMapper.readTree(created.getResponse().getContentAsString());
        String source = sources() + "/" + json.get("id").asText();

        mockMvc.perform(get(source).header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("GitHub Webhooks"));
        mockMvc.perform(get(sources()).header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));

        IncomingSourceRequest update = IncomingSourceRequest.builder()
                .name("GitHub Webhooks Updated")
                .providerType(ProviderType.GITHUB)
                .status(IncomingSourceStatus.ACTIVE)
                .verificationMode(VerificationMode.HMAC_GENERIC)
                .hmacSecret("test-hmac-secret")
                .hmacHeaderName("X-Hub-Signature-256")
                .hmacSignaturePrefix("sha256=")
                .build();
        mockMvc.perform(put(source)
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("GitHub Webhooks Updated"))
                .andExpect(jsonPath("$.verificationMode").value("HMAC_GENERIC"))
                .andExpect(jsonPath("$.hmacSecretConfigured").value(true))
                .andExpect(jsonPath("$.hmacHeaderName").value("X-Hub-Signature-256"))
                .andExpect(jsonPath("$.hmacSignaturePrefix").value("sha256="));

        mockMvc.perform(delete(source).header("Authorization", auth()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(source).header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mockMvc.perform(post("/ingress/" + json.get("ingressPathToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"test\":true}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("disabled"));
    }

    @Test
    void sourceRequestsThatAreRefused() throws Exception {
        createSource("taken", VerificationMode.NONE, null);

        mockMvc.perform(post(sources())
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(IncomingSourceRequest.builder().name("Another").slug("taken").build())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(sources())
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"no-name\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(sources() + "/" + UUID.randomUUID()).header("Authorization", auth()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(sources())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(IncomingSourceRequest.builder().name("No Auth").build())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void destinationLifecycle() throws Exception {
        Source source = createSource("destinations", VerificationMode.NONE, null);
        String destinations = sources() + "/" + source.id() + "/destinations";

        IncomingDestinationRequest request = IncomingDestinationRequest.builder()
                .url("https://example.com/webhook-receiver")
                .authType(IncomingAuthType.BEARER)
                .authConfig("{\"token\":\"secret-bearer-token\"}")
                .maxAttempts(3)
                .timeoutSeconds(15)
                .retryDelays("30,60,300")
                .build();
        MvcResult created = mockMvc.perform(post(destinations)
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value("https://example.com/webhook-receiver"))
                .andExpect(jsonPath("$.authType").value("BEARER"))
                .andExpect(jsonPath("$.authConfigured").value(true))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.maxAttempts").value(3))
                .andExpect(jsonPath("$.timeoutSeconds").value(15))
                .andExpect(jsonPath("$.retryDelays").value("30,60,300"))
                .andReturn();
        String destination = destinations + "/" + objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get(destination).header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example.com/webhook-receiver"));
        mockMvc.perform(get(destinations).header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));

        IncomingDestinationRequest update = IncomingDestinationRequest.builder()
                .url("https://example.com/updated-hook")
                .authType(IncomingAuthType.NONE)
                .enabled(false)
                .maxAttempts(10)
                .timeoutSeconds(60)
                .retryDelays("60,300,900,3600")
                .build();
        mockMvc.perform(put(destination)
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example.com/updated-hook"))
                .andExpect(jsonPath("$.authType").value("NONE"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.maxAttempts").value(10));

        mockMvc.perform(delete(destination).header("Authorization", auth()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(destination).header("Authorization", auth()))
                .andExpect(status().isNotFound());
    }

    @Test
    void ingressAcceptsWebhooksAndRecordsThem() throws Exception {
        Source source = createSource("ingress", VerificationMode.NONE, null);
        mockMvc.perform(post(sources() + "/" + source.id() + "/destinations")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(IncomingDestinationRequest.builder()
                                .url("https://example.com/hook").build())))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/ingress/" + source.ingressPathToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"push\",\"ref\":\"refs/heads/main\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        mockMvc.perform(post("/ingress/" + source.ingressPathToken())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));

        String events = "/api/v1/projects/" + projectId + "/incoming-events";
        mockMvc.perform(get(events).param("sourceId", source.id().toString()).header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].requestId").isNotEmpty())
                .andExpect(jsonPath("$.content[0].method").value("POST"));

        MvcResult list = mockMvc.perform(get(events).header("Authorization", auth()))
                .andExpect(status().isOk())
                .andReturn();
        String eventId = objectMapper.readTree(list.getResponse().getContentAsString()).get("content").get(0).get("id").asText();
        mockMvc.perform(get(events + "/" + eventId).header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(eventId))
                .andExpect(jsonPath("$.method").value("POST"))
                .andExpect(jsonPath("$.incomingSourceId").value(source.id().toString()));
        mockMvc.perform(get(events + "/" + eventId + "/attempts").header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void ingressAnswersNotFoundForAnUnknownToken() throws Exception {
        mockMvc.perform(post("/ingress/nonexistent-token-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"test\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void ingressRefusesAWrongSignature() throws Exception {
        Source source = createSource("signed", VerificationMode.HMAC_GENERIC, "test-hmac-secret");

        mockMvc.perform(post("/ingress/" + source.ingressPathToken())
                        .header("X-Signature", "invalid-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"push\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("signature_verification_failed"));
    }

    // Spring rebuilds a form body from parsed params; the signature covers the sender's own bytes.
    @Test
    void formEncodedWebhookIsVerifiedStoredAndForwardedAsTheBytesThatWereSigned() throws Exception {
        String secret = "form-hmac-secret";
        Source source = createSource("form", VerificationMode.HMAC_GENERIC, secret);

        // Encoded the way URLEncoder does not: %20 for a space, lowercase hex.
        String raw = "command=%2fdeploy&text=hello%20world&payload=%7b%22a%22%3a1%7d&user_name=o%27brien";
        byte[] rawBytes = raw.getBytes(StandardCharsets.US_ASCII);
        String signature = HexFormat.of().formatHex(hmacSha256(secret, rawBytes));

        MvcResult accepted = mockMvc.perform(post("/ingress/" + source.ingressPathToken())
                        .header("X-Signature", signature)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(rawBytes))
                .andExpect(status().isAccepted())
                .andReturn();

        String requestId = objectMapper.readTree(accepted.getResponse().getContentAsString()).get("requestId").asText();
        IncomingEvent stored = incomingEventRepository
                .findByIncomingSourceId(source.id(), PageRequest.of(0, 100)).getContent().stream()
                .filter(e -> requestId.equals(e.getRequestId()))
                .findFirst()
                .orElseThrow();

        // Forwarding sends body_raw, so this is also what the Destination receives.
        assertThat(stored.getBodyRaw()).isEqualTo(raw);
        assertThat(stored.getBodySha256())
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBytes)));
        assertThat(stored.getVerified()).isTrue();
    }

    private String auth() {
        return "Bearer " + accessToken;
    }

    private String sources() {
        return "/api/v1/projects/" + projectId + "/incoming-sources";
    }

    private Source createSource(String slug, VerificationMode mode, String hmacSecret) throws Exception {
        IncomingSourceRequest.IncomingSourceRequestBuilder request = IncomingSourceRequest.builder()
                .name(slug)
                .slug(slug)
                .verificationMode(mode);
        if (hmacSecret != null) {
            request.hmacSecret(hmacSecret).hmacHeaderName("X-Signature").hmacSignaturePrefix("");
        }
        MvcResult result = mockMvc.perform(post(sources())
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request.build())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Source(UUID.fromString(json.get("id").asText()), json.get("ingressPathToken").asText());
    }

    private static byte[] hmacSha256(String secret, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data);
    }
}
