package com.debugbundle.servlet.javax;

import com.debugbundle.sdk.DebugBundle;
import com.debugbundle.sdk.DebugBundleClient;
import com.debugbundle.sdk.DebugBundleRequestScope;
import com.debugbundle.sdk.web.DebugBundleWebCapture;
import com.debugbundle.sdk.web.DebugBundleWebDeployment;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class DebugBundleServletFilter implements Filter {
    private static final String REQUEST_CLIENT_ATTRIBUTE = DebugBundleServletFilter.class.getName() + ".client";
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

    private final Supplier<DebugBundleClient> clientSupplier;

    public DebugBundleServletFilter() {
        this(DebugBundle::client);
    }

    public DebugBundleServletFilter(DebugBundleClient client) {
        this(() -> client);
    }

    public DebugBundleServletFilter(Supplier<DebugBundleClient> clientSupplier) {
        this.clientSupplier = clientSupplier == null ? DebugBundle::client : clientSupplier;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        if (!(servletRequest instanceof HttpServletRequest request)
                || !(servletResponse instanceof HttpServletResponse response)) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        Instant startedAt = Instant.now();
        DebugBundleRequestScope requestScope = safeBeginRequest(request);
        try {
            filterChain.doFilter(servletRequest, servletResponse);
        } catch (RuntimeException | ServletException | IOException error) {
            if (request.getAttribute(DebugBundleServletRequestAttributes.EXCEPTION_CAPTURED) == null) {
                safeCaptureException(request, error, buildContext(request, response, startedAt));
                request.setAttribute(DebugBundleServletRequestAttributes.EXCEPTION_CAPTURED, Boolean.TRUE);
            }
            throw error;
        } finally {
            safeCaptureRequest(request, response, startedAt);
            safeEndRequest(request, requestScope);
        }
    }

    private DebugBundleRequestScope safeBeginRequest(HttpServletRequest request) {
        try {
            DebugBundleClient client = client(request);
            if (client == null) {
                return DebugBundleRequestScope.noop();
            }
            return client.beginRequest(beginRequestSnapshot(request));
        } catch (RuntimeException error) {
            return DebugBundleRequestScope.noop();
        }
    }

    private void safeCaptureException(HttpServletRequest request, Throwable error, Map<String, Object> context) {
        try {
            DebugBundleClient client = client(request);
            if (client != null) {
                client.captureException(error, context);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void safeCaptureRequest(HttpServletRequest request, HttpServletResponse response, Instant startedAt) {
        try {
            DebugBundleClient client = client(request);
            if (client != null) {
                client.captureRequest(requestSnapshot(request), responseSnapshot(response, startedAt), buildContext(request, response, startedAt));
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void safeEndRequest(HttpServletRequest request, DebugBundleRequestScope requestScope) {
        try {
            DebugBundleClient client = client(request);
            if (client != null) {
                client.endRequest(requestScope);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private DebugBundleClient client(HttpServletRequest request) {
        DebugBundleClient requestClient = DebugBundleWebDeployment.clientFromAttribute(request.getAttribute(REQUEST_CLIENT_ATTRIBUTE));
        if (requestClient != null) {
            return requestClient;
        }

        DebugBundleClient deploymentClient = request.getServletContext() == null
            ? null
            : DebugBundleWebDeployment.clientFromAttribute(
                request.getServletContext().getAttribute(DebugBundleWebDeployment.CLIENT_ATTRIBUTE)
            );
        if (deploymentClient != null) {
            request.setAttribute(REQUEST_CLIENT_ATTRIBUTE, deploymentClient);
            return deploymentClient;
        }

        DebugBundleClient fallbackClient = clientSupplier.get();
        if (fallbackClient != null) {
            request.setAttribute(REQUEST_CLIENT_ATTRIBUTE, fallbackClient);
        }
        return fallbackClient;
    }

    private Map<String, Object> buildContext(
            HttpServletRequest request,
            HttpServletResponse response,
            Instant startedAt
    ) {
        return DebugBundleWebCapture.context(
                request.getMethod(),
                request.getRequestURI(),
                null,
                response.getStatus(),
                durationMillis(startedAt),
                request.getHeader("X-DebugBundle-Trace-Id"),
                DebugBundleWebCapture.firstNonBlank(
                        request.getHeader("X-Request-Id"),
                        request.getHeader("X-Correlation-Id")
                )
        );
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
                null,
                DebugBundleWebCapture.selectedHeaders(request::getHeader, BEGIN_REQUEST_HEADER_NAMES, false),
                extractQueryMap(request)
        );
    }

    private Map<String, Object> responseSnapshot(HttpServletResponse response, Instant startedAt) {
        return DebugBundleWebCapture.responseSnapshot(response.getStatus(), durationMillis(startedAt));
    }

    private Map<String, Object> extractQueryMap(HttpServletRequest request) {
        Enumeration<String> names = request.getParameterNames();
        List<String> parameterNames = new ArrayList<>();
        while (names.hasMoreElements()) {
            parameterNames.add(names.nextElement());
        }
        return DebugBundleWebCapture.queryParameters(parameterNames, request::getParameterValues);
    }

    private long durationMillis(Instant startedAt) {
        return DebugBundleWebCapture.durationMillis(startedAt, Instant.now());
    }
}