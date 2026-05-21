package com.debugbundle.spring.boot;

import com.debugbundle.sdk.DebugBundleClient;
import com.debugbundle.sdk.DebugBundleRequestScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

public class DebugBundleServletFilter extends OncePerRequestFilter {
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
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("method", request.getMethod());
        context.put("path", request.getRequestURI());
        context.put("route_template", request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        context.put("response_status", response.getStatus());
        context.put("duration_ms", Duration.between(startedAt, Instant.now()).toMillis());
        context.put("trace_id", request.getHeader("X-DebugBundle-Trace-Id"));
        context.put("request_id", firstNonBlank(
                request.getHeader("X-Request-Id"),
                request.getHeader("X-Correlation-Id")
        ));
        return context;
    }

    private Map<String, Object> requestSnapshot(HttpServletRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("method", request.getMethod());
        snapshot.put("path", request.getRequestURI());

        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("x-request-id", request.getHeader("X-Request-Id"));
        headers.put("x-correlation-id", request.getHeader("X-Correlation-Id"));
        headers.put("x-debugbundle-trace-id", request.getHeader("X-DebugBundle-Trace-Id"));
        snapshot.put("headers", headers);
        snapshot.put("query", extractQueryMap(request));
        return snapshot;
    }

    private Map<String, Object> beginRequestSnapshot(HttpServletRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("method", request.getMethod());
        snapshot.put("path", request.getRequestURI());
        snapshot.put("route_template", request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        snapshot.put("headers", extractBeginHeaders(request));
        snapshot.put("query", extractQueryMap(request));
        return snapshot;
    }

    private Map<String, Object> responseSnapshot(HttpServletResponse response, Instant startedAt) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status_code", response.getStatus());
        snapshot.put("duration_ms", Duration.between(startedAt, Instant.now()).toMillis());
        return snapshot;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private Map<String, Object> extractBeginHeaders(HttpServletRequest request) {
        Map<String, Object> headers = new LinkedHashMap<>();
        copyHeaderIfPresent(headers, request, "X-DebugBundle-Probe-Trigger");
        copyHeaderIfPresent(headers, request, "X-DebugBundle-Trace-Id");
        copyHeaderIfPresent(headers, request, "X-Request-Id");
        copyHeaderIfPresent(headers, request, "X-Correlation-Id");
        return headers;
    }

    private void copyHeaderIfPresent(Map<String, Object> target, HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value != null && !value.isBlank()) {
            target.put(name, value);
        }
    }

    private Map<String, Object> extractQueryMap(HttpServletRequest request) {
        Map<String, Object> query = new LinkedHashMap<>();
        Enumeration<String> names = request.getParameterNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String[] values = request.getParameterValues(name);
            if (values == null || values.length == 0) {
                continue;
            }
            if (values.length == 1) {
                query.put(name, values[0]);
                continue;
            }
            List<String> listValues = new ArrayList<>();
            for (String value : values) {
                if (value != null) {
                    listValues.add(value);
                }
            }
            query.put(name, listValues);
        }
        return query;
    }
}
