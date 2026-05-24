package com.debugbundle.servlet.javax;

import com.debugbundle.sdk.DebugBundle;
import com.debugbundle.sdk.DebugBundleClient;
import com.debugbundle.sdk.DebugBundleConfig;
import com.debugbundle.sdk.DebugBundleConfigLoader;
import com.debugbundle.sdk.web.DebugBundleWebDeployment;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

public class DebugBundleServletContextListener implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent) {
        ServletContext servletContext = servletContextEvent.getServletContext();
        if (DebugBundleWebDeployment.clientFromAttribute(servletContext.getAttribute(DebugBundleWebDeployment.CLIENT_ATTRIBUTE)) != null) {
            return;
        }

        String deploymentKey = DebugBundleWebDeployment.deploymentKeyFromContextPath(servletContext.getContextPath());
        String configPath = firstNonBlank(
            servletContext.getInitParameter("debugbundle.config"),
            System.getProperty("debugbundle.config"),
            System.getenv("DEBUGBUNDLE_CONFIG")
        );

        DebugBundleConfig config = DebugBundleConfigLoader.load(
                servletContext::getInitParameter,
            DebugBundleConfigLoader.deploymentLookup(
                DebugBundleConfigLoader.propertiesFileLookup(configPath),
                deploymentKey
            ),
                DebugBundleWebDeployment.serviceNameFromContextPath(servletContext.getContextPath())
        );
        DebugBundleClient client = DebugBundle.create(config);
        servletContext.setAttribute(DebugBundleWebDeployment.CLIENT_ATTRIBUTE, client);
    }

    @Override
    public void contextDestroyed(ServletContextEvent servletContextEvent) {
        ServletContext servletContext = servletContextEvent.getServletContext();
        DebugBundleClient client = DebugBundleWebDeployment.clientFromAttribute(
                servletContext.getAttribute(DebugBundleWebDeployment.CLIENT_ATTRIBUTE)
        );
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
            }
            servletContext.removeAttribute(DebugBundleWebDeployment.CLIENT_ATTRIBUTE);
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}