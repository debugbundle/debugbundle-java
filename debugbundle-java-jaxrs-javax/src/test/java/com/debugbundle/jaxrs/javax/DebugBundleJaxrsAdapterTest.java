package com.debugbundle.jaxrs.javax;

import static org.assertj.core.api.Assertions.assertThat;

import com.debugbundle.sdk.DebugBundleClient;
import com.debugbundle.sdk.DebugBundleConfig;
import com.debugbundle.sdk.DebugBundleRequestScope;
import com.debugbundle.sdk.DebugBundleStatus;
import com.debugbundle.sdk.LogLevel;
import com.debugbundle.sdk.ProbeOptions;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.Path;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

class DebugBundleJaxrsAdapterTest {
    @Test
    void filterBeginsCapturesAndEndsRequestScope() throws Exception {
        RecordingClient client = new RecordingClient();
        DebugBundleJaxrsFilter filter = new DebugBundleJaxrsFilter(client);
        injectContext(filter, "resourceInfo", resourceInfo(LegacyPatientResource.class, "update"));
        TestRequestContext requestContext = new TestRequestContext("GET", "legacy/patients/42")
                .header("X-DebugBundle-Trace-Id", "trace-123")
                .header("X-Request-Id", "req-123")
                .query("expand", "allergies");
        ContainerResponseContext responseContext = responseContext(202);

        filter.filter(requestContext.proxy());
        filter.filter(requestContext.proxy(), responseContext);

        assertThat(client.begunRequests).hasSize(1);
        assertThat(client.endedScopes).containsExactly(client.begunScope);
        assertThat(client.capturedRequests).hasSize(1);
        assertThat(client.begunRequests.get(0))
                .containsEntry("method", "GET")
            .containsEntry("path", "/legacy/patients/42")
            .containsEntry("route_template", "/legacy/patients/{id}");
        assertThat(objectMap(client.begunRequests.get(0).get("query"))).containsEntry("expand", "allergies");
        assertThat(client.capturedContexts.get(0))
                .containsEntry("response_status", 202)
                .containsEntry("trace_id", "trace-123")
            .containsEntry("request_id", "req-123")
            .containsEntry("route_template", "/legacy/patients/{id}")
            .containsEntry("resource_class", LegacyPatientResource.class.getName())
            .containsEntry("resource_method", "update");
    }

