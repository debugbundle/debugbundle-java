package com.debugbundle.jaxrs.javax;

import com.debugbundle.sdk.DebugBundle;
import com.debugbundle.sdk.DebugBundleClient;
import com.debugbundle.sdk.DebugBundleRequestScope;
import com.debugbundle.sdk.web.DebugBundleWebCapture;
import com.debugbundle.sdk.web.DebugBundleWebDeployment;
import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.servlet.ServletContext;
import javax.ws.rs.Path;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.Provider;

@Provider
public class DebugBundleJaxrsFilter implements ContainerRequestFilter, ContainerResponseFilter {
    static final String CLIENT_PROPERTY = DebugBundleJaxrsFilter.class.getName() + ".client";
    static final String REQUEST_SCOPE_PROPERTY = DebugBundleJaxrsFilter.class.getName() + ".requestScope";
    static final String STARTED_AT_PROPERTY = DebugBundleJaxrsFilter.class.getName() + ".startedAt";
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

        @Context
        private ServletContext servletContext;

        @Context
        private ResourceInfo resourceInfo;

    private final Supplier<DebugBundleClient> clientSupplier;

    public DebugBundleJaxrsFilter() {
        this(DebugBundle::client);
    }

    public DebugBundleJaxrsFilter(DebugBundleClient client) {
        this(() -> client);
    }

    public DebugBundleJaxrsFilter(Supplier<DebugBundleClient> clientSupplier) {
        this.clientSupplier = clientSupplier == null ? DebugBundle::client : clientSupplier;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        requestContext.setProperty(STARTED_AT_PROPERTY, Instant.now());
        requestContext.setProperty(CLIENT_PROPERTY, resolveClient());
        requestContext.setProperty(REQUEST_SCOPE_PROPERTY, safeBeginRequest(requestContext));
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
        safeCaptureRequest(requestContext, responseContext, startedAt(requestContext));
        safeEndRequest(requestContext.getProperty(REQUEST_SCOPE_PROPERTY));
    }

    private DebugBundleRequestScope safeBeginRequest(ContainerRequestContext requestContext) {
        try {
            DebugBundleClient client = client(requestContext);
            if (client == null) {
                return DebugBundleRequestScope.noop();
            }
            return client.beginRequest(beginRequestSnapshot(requestContext));
        } catch (RuntimeException error) {
            return DebugBundleRequestScope.noop();
        }
    }

