package com.debugbundle.sdk.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debugbundle.sdk.DebugBundle;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DebugBundleWebUtilitiesTest {
    @Test
    void boundedBodyReaderAcceptsLimitAndRejectsOneByteOver() throws Exception {
        byte[] maximum = new byte[DebugBundleBrowserRelay.DEFAULT_MAX_BODY_BYTES];
        byte[] oversized = new byte[DebugBundleBrowserRelay.DEFAULT_MAX_BODY_BYTES + 1];

        assertThat(DebugBundleBrowserRelayBodyReader.readBoundedBody(new ByteArrayInputStream(maximum)))
                .hasSize(maximum.length);
        assertThatThrownBy(() -> DebugBundleBrowserRelayBodyReader.readBoundedBody(
                new ByteArrayInputStream(oversized)))
                .isInstanceOf(DebugBundleBrowserRelayBodyReader.PayloadTooLargeException.class);
    }

    @Test
    void deploymentHelpersNormalizeContextPathsAndClientAttributes() {
        assertThat(DebugBundleWebDeployment.clientFromAttribute(DebugBundle.create(null))).isNotNull();
        assertThat(DebugBundleWebDeployment.clientFromAttribute("not-a-client")).isNull();
        assertThat(DebugBundleWebDeployment.deploymentKeyFromContextPath(null)).isEqualTo("root-webapp");
        assertThat(DebugBundleWebDeployment.deploymentKeyFromContextPath("/")).isEqualTo("root-webapp");
        assertThat(DebugBundleWebDeployment.deploymentKeyFromContextPath(" /orders/admin "))
                .isEqualTo("orders-admin");
        assertThat(DebugBundleWebDeployment.deploymentKeyFromContextPath("///"))
                .isEqualTo("--");
        assertThat(DebugBundleWebDeployment.serviceNameFromContextPath("/checkout"))
                .isEqualTo("checkout");
    }

    @Test
    void relayValueObjectsAndNullDependenciesUseSafeDefaults() {
        DebugBundleBrowserRelay.Config config =
                new DebugBundleBrowserRelay.Config(null, " ", " ", " ", 0, true, " ", null);
        DebugBundleBrowserRelay.Request request =
                new DebugBundleBrowserRelay.Request(null, null, null, null);
        DebugBundleBrowserRelay relay = new DebugBundleBrowserRelay(null, null, null);
        DebugBundleBrowserRelay.Request allowedGet = new DebugBundleBrowserRelay.Request(
                "GET", null, null, name -> switch (name) {
                    case "Origin" -> "https://app.example.test";
                    case "Host" -> "app.example.test";
                    default -> null;
                });

        assertThat(config.endpoint()).isEqualTo("https://api.debugbundle.com/v1/events");
        assertThat(config.projectMode()).isEqualTo("connected");
        assertThat(config.localEventsDir()).isEqualTo(".debugbundle/local/events");
        assertThat(config.rateLimitPerMinute()).isEqualTo(60);
        assertThat(config.spoolDir()).isEqualTo(".debugbundle/local/browser-relay-spool");
        assertThat(config.allowedOrigins()).isEmpty();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.header("Origin")).isNull();
        assertThat(relay.handle(request, null).status()).isEqualTo(403);
        assertThat(relay.handle(allowedGet, null).status()).isEqualTo(405);
        assertThat(DebugBundleBrowserRelay.Response.empty(204).headers()).isEmpty();
        assertThat(DebugBundleBrowserRelay.Response.empty(204, null).headers()).isEmpty();
        assertThat(DebugBundleBrowserRelay.Response.withBody(202, 1, 0, List.of()).body())
                .containsEntry("accepted", 1);
        assertThat(DebugBundleBrowserRelay.Response.withBody(202, 1, 0, List.of())
                .withHeaders(Map.of("Vary", "Origin")).headers())
                .containsEntry("Vary", "Origin");
    }
}
