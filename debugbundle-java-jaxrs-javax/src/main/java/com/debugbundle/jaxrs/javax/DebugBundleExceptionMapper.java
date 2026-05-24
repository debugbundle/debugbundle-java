package com.debugbundle.jaxrs.javax;

import com.debugbundle.sdk.DebugBundle;
import com.debugbundle.sdk.DebugBundleClient;
import com.debugbundle.sdk.web.DebugBundleWebCapture;
import com.debugbundle.sdk.web.DebugBundleWebDeployment;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Supplier;
import javax.servlet.ServletContext;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.Path;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class DebugBundleExceptionMapper implements ExceptionMapper<Throwable> {
    private final Supplier<DebugBundleClient> clientSupplier;

    @Context
    private Request request;

    @Context
    private UriInfo uriInfo;

    @Context
    private HttpHeaders httpHeaders;

    @Context
    private ServletContext servletContext;

    @Context
    private ResourceInfo resourceInfo;

    public DebugBundleExceptionMapper() {
        this(DebugBundle::client);
    }

    public DebugBundleExceptionMapper(DebugBundleClient client) {
        this(() -> client);
    }

    public DebugBundleExceptionMapper(Supplier<DebugBundleClient> clientSupplier) {
        this.clientSupplier = clientSupplier == null ? DebugBundle::client : clientSupplier;
    }

    @Override
    public Response toResponse(Throwable throwable) {
        safeCaptureException(throwable, buildContext(statusCode(throwable)));
        if (throwable instanceof WebApplicationException webApplicationException) {
            return webApplicationException.getResponse();
        }
        return Response.serverError().build();
    }

    private void safeCaptureException(Throwable throwable, Map<String, Object> context) {
        try {
            DebugBundleClient client = resolveClient();
            if (client != null) {
                client.captureException(throwable, context);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private Map<String, Object> buildContext(int responseStatus) {
        Map<String, Object> context = DebugBundleWebCapture.context(
                request == null ? null : request.getMethod(),
                uriInfo == null ? null : "/" + uriInfo.getPath(),
                routeTemplate(),
                responseStatus,
                0L,
                httpHeaders == null ? null : httpHeaders.getHeaderString("X-DebugBundle-Trace-Id"),
                DebugBundleWebCapture.firstNonBlank(
                        httpHeaders == null ? null : httpHeaders.getHeaderString("X-Request-Id"),
                        httpHeaders == null ? null : httpHeaders.getHeaderString("X-Correlation-Id")
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

    private int statusCode(Throwable throwable) {
        if (throwable instanceof WebApplicationException webApplicationException) {
            return webApplicationException.getResponse().getStatus();
        }
        return 500;
    }

    private DebugBundleClient resolveClient() {
        DebugBundleClient deploymentClient = servletContext == null
                ? null
                : DebugBundleWebDeployment.clientFromAttribute(servletContext.getAttribute(DebugBundleWebDeployment.CLIENT_ATTRIBUTE));
        return deploymentClient == null ? clientSupplier.get() : deploymentClient;
    }
}