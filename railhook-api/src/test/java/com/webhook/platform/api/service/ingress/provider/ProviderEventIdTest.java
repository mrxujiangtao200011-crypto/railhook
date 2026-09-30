package com.webhook.platform.api.service.ingress.provider;

import com.webhook.platform.api.service.ingress.ProviderEventIdExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

// Stripe and Twilio ids were read from headers neither sends, so every resend was forwarded again.
class ProviderEventIdTest {

    private static final String BASE_URL = "https://hooks.example.com";
    private static final String STRIPE_EVENT =
            "{\"id\":\"evt_1NxQ2bLkdIwHu7ix\",\"object\":\"event\",\"type\":\"invoice.paid\","
                    + "\"data\":{\"object\":{\"id\":\"in_1NxQ2a\"}}}";

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/ingress/token");
    }

    private static String extract(InboundProvider provider, MockHttpServletRequest request, String body) {
        return ProviderEventIdExtractor.extract(provider, request, body);
    }

    @Test
    void aStripeEventIsKeyedByTheEventIdInItsBody() {
        assertThat(extract(new StripeProvider(), request(), STRIPE_EVENT)).isEqualTo("evt_1NxQ2bLkdIwHu7ix");
    }

    @Test
    void aStripeBodyWhoseIdIsNotAnEventIdIsNotDeduplicated() {
        StripeProvider stripe = new StripeProvider();
        assertThat(extract(stripe, request(), "{\"id\":\"in_1NxQ2a\"}")).isNull();
        assertThat(extract(stripe, request(), "{\"id\":\"   \"}")).isNull();
        assertThat(extract(stripe, request(), "{\"id\":42}")).isNull();
        assertThat(extract(stripe, request(), "{\"data\":{\"id\":\"evt_nested\"}}")).isNull();
        assertThat(extract(stripe, request(), "not json")).isNull();
        assertThat(extract(stripe, request(), null)).isNull();
    }

    @Test
    void aGenericSourceIsKeyedOnlyByXWebhookId() {
        MockHttpServletRequest request = request();
        assertThat(extract(null, request, STRIPE_EVENT)).isNull();

        request.addHeader("X-Webhook-Id", " wh_123 ");
        assertThat(extract(null, request, STRIPE_EVENT)).isEqualTo("wh_123");
    }

    @Test
    void anIdLongerThanTheColumnIsTruncated() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Webhook-Id", "x".repeat(300));

        assertThat(extract(null, request, "{}")).hasSize(255);
    }

    @Test
    void headerKeyedProvidersReadTheHeaderTheyKeepAcrossRetries() {
        MockHttpServletRequest github = request();
        github.addHeader("X-GitHub-Delivery", "72d3162e-cc78-11e3-81ab-4c9367dc0958");
        assertThat(extract(new GitHubProvider(), github, "{}")).isEqualTo("72d3162e-cc78-11e3-81ab-4c9367dc0958");

        MockHttpServletRequest shopify = request();
        shopify.addHeader("X-Shopify-Webhook-Id", "b54557e4-bdd9-4b37-8a5f-bf7d70bcd043");
        assertThat(extract(new ShopifyProvider(), shopify, "{}")).isEqualTo("b54557e4-bdd9-4b37-8a5f-bf7d70bcd043");

        MockHttpServletRequest twilio = request();
        twilio.addHeader("I-Twilio-Idempotency-Token", "a1b2c3-idem");
        assertThat(extract(new TwilioProvider(BASE_URL), twilio, "AccountSid=AC1")).isEqualTo("a1b2c3-idem");
    }

    @Test
    void gitLabPrefersTheNewestDeliveryIdHeader() {
        MockHttpServletRequest request = request();
        request.addHeader("Idempotency-Key", "old");
        assertThat(extract(new GitLabProvider(), request, "{}")).isEqualTo("old");

        request.addHeader("webhook-id", "new");
        assertThat(extract(new GitLabProvider(), request, "{}")).isEqualTo("new");
    }

    @Test
    void gitLabIgnoresTheEventUuid() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Gitlab-Event-UUID", "shared");

        assertThat(extract(new GitLabProvider(), request, "{}")).isNull();
    }

    @Test
    void aSquareNotificationIsKeyedByTheEventIdInItsBody() {
        assertThat(extract(new SquareProvider(BASE_URL), request(),
                "{\"merchant_id\":\"MLEFBHHSJGVHD\",\"type\":\"payment.updated\","
                        + "\"event_id\":\"6a8f5f28-54a1-4eb0-a98a-3111513fd4fc\"}"))
                .isEqualTo("6a8f5f28-54a1-4eb0-a98a-3111513fd4fc");
    }

    @Test
    void aSlackEventIsKeyedByItsBodyEventId() {
        assertThat(extract(new SlackProvider(), request(), "{\"event_id\":\"Ev0PV52K25\"}")).isEqualTo("Ev0PV52K25");
    }

    // Keyed only for a single item: a key from the first of several would drop the others.
    @Test
    void anAdyenNotificationIsKeyedByItsPspReferenceAndEventCode() {
        assertThat(extract(new AdyenProvider(), request(), adyenBody(
                "{\"pspReference\":\"7914073381342284\",\"eventCode\":\"AUTHORISATION\",\"success\":\"true\"}")))
                .isEqualTo("7914073381342284:AUTHORISATION");
    }

    @Test
    void anAdyenNotificationCarryingSeveralItemsIsNotDeduplicated() {
        assertThat(extract(new AdyenProvider(), request(), adyenBody(
                "{\"pspReference\":\"one\",\"eventCode\":\"AUTHORISATION\"}",
                "{\"pspReference\":\"two\",\"eventCode\":\"CAPTURE\"}")))
                .isNull();
    }

    @Test
    void anAdyenNotificationMissingEitherHalfOfThePairIsNotDeduplicated() {
        AdyenProvider adyen = new AdyenProvider();
        assertThat(extract(adyen, request(), adyenBody("{\"eventCode\":\"AUTHORISATION\"}"))).isNull();
        assertThat(extract(adyen, request(), adyenBody("{\"pspReference\":\"7914073381342284\"}"))).isNull();
        assertThat(extract(adyen, request(), "not json")).isNull();
        assertThat(extract(adyen, request(), "{\"type\":\"balancePlatform.accountHolder.updated\"}")).isNull();
    }

    // Batches have no request id, and keying on the first event would drop the rest of a re-cut batch.
    @Test
    void batchingProvidersAreNotDeduplicated() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Webhook-Id", "ignored");

        assertThat(extract(new SendGridProvider(), request, "[{\"sg_event_id\":\"a\"}]")).isNull();
        assertThat(extract(new HubSpotProvider(BASE_URL), request, "[{\"eventId\":1}]")).isNull();
    }

    private static String adyenBody(String... items) {
        StringBuilder body = new StringBuilder("{\"live\":\"false\",\"notificationItems\":[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                body.append(",");
            }
            body.append("{\"NotificationRequestItem\":").append(items[i]).append("}");
        }
        return body.append("]}").toString();
    }
}
