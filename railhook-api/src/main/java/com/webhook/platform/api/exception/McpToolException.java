package com.webhook.platform.api.exception;

/** Its message is shown to the model as-is. */
public class McpToolException extends RuntimeException {

    public McpToolException(String message) {
        super(message);
    }
}
