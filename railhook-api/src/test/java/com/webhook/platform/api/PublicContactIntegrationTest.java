package com.webhook.platform.api;

import com.webhook.platform.api.service.ContactMessageBudget;
import com.webhook.platform.api.service.EmailService;
import com.webhook.platform.api.service.captcha.CaptchaVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class PublicContactIntegrationTest extends AbstractIntegrationTest {

    private static final String VALID = """
            {"email":"ada@example.com","name":"Ada","topic":"sales",
             "message":"We send about two million events a month. Can we talk?",
             "page":"/pricing","captchaToken":"token"}
            """;

    @MockitoBean
    private CaptchaVerifier captchaVerifier;

    @MockitoBean
    private EmailService emailService;

    @MockitoBean
    private ContactMessageBudget contactMessageBudget;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void open() {
        when(captchaVerifier.verify(any(), anyString())).thenReturn(true);
        when(authRateLimiterService.allowContactMessage(anyString())).thenReturn(true);
        when(emailService.isContactAvailable()).thenReturn(true);
        when(contactMessageBudget.tryAcquire()).thenReturn(true);
    }

    private static MockHttpServletRequestBuilder send(String body) {
        return post("/api/v1/public/contact")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(request -> {
                    request.setRemoteAddr("198.51.100.7");
                    return request;
                });
    }

    @Test
    public void aMessageGoesToSupportWithTheVisitorAsReplyTo() throws Exception {
        mockMvc.perform(send(VALID)).andExpect(status().isAccepted());

        verify(emailService).sendContactMessage("ada@example.com", "Ada", "sales",
                "We send about two million events a month. Can we talk?", "/pricing");
    }

    static Stream<Arguments> invalidFields() {
        String message = "We send about two million events a month. Can we talk?";
        return Stream.of(
                Arguments.of("ada@example.com", "not-an-address"),
                Arguments.of(message, " "),
                Arguments.of("\"sales\"", "\"<script>\""),
                Arguments.of(message, "x".repeat(5001)));
    }

    @ParameterizedTest
    @MethodSource("invalidFields")
    public void anInvalidFieldIsRefusedAndNothingIsSent(String valid, String invalid) throws Exception {
        mockMvc.perform(send(VALID.replace(valid, invalid)))
                .andExpect(status().isBadRequest());

        verify(emailService, never()).sendContactMessage(any(), any(), any(), any(), any());
    }

    @Test
    public void aFailedChallengeSendsNothingAndSpendsNoneOfTheDailyCeiling() throws Exception {
        when(captchaVerifier.verify(any(), anyString())).thenReturn(false);

        mockMvc.perform(send(VALID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("captcha_failed"));

        verify(emailService, never()).sendContactMessage(any(), any(), any(), any(), any());
        verify(contactMessageBudget, never()).tryAcquire();
    }

    @Test
    public void messagesAreLimitedPerAddress() throws Exception {
        when(authRateLimiterService.allowContactMessage("198.51.100.7")).thenReturn(false);

        mockMvc.perform(send(VALID))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("rate_limit_exceeded"));

        verify(emailService, never()).sendContactMessage(any(), any(), any(), any(), any());
    }

    @Test
    public void pastTheDailyCeilingNothingIsSent() throws Exception {
        when(contactMessageBudget.tryAcquire()).thenReturn(false);

        mockMvc.perform(send(VALID))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("contact_busy"));

        verify(emailService, never()).sendContactMessage(any(), any(), any(), any(), any());
    }

    @Test
    public void aDeploymentWithNoSupportAddressSaysSo() throws Exception {
        when(emailService.isContactAvailable()).thenReturn(false);

        mockMvc.perform(send(VALID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("contact_unavailable"));
    }
}
