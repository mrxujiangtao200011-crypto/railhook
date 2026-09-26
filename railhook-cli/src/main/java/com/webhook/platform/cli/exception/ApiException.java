package com.webhook.platform.cli.exception;

import java.io.IOException;

public class ApiException extends IOException {

    private final int statusCode;

    public ApiException(int statusCode, String message) {
        super("HTTP " + statusCode + ": " + message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
