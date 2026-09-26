package com.webhook.platform.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.webhook.platform.api.exception.ErrorCode;
import com.webhook.platform.api.exception.ErrorResponseWriter;
import com.webhook.platform.api.exception.RequestBodyTooLargeException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxPayloadSizeBytes;
    private final long ingressMaxPayloadSizeBytes;

    public RequestSizeLimitFilter(
            @Value("${webhook.max-payload-size-bytes:262144}") long maxPayloadSizeBytes,
            @Value("${webhook.incoming.max-payload-size-bytes:524288}") long ingressMaxPayloadSizeBytes) {
        this.maxPayloadSizeBytes = maxPayloadSizeBytes;
        this.ingressMaxPayloadSizeBytes = ingressMaxPayloadSizeBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long effectiveLimit = resolveLimit(request);
        long contentLength = request.getContentLengthLong();
        if (contentLength > effectiveLimit) {
            log.warn("Request rejected: Content-Length {} exceeds max payload size {} bytes (URI: {})",
                    contentLength, effectiveLimit, request.getRequestURI());
            rejectRequest(response, effectiveLimit);
            return;
        }

        // Chunked bodies carry no Content-Length, so the stream is counted too.
        HttpServletRequest wrappedRequest = new ContentLimitedRequestWrapper(request, effectiveLimit);

        try {
            filterChain.doFilter(wrappedRequest, response);
        } catch (RequestBodyTooLargeException e) {
            log.warn("Request rejected mid-stream: body exceeds max payload size {} bytes (URI: {})",
                    effectiveLimit, request.getRequestURI());
            if (!response.isCommitted()) {
                rejectRequest(response, effectiveLimit);
            }
        } catch (ServletException e) {
            if (hasPayloadTooLargeCause(e)) {
                log.warn("Request rejected mid-stream: body exceeds max payload size {} bytes (URI: {})",
                        effectiveLimit, request.getRequestURI());
                if (!response.isCommitted()) {
                    rejectRequest(response, effectiveLimit);
                }
            } else {
                throw e;
            }
        }
    }

    private long resolveLimit(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/ingress/")) {
            return ingressMaxPayloadSizeBytes;
        }
        return maxPayloadSizeBytes;
    }

    private void rejectRequest(HttpServletResponse response, long limit) throws IOException {
        ErrorResponseWriter.write(response, ErrorCode.PAYLOAD_TOO_LARGE,
                "Request body exceeds maximum allowed size of " + limit + " bytes");
    }

    private boolean hasPayloadTooLargeCause(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof RequestBodyTooLargeException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static class ContentLimitedRequestWrapper extends HttpServletRequestWrapper {

        private final long maxBytes;

        ContentLimitedRequestWrapper(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedServletInputStream(super.getInputStream(), maxBytes);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    getCharacterEncoding() != null ? getCharacterEncoding() : "UTF-8"));
        }
    }

    private static class LimitedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long bytesRead = 0;

        LimitedServletInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                bytesRead++;
                checkLimit();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int count = delegate.read(b, off, len);
            if (count > 0) {
                bytesRead += count;
                checkLimit();
            }
            return count;
        }

        private void checkLimit() throws RequestBodyTooLargeException {
            if (bytesRead > maxBytes) {
                throw new RequestBodyTooLargeException(maxBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
