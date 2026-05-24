package com.debugbundle.spring.boot;

import com.debugbundle.sdk.DebugBundleClient;
import com.debugbundle.sdk.DebugBundleRequestScope;
import com.debugbundle.sdk.web.DebugBundleWebCapture;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

public class DebugBundleServletFilter extends OncePerRequestFilter {
    private static final List<String> BEGIN_REQUEST_HEADER_NAMES = List.of(
        "X-DebugBundle-Probe-Trigger",
        "X-DebugBundle-Trace-Id",
        "X-Request-Id",
        "X-Correlation-Id"
    );
    private static final List<String> CAPTURED_REQUEST_HEADER_NAMES = List.of(
        "x-request-id",
        "x-correlation-id",
        "x-debugbundle-trace-id"
    );

    private final DebugBundleClient client;

    public DebugBundleServletFilter(DebugBundleClient client) {
        this.client = client;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Instant startedAt = Instant.now();
        DebugBundleRequestScope requestScope = safeBeginRequest(request);

        try {
            filterChain.doFilter(request, response);
        } catch (RuntimeException | ServletException | IOException error) {
            if (request.getAttribute(DebugBundleRequestAttributes.EXCEPTION_CAPTURED) == null) {
                safeCaptureException(error, buildContext(request, response, startedAt));
                request.setAttribute(DebugBundleRequestAttributes.EXCEPTION_CAPTURED, Boolean.TRUE);
            }
            throw error;
        } finally {
            safeCaptureRequest(request, response, startedAt);
            safeEndRequest(requestScope);
        }
    }

    private DebugBundleRequestScope safeBeginRequest(HttpServletRequest request) {
        try {
            return client.beginRequest(beginRequestSnapshot(request));
        } catch (RuntimeException error) {
            return DebugBundleRequestScope.noop();
        }
    }

    private void safeCaptureException(Throwable error, Map<String, Object> context) {
        try {
            client.captureException(error, context);
        } catch (RuntimeException ignored) {
        }
    }

    private void safeCaptureRequest(HttpServletRequest request, HttpServletResponse response, Instant startedAt) {
        try {
            client.captureRequest(requestSnapshot(request), responseSnapshot(response, startedAt), buildContext(request, response, startedAt));
        } catch (RuntimeException ignored) {
        }
    }

    private void safeEndRequest(DebugBundleRequestScope requestScope) {
        try {
            client.endRequest(requestScope);
        } catch (RuntimeException ignored) {
        }
    }

    private Map<String, Object> buildContext(
            HttpServletRequest request,
            HttpServletResponse response,
            Instant startedAt
    ) {
        return DebugBundleWebCapture.context(
                request.getMethod(),
                request.getRequestURI(),
                request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE),
                response.getStatus(),
                durationMillis(startedAt),
                request.getHeader("X-DebugBundle-Trace-Id"),
                DebugBundleWebCapture.firstNonBlank(
                request.getHeader("X-Request-Id"),
                request.getHeader("X-Correlation-Id")
        ));
    }

    private Map<String, Object> requestSnapshot(HttpServletRequest request) {
        return DebugBundleWebCapture.requestSnapshot(
                request.getMethod(),
                request.getRequestURI(),
                DebugBundleWebCapture.selectedHeaders(request::getHeader, CAPTURED_REQUEST_HEADER_NAMES, true),
                extractQueryMap(request)
        );
    }

    private Map<String, Object> beginRequestSnapshot(HttpServletRequest request) {
        return DebugBundleWebCapture.beginRequestSnapshot(
                request.getMethod(),
                request.getRequestURI(),
                request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE),
                DebugBundleWebCapture.selectedHeaders(request::getHeader, BEGIN_REQUEST_HEADER_NAMES, false),
                extractQueryMap(request)
        );
    }

    private Map<String, Object> responseSnapshot(HttpServletResponse response, Instant startedAt) {
        return DebugBundleWebCapture.responseSnapshot(response.getStatus(), durationMillis(startedAt));
    }

    private Map<String, Object> extractQueryMap(HttpServletRequest request) {
        Enumeration<String> names = request.getParameterNames();
        List<String> parameterNames = new java.util.ArrayList<>();
        while (names.hasMoreElements()) {
            parameterNames.add(names.nextElement());
        }
        return DebugBundleWebCapture.queryParameters(parameterNames, request::getParameterValues);
    }

    private long durationMillis(Instant startedAt) {
        return DebugBundleWebCapture.durationMillis(startedAt, Instant.now());
    }
}
