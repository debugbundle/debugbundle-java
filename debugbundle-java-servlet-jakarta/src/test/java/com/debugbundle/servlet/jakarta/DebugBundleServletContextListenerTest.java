package com.debugbundle.servlet.jakarta;

import static org.assertj.core.api.Assertions.assertThat;

import com.debugbundle.sdk.DebugBundle;
import com.debugbundle.sdk.DebugBundleClient;
import com.debugbundle.sdk.DebugBundleConfig;
import com.debugbundle.sdk.web.DebugBundleWebDeployment;
import jakarta.servlet.ServletContextEvent;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockServletContext;

class DebugBundleServletContextListenerTest {
    @Test
    void listenerCreatesDeploymentScopedClientAndClosesItOnShutdown() {
        MockServletContext context = new MockServletContext();
        context.setContextPath("/orders/admin");
        context.addInitParameter("debugbundle.enabled", "false");
        context.addInitParameter("debugbundle.environment", "test");
        DebugBundleServletContextListener listener = new DebugBundleServletContextListener();
        ServletContextEvent event = new ServletContextEvent(context);

        listener.contextInitialized(event);

        DebugBundleClient client = (DebugBundleClient) context.getAttribute(
                DebugBundleWebDeployment.CLIENT_ATTRIBUTE);
        assertThat(client).isNotNull();
        assertThat(client.config().service()).isEqualTo("orders-admin");
        assertThat(client.config().environment()).isEqualTo("test");
        assertThat(client.config().enabled()).isFalse();

        listener.contextDestroyed(event);
        assertThat(context.getAttribute(DebugBundleWebDeployment.CLIENT_ATTRIBUTE)).isNull();
    }

    @Test
    void listenerPreservesAnExistingDeploymentClient() {
        MockServletContext context = new MockServletContext();
        DebugBundleClient existing = DebugBundle.create(DebugBundleConfig.builder().enabled(false).build());
        context.setAttribute(DebugBundleWebDeployment.CLIENT_ATTRIBUTE, existing);

        new DebugBundleServletContextListener().contextInitialized(new ServletContextEvent(context));

        assertThat(context.getAttribute(DebugBundleWebDeployment.CLIENT_ATTRIBUTE)).isSameAs(existing);
    }
}