    @Test
    void exceptionMapperCapturesThrowableAndPreservesWebApplicationStatus() throws Exception {
        RecordingClient client = new RecordingClient();
        DebugBundleExceptionMapper mapper = new DebugBundleExceptionMapper(client);
        injectContext(mapper, "request", request("PUT"));
        injectContext(mapper, "uriInfo", uriInfo("legacy/patients/42", new MultivaluedHashMap<>()));
        injectContext(mapper, "httpHeaders", headers(Map.of(
                "X-DebugBundle-Trace-Id", "trace-123",
                "X-Correlation-Id", "corr-123"
        )));
        injectContext(mapper, "resourceInfo", resourceInfo(LegacyPatientResource.class, "update"));

        Response response = mapper.toResponse(new WebApplicationException(Response.status(409).build()));

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(client.capturedExceptions).hasSize(1);
        assertThat(client.capturedContexts.get(0))
                .containsEntry("path", "/legacy/patients/42")
                .containsEntry("trace_id", "trace-123")
                .containsEntry("request_id", "corr-123")
            .containsEntry("response_status", 409)
            .containsEntry("route_template", "/legacy/patients/{id}")
            .containsEntry("resource_class", LegacyPatientResource.class.getName())
            .containsEntry("resource_method", "update");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static ContainerResponseContext responseContext(int status) {
        return (ContainerResponseContext) Proxy.newProxyInstance(
                ContainerResponseContext.class.getClassLoader(),
                new Class<?>[] {ContainerResponseContext.class},
                (instance, method, args) -> "getStatus".equals(method.getName()) ? status : defaultValue(method.getReturnType())
        );
    }

    private static Request request(String method) {
        return (Request) Proxy.newProxyInstance(
                Request.class.getClassLoader(),
                new Class<?>[] {Request.class},
                (instance, invokedMethod, args) -> "getMethod".equals(invokedMethod.getName()) ? method : defaultValue(invokedMethod.getReturnType())
        );
    }

    private static HttpHeaders headers(Map<String, String> values) {
        return (HttpHeaders) Proxy.newProxyInstance(
                HttpHeaders.class.getClassLoader(),
                new Class<?>[] {HttpHeaders.class},
                (instance, invokedMethod, args) -> "getHeaderString".equals(invokedMethod.getName())
                        ? values.get((String) args[0])
                        : defaultValue(invokedMethod.getReturnType())
        );
    }

    private static UriInfo uriInfo(String path, MultivaluedHashMap<String, String> query) {
        return (UriInfo) Proxy.newProxyInstance(
                UriInfo.class.getClassLoader(),
                new Class<?>[] {UriInfo.class},
                (instance, invokedMethod, args) -> switch (invokedMethod.getName()) {
                    case "getPath" -> path;
                    case "getQueryParameters" -> query;
                    default -> defaultValue(invokedMethod.getReturnType());
                }
        );
    }

    private static ResourceInfo resourceInfo(Class<?> resourceClass, String methodName) {
        return new ResourceInfo() {
            @Override
            public java.lang.reflect.Method getResourceMethod() {
                try {
                    return resourceClass.getDeclaredMethod(methodName);
                } catch (NoSuchMethodException error) {
                    throw new IllegalStateException(error);
                }
            }

            @Override
            public Class<?> getResourceClass() {
                return resourceClass;
            }
        };
    }

    private static void injectContext(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Path("/legacy/patients")
    private static final class LegacyPatientResource {
        @Path("/{id}")
        void update() {
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Byte.TYPE) {
            return (byte) 0;
        }
        if (returnType == Short.TYPE) {
            return (short) 0;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == Float.TYPE) {
            return 0.0f;
        }
        if (returnType == Double.TYPE) {
            return 0.0d;
        }
        if (returnType == Character.TYPE) {
            return '\0';
        }
        return null;
    }

    private static final class TestRequestContext {
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final MultivaluedHashMap<String, String> query = new MultivaluedHashMap<>();
        private final ContainerRequestContext proxy;

        private TestRequestContext(String method, String path) {
            UriInfo uriInfo = uriInfo(path, query);
            proxy = (ContainerRequestContext) Proxy.newProxyInstance(
                    ContainerRequestContext.class.getClassLoader(),
                    new Class<?>[] {ContainerRequestContext.class},
                    (instance, invokedMethod, args) -> switch (invokedMethod.getName()) {
                        case "getMethod" -> method;
                        case "getHeaderString" -> headers.get((String) args[0]);
                        case "getUriInfo" -> uriInfo;
                        case "setProperty" -> {
                            properties.put((String) args[0], args[1]);
                            yield null;
                        }
                        case "getProperty" -> properties.get((String) args[0]);
                        default -> defaultValue(invokedMethod.getReturnType());
                    }
            );
        }

        private TestRequestContext header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        private TestRequestContext query(String name, String value) {
            query.add(name, value);
            return this;
        }

        private ContainerRequestContext proxy() {
            return proxy;
        }
    }

    private static final class RecordingClient implements DebugBundleClient {
        private final List<Map<String, Object>> begunRequests = new ArrayList<>();
        private final List<DebugBundleRequestScope> endedScopes = new ArrayList<>();
        private final List<Object> capturedRequests = new ArrayList<>();
        private final List<Throwable> capturedExceptions = new ArrayList<>();
        private final List<Map<String, Object>> capturedContexts = new ArrayList<>();
        private DebugBundleRequestScope begunScope;

        @Override
        public DebugBundleConfig config() {
            return DebugBundleConfig.builder().enabled(false).build();
        }

        @Override
        public void captureException(Throwable error) {
            capturedExceptions.add(error);
        }

        @Override
        public void captureException(Throwable error, Map<String, Object> context) {
            capturedExceptions.add(error);
            capturedContexts.add(context);
        }

        @Override
        public void captureError(Throwable error) {
        }

        @Override
        public void captureLog(String message, LogLevel level) {
        }

        @Override
        public void captureLog(String message, LogLevel level, Map<String, Object> context) {
        }

        @Override
        public void captureRequest(Object request, Object response, Map<String, Object> context) {
            capturedRequests.add(request);
            capturedContexts.add(context);
        }

        @Override
        public void captureMessage(String message) {
        }

        @Override
        public void captureMessage(String message, LogLevel level, Map<String, Object> context) {
        }

        @Override
        public void setContext(String key, Object value) {
        }

        @Override
        public void probe(String label, Object data) {
        }

        @Override
        public void probe(String label, Supplier<?> dataSupplier) {
        }

        @Override
        public void probe(String label, Supplier<?> dataSupplier, ProbeOptions options) {
        }

        @Override
        public DebugBundleRequestScope beginRequest(Map<String, Object> request) {
            begunRequests.add(request);
            begunScope = new DebugBundleRequestScope();
            return begunScope;
        }

        @Override
        public void endRequest(DebugBundleRequestScope scope) {
            endedScopes.add(scope);
        }

        @Override
        public CompletableFuture<Void> flush() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public DebugBundleStatus status() {
            return DebugBundleStatus.HEALTHY;
        }

        @Override
        public Optional<Instant> lastEventAt() {
            return Optional.empty();
        }
    }
}