    private void safeCaptureRequest(
            ContainerRequestContext requestContext,
            ContainerResponseContext responseContext,
            Instant startedAt
    ) {
        try {
            DebugBundleClient client = client(requestContext);
            if (client != null) {
                client.captureRequest(
                        requestSnapshot(requestContext),
                        responseSnapshot(responseContext, startedAt),
                        buildContext(requestContext, responseContext, startedAt)
                );
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void safeEndRequest(Object requestScope) {
        try {
            DebugBundleClient client = resolveClient();
            if (client != null && requestScope instanceof DebugBundleRequestScope scope) {
                client.endRequest(scope);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private DebugBundleClient client(ContainerRequestContext requestContext) {
        DebugBundleClient requestClient = DebugBundleWebDeployment.clientFromAttribute(requestContext.getProperty(CLIENT_PROPERTY));
        return requestClient == null ? resolveClient() : requestClient;
    }

    private DebugBundleClient resolveClient() {
        DebugBundleClient deploymentClient = servletContext == null
                ? null
                : DebugBundleWebDeployment.clientFromAttribute(servletContext.getAttribute(DebugBundleWebDeployment.CLIENT_ATTRIBUTE));
        return deploymentClient == null ? clientSupplier.get() : deploymentClient;
    }

    private Map<String, Object> beginRequestSnapshot(ContainerRequestContext requestContext) {
        return DebugBundleWebCapture.beginRequestSnapshot(
                requestContext.getMethod(),
                path(requestContext),
                routeTemplate(),
                DebugBundleWebCapture.selectedHeaders(requestContext::getHeaderString, BEGIN_REQUEST_HEADER_NAMES, false),
                queryMap(requestContext.getUriInfo().getQueryParameters())
        );
    }

    private Map<String, Object> requestSnapshot(ContainerRequestContext requestContext) {
        return DebugBundleWebCapture.requestSnapshot(
                requestContext.getMethod(),
                path(requestContext),
                DebugBundleWebCapture.selectedHeaders(requestContext::getHeaderString, CAPTURED_REQUEST_HEADER_NAMES, true),
                queryMap(requestContext.getUriInfo().getQueryParameters())
        );
    }

    private Map<String, Object> responseSnapshot(ContainerResponseContext responseContext, Instant startedAt) {
        return DebugBundleWebCapture.responseSnapshot(responseContext.getStatus(), durationMillis(startedAt));
    }

    private Map<String, Object> buildContext(
            ContainerRequestContext requestContext,
            ContainerResponseContext responseContext,
            Instant startedAt
    ) {
        Map<String, Object> context = DebugBundleWebCapture.context(
                requestContext.getMethod(),
                path(requestContext),
                routeTemplate(),
                responseContext.getStatus(),
                durationMillis(startedAt),
                requestContext.getHeaderString("X-DebugBundle-Trace-Id"),
                DebugBundleWebCapture.firstNonBlank(
                        requestContext.getHeaderString("X-Request-Id"),
                        requestContext.getHeaderString("X-Correlation-Id")
                )
        );
        addResourceMetadata(context);
        return context;
    }

    private String routeTemplate() {
        if (resourceInfo == null) {
            return null;
        }
        return routeTemplate(resourceInfo.getResourceClass(), resourceInfo.getResourceMethod());
    }

    private String routeTemplate(Class<?> resourceClass, Method resourceMethod) {
        String classPath = pathValue(resourceClass == null ? null : resourceClass.getAnnotation(Path.class));
        String methodPath = pathValue(resourceMethod == null ? null : resourceMethod.getAnnotation(Path.class));
        StringBuilder route = new StringBuilder();
        appendPath(route, classPath);
        appendPath(route, methodPath);
        if (route.isEmpty()) {
            return null;
        }
        return "/" + route;
    }

    private String pathValue(Path path) {
        return path == null ? null : path.value();
    }

    private void appendPath(StringBuilder route, String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        String normalizedPath = path.trim().replaceAll("^/+", "").replaceAll("/+$", "");
        if (normalizedPath.isBlank()) {
            return;
        }
        if (!route.isEmpty()) {
            route.append('/');
        }
        route.append(normalizedPath);
    }

    private void addResourceMetadata(Map<String, Object> context) {
        if (resourceInfo == null) {
            return;
        }
        Class<?> resourceClass = resourceInfo.getResourceClass();
        Method resourceMethod = resourceInfo.getResourceMethod();
        if (resourceClass != null) {
            context.put("resource_class", resourceClass.getName());
        }
        if (resourceMethod != null) {
            context.put("resource_method", resourceMethod.getName());
        }
    }

    private String path(ContainerRequestContext requestContext) {
        return "/" + requestContext.getUriInfo().getPath();
    }

    private Map<String, Object> queryMap(MultivaluedMap<String, String> queryParameters) {
        Map<String, Object> query = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : queryParameters.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            if (entry.getValue().size() == 1) {
                query.put(entry.getKey(), entry.getValue().get(0));
                continue;
            }
            query.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return query;
    }

    private Instant startedAt(ContainerRequestContext requestContext) {
        Object startedAt = requestContext.getProperty(STARTED_AT_PROPERTY);
        return startedAt instanceof Instant instant ? instant : Instant.now();
    }

    private long durationMillis(Instant startedAt) {
        return DebugBundleWebCapture.durationMillis(startedAt, Instant.now());
    }
}