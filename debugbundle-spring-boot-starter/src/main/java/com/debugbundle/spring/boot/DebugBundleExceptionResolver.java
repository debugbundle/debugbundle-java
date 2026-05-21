package com.debugbundle.spring.boot;

import com.debugbundle.sdk.DebugBundleClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

public class DebugBundleExceptionResolver implements HandlerExceptionResolver, Ordered {
    private final DebugBundleClient client;

    public DebugBundleExceptionResolver(DebugBundleClient client) {
        this.client = client;
    }

    @Override
    public ModelAndView resolveException(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
        Exception ex
    ) {
        if (request.getAttribute(DebugBundleRequestAttributes.EXCEPTION_CAPTURED) == null) {
            try {
                client.captureException(ex, buildContext(request, response));
            } catch (RuntimeException ignored) {
            }
            request.setAttribute(DebugBundleRequestAttributes.EXCEPTION_CAPTURED, Boolean.TRUE);
        }

        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private Map<String, Object> buildContext(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("method", request.getMethod());
        context.put("path", request.getRequestURI());
        context.put("response_status", response == null ? 500 : response.getStatus());
        context.put("trace_id", request.getHeader("X-DebugBundle-Trace-Id"));
        context.put("request_id", request.getHeader("X-Request-Id"));
        return context;
    }
}
