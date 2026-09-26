package com.webhook.platform.api.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Spring AI's WebMVC transport answers every handler error with HTTP 500, including an unknown
 * method that clients probe routinely. Only an {@link McpError} carrying a JSON-RPC error is
 * converted; anything else is a real failure and stays a 500.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class JsonRpcErrorAnsweringTransport implements McpStatelessServerTransport {

    private final McpStatelessServerTransport delegate;

    @Override
    public void setMcpHandler(McpStatelessServerHandler handler) {
        delegate.setMcpHandler(new McpStatelessServerHandler() {
            @Override
            public Mono<McpSchema.JSONRPCResponse> handleRequest(
                    McpTransportContext context, McpSchema.JSONRPCRequest request) {
                return handler.handleRequest(context, request)
                        .onErrorResume(McpError.class, error -> error.getJsonRpcError() == null
                                ? Mono.error(error)
                                : Mono.just(new McpSchema.JSONRPCResponse(
                                        McpSchema.JSONRPC_VERSION, request.id(), null, error.getJsonRpcError())));
            }

            @Override
            public Mono<Void> handleNotification(
                    McpTransportContext context, McpSchema.JSONRPCNotification notification) {
                return handler.handleNotification(context, notification);
            }
        });
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }
}
