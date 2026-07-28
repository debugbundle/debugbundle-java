package com.debugbundle.servlet.javax;

import static org.assertj.core.api.Assertions.assertThat;

import com.debugbundle.sdk.DebugBundle;
import com.debugbundle.sdk.DebugBundleClient;
import com.debugbundle.sdk.DebugBundleConfig;
import com.debugbundle.sdk.web.DebugBundleWebDeployment;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import org.junit.jupiter.api.Test;

class DebugBundleServletContextListenerTest {
    @Test
    void listenerCreatesDeploymentScopedClientAndRemovesItOnShutdown() {
        ContextState state = new ContextState("/orders/admin", Map.of(
                "debugbundle.enabled", "false",
                "debugbundle.environment", "test"
        ));
        ServletContext context = state.proxy();
        DebugBundleServletContextListener listener = new DebugBundleServletContextListener();
        ServletContextEvent event = new ServletContextEvent(context);

        listener.contextInitialized(event);

        DebugBundleClient client = (DebugBundleClient) state.attributes.get(
                DebugBundleWebDeployment.CLIENT_ATTRIBUTE);
        assertThat(client).isNotNull();
        assertThat(client.config().service()).isEqualTo("orders-admin");
        assertThat(client.config().environment()).isEqualTo("test");
        assertThat(client.config().enabled()).isFalse();

        listener.contextDestroyed(event);
        assertThat(state.attributes).doesNotContainKey(DebugBundleWebDeployment.CLIENT_ATTRIBUTE);
    }

    @Test
    void listenerPreservesExistingClient() {
        ContextState state = new ContextState("/", Map.of());
        DebugBundleClient existing = DebugBundle.create(DebugBundleConfig.builder().enabled(false).build());
        state.attributes.put(DebugBundleWebDeployment.CLIENT_ATTRIBUTE, existing);

        new DebugBundleServletContextListener().contextInitialized(new ServletContextEvent(state.proxy()));

        assertThat(state.attributes.get(DebugBundleWebDeployment.CLIENT_ATTRIBUTE)).isSameAs(existing);
    }

    private static final class ContextState {
        private final String contextPath;
        private final Map<String, String> initParameters;
        private final Map<String, Object> attributes = new HashMap<>();

        private ContextState(String contextPath, Map<String, String> initParameters) {
            this.contextPath = contextPath;
            this.initParameters = initParameters;
        }

        private ServletContext proxy() {
            return (ServletContext) Proxy.newProxyInstance(
                    ServletContext.class.getClassLoader(),
                    new Class<?>[] {ServletContext.class},
                    (instance, method, args) -> switch (method.getName()) {
                        case "getContextPath" -> contextPath;
                        case "getInitParameter" -> initParameters.get((String) args[0]);
                        case "getAttribute" -> attributes.get((String) args[0]);
                        case "setAttribute" -> {
                            attributes.put((String) args[0], args[1]);
                            yield null;
                        }
                        case "removeAttribute" -> {
                            attributes.remove((String) args[0]);
                            yield null;
                        }
                        case "toString" -> "DebugBundleTestServletContext";
                        case "hashCode" -> System.identityHashCode(instance);
                        case "equals" -> instance == args[0];
                        default -> defaultValue(method.getReturnType());
                    }
            );
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == Boolean.TYPE) {
            return false;
        }
        if (type == Integer.TYPE) {
            return 0;
        }
        if (type == Long.TYPE) {
            return 0L;
        }
        return null;
    }
}
