package com.webhook.platform.api.exception;

/** Its own error code so the dashboard can say "this is the demo" rather than "you lack a role". */
public class DemoReadOnlyException extends ForbiddenException {

    public DemoReadOnlyException() {
        super(ErrorCode.DEMO_READ_ONLY, "This is a read-only demo. Create a free account to make changes.");
    }
}
