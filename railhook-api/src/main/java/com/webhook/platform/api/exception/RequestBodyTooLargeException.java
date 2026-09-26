package com.webhook.platform.api.exception;

import java.io.IOException;

public class RequestBodyTooLargeException extends IOException {

    public RequestBodyTooLargeException(long limit) {
        super("Request body exceeds maximum allowed size of " + limit + " bytes");
    }
}
