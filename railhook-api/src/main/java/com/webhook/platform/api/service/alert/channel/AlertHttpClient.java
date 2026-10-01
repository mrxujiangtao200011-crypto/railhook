package com.webhook.platform.api.service.alert.channel;

import com.webhook.platform.common.http.SsrfProtectionCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;

@Component
public class AlertHttpClient {

    private final WebClient webClient;

    public AlertHttpClient(
            WebClient.Builder webClientBuilder,
            @Value("${webhook.url-validation.allow-private-ips:false}") boolean allowPrivateIps,
            @Value("${webhook.url-validation.allowed-hosts:}") List<String> allowedHosts) {
        // A user aims this client, and a name validated at write time can resolve elsewhere later.
        this.webClient = webClientBuilder
                .clientConnector(new ReactorClientHttpConnector(
                        SsrfProtectionCustomizer.apply(HttpClient.create(), allowPrivateIps, allowedHosts)))
                .defaultHeader("User-Agent", "Railhook-Alerts/1.0")
                .build();
    }

    public void post(String url, Object body) {
        post(url, null, body);
    }

    public void post(String url, String authorization, Object body) {
        webClient.post()
                .uri(url)
                .headers(headers -> {
                    if (authorization != null) {
                        headers.set("Authorization", authorization);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(10))
                .block();
    }
}
