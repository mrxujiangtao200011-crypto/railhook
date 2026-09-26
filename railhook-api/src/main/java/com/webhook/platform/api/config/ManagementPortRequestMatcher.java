package com.webhook.platform.api.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Boot copies the main security filters into the management port's child context, so the chain
 * needs this to tell the two ports apart. It reads the resolved ports because a configured
 * {@code port=0} says nothing about what is listening. When the ports are not split, nothing
 * matches and actuator stays behind authentication.
 */
@RequiredArgsConstructor
public class ManagementPortRequestMatcher implements RequestMatcher {

    private final Environment environment;

    @Override
    public boolean matches(HttpServletRequest request) {
        Integer managementPort = environment.getProperty("local.management.port", Integer.class);
        Integer serverPort = environment.getProperty("local.server.port", Integer.class);

        if (managementPort == null || serverPort == null || managementPort.equals(serverPort)) {
            return false;
        }
        return request.getLocalPort() == managementPort;
    }
}
