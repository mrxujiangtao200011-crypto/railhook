package com.webhook.platform.api.controller;

import com.webhook.platform.api.exception.DomainException;
import com.webhook.platform.api.exception.ErrorCode;
import com.webhook.platform.api.security.AllowedInDemo;
import com.webhook.platform.api.dto.PublicBinCreateRequest;
import com.webhook.platform.api.dto.PublicBinResponse;
import com.webhook.platform.api.security.ProjectScopeExempt;
import com.webhook.platform.api.security.TrustedProxyResolver;
import com.webhook.platform.api.service.AuthRateLimiterService;
import com.webhook.platform.api.service.PublicBinService;
import com.webhook.platform.api.service.captcha.CaptchaVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/** The public webhook tester, no account needed. Captured requests land on PublicBinCaptureController. */
@RestController
@RequestMapping("/api/v1/public/bins")
@Tag(name = "Webhook Tester", description = "Public webhook tester URLs that need no account")
@ProjectScopeExempt(reason = "public and anonymous; a tester URL belongs to no project")
@RequiredArgsConstructor
public class PublicBinController {

    private final PublicBinService publicBinService;
    private final AuthRateLimiterService authRateLimiterService;
    private final TrustedProxyResolver trustedProxyResolver;
    private final CaptchaVerifier captchaVerifier;

    @Operation(operationId = "createPublicBin", summary = "Make a tester URL",
            description = "A URL that records every request sent to it for a day: the latest 100 within 1 MB of "
                    + "bodies, the first 64 KB of each, with credentials masked. Anyone who has the URL can read what it "
                    + "received. At most three live URLs per address.")
    @ApiResponse(responseCode = "201", description = "The URL")
    @ApiResponse(responseCode = "400", description = "The challenge was not passed")
    @ApiResponse(responseCode = "429", description = "Too many URLs made from this address, or it already holds three live ones")
    @ApiResponse(responseCode = "503", description = "The tester holds as many live URLs as it allows; try later")
    @AllowedInDemo(reason = "anonymous by design; a tester URL belongs to no organization")
    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) PublicBinCreateRequest body,
                                    HttpServletRequest request) {
        String ip = trustedProxyResolver.resolve(request);
        if (!authRateLimiterService.allowPublicBin(ip)) {
            throw new DomainException(ErrorCode.RATE_LIMIT_EXCEEDED, "Too many tester URLs. Try again in a minute.");
        }
        // Per-address limits alone do not stop a script with many addresses.
        if (!captchaVerifier.verify(body == null ? null : body.getCaptchaToken(), ip)) {
            throw new DomainException(ErrorCode.CAPTCHA_FAILED, "Challenge verification failed. Please try again.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(publicBinService.create(ip));
    }

    @Operation(operationId = "getPublicBin", summary = "Read a tester URL",
            description = "The URL and the requests it received, newest first.")
    @ApiResponse(responseCode = "200", description = "The URL and its requests")
    @ApiResponse(responseCode = "404", description = "No such URL, or it has expired")
    @GetMapping("/{slug}")
    public ResponseEntity<PublicBinResponse> read(@PathVariable("slug") String slug) {
        return ResponseEntity.ok(publicBinService.read(slug));
    }
}
