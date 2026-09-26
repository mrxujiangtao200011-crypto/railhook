package com.webhook.platform.api.exception;

public class ForbiddenException extends DomainException {

    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }

    public ForbiddenException(String message, Throwable cause) {
        super(ErrorCode.FORBIDDEN, message, cause);
    }

    protected ForbiddenException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
