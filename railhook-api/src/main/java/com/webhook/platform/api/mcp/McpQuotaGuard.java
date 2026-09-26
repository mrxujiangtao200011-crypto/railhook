package com.webhook.platform.api.mcp;

import com.webhook.platform.api.dto.EndpointRequest;
import com.webhook.platform.api.dto.EndpointResponse;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.service.EndpointService;
import com.webhook.platform.api.service.billing.QuotaType;
import com.webhook.platform.api.service.billing.RequireQuota;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * A separate bean because the quota aspect only applies through a Spring proxy, and MCP tool
 * methods are invoked reflectively. The aspect reads the {@code auth} and {@code projectId}
 * parameters by name, so do not rename them.
 */
@Component
@RequiredArgsConstructor
public class McpQuotaGuard {

    private final EndpointService endpointService;

    @RequireQuota(QuotaType.ENDPOINTS_PER_PROJECT)
    public EndpointResponse createEndpoint(AuthContext auth, UUID projectId, EndpointRequest request) {
        auth.validateProjectAccess(projectId);
        return endpointService.createEndpoint(projectId, request);
    }
}
