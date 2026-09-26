package com.webhook.platform.api.exception;

public class OrganizationSuspendedException extends RuntimeException {
    public OrganizationSuspendedException(String message) { super(message); }
}
