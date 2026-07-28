package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RemoteConfigParserValidationTest {
    @Test
    void rejectsEmptyMalformedAndNonObjectDocuments() {
        assertThat(RemoteConfigParser.parse(null, 60_000L, 0L)).isNull();
        assertThat(RemoteConfigParser.parse(" ", 60_000L, 0L)).isNull();
        assertThat(RemoteConfigParser.parse("{", 60_000L, 0L)).isNull();
        assertThat(RemoteConfigParser.parse("[]", 60_000L, 0L)).isNull();
        assertThat(RemoteConfigParser.parse(
                "{\"capture_policy\":\"balanced\"}", 60_000L, 0L)).isNull();
    }

    @Test
    void parsesDirectivesPollIntervalsAndAllCaptureModes() {
        RemoteConfigSnapshot snapshot = RemoteConfigParser.parse("""
                {
                  "probes_enabled": true,
                  "remote_probes_enabled": true,
                  "poll_interval_ms": 120000,
                  "trigger_token_key": " secret ",
                  "active_probes": [
                    {
                      "id": "activation-1",
                      "label_pattern": "checkout.*",
                      "service": "checkout",
                      "environment": "production",
                      "expires_at": "2036-03-20T00:00:00Z"
                    },
                    {"id": "expired", "label_pattern": "*", "service": "checkout",
                     "environment": "production", "expires_at": "2020-01-01T00:00:00Z"},
                    {"id": "invalid", "label_pattern": "*", "service": "checkout",
                     "environment": "production", "expires_at": "tomorrow"},
                    null
                  ],
                  "capture_policy": {
                    "preset": "investigative",
                    "capture_logs": "info",
                    "capture_request_events": "all",
                    "capture_breadcrumbs": "standalone",
                    "capture_probe_events": "standalone_when_activated",
                    "immediate_client_error_statuses": [429, 404, 404],
                    "immediate_client_error_path_rules": [
                      {"status_code": 404, "path_pattern": "/orders/*", "methods": ["get", "GET", "POST"]}
                    ]
                  }
                }
                """, 60_000L, 1_773_446_400_000L);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.pollIntervalMillis()).isEqualTo(120_000L);
        assertThat(snapshot.triggerTokenKey()).isEqualTo(" secret ");
        assertThat(snapshot.directives()).hasSize(1);
        assertThat(snapshot.capturePolicy().captureLogs()).isEqualTo(CapturePolicy.CaptureLogsMode.INFO);
        assertThat(snapshot.capturePolicy().captureRequestEvents())
                .isEqualTo(CapturePolicy.CaptureRequestEventsMode.ALL);
        assertThat(snapshot.capturePolicy().captureBreadcrumbs())
                .isEqualTo(CapturePolicy.CaptureBreadcrumbsMode.STANDALONE);
        assertThat(snapshot.capturePolicy().captureProbeEvents())
                .isEqualTo(CapturePolicy.CaptureProbeEventsMode.STANDALONE_WHEN_ACTIVATED);
        assertThat(snapshot.capturePolicy().immediateClientErrorStatuses()).containsExactly(404, 429);
        assertThat(snapshot.capturePolicy().immediateClientErrorPathRules().get(0).methods())
                .containsExactly("GET", "POST");
    }

    @Test
    void fallsBackForDisabledRemoteProbesAndInvalidPollIntervals() {
        RemoteConfigSnapshot missing = RemoteConfigParser.parse("""
                {"remote_probes_enabled":false,"poll_interval_ms":0}
                """, 60_000L, 0L);
        RemoteConfigSnapshot wrongType = RemoteConfigParser.parse("""
                {"remote_probes_enabled":true,"poll_interval_ms":"later"}
                """, 45_000L, 0L);

        assertThat(missing).isNotNull();
        assertThat(missing.pollIntervalMillis()).isEqualTo(60_000L);
        assertThat(missing.capturePolicy()).isEqualTo(CapturePolicy.BALANCED);
        assertThat(wrongType).isNotNull();
        assertThat(wrongType.pollIntervalMillis()).isEqualTo(45_000L);
    }

    @Test
    void rejectsInvalidCapturePolicyEnumsAndStatusCollections() {
        assertInvalidPolicy("""
                {"capture_logs":"verbose","capture_request_events":"all",
                 "capture_breadcrumbs":"standalone","capture_probe_events":"buffer_only"}
                """);
        assertInvalidPolicy("""
                {"capture_logs":"off","capture_request_events":"sometimes",
                 "capture_breadcrumbs":"standalone","capture_probe_events":"buffer_only"}
                """);
        assertInvalidPolicy("""
                {"capture_logs":"error","capture_request_events":"off",
                 "capture_breadcrumbs":"always","capture_probe_events":"buffer_only"}
                """);
        assertInvalidPolicy("""
                {"capture_logs":"warning","capture_request_events":"filtered",
                 "capture_breadcrumbs":"local_only","capture_probe_events":"always"}
                """);
        assertInvalidPolicy("""
                {"capture_logs":"warning","capture_request_events":"failures_only",
                 "capture_breadcrumbs":"exception_only","capture_probe_events":"buffer_only",
                 "immediate_client_error_statuses":"404"}
                """);
        assertInvalidPolicy("""
                {"capture_logs":"warning","capture_request_events":"failures_only",
                 "capture_breadcrumbs":"exception_only","capture_probe_events":"buffer_only",
                 "immediate_client_error_statuses":[399]}
                """);
        assertInvalidPolicy("""
                {"capture_logs":"warning","capture_request_events":"failures_only",
                 "capture_breadcrumbs":"exception_only","capture_probe_events":"buffer_only",
                 "immediate_client_error_statuses":[400,401,402,403,404,405,406,407,408,409,410,411,412]}
                """);
    }

    @Test
    void rejectsInvalidImmediatePathRules() {
        assertInvalidRule("\"not-an-array\"");
        assertInvalidRule("[null]");
        assertInvalidRule("[{\"status_code\":\"404\",\"path_pattern\":\"/orders\"}]");
        assertInvalidRule("[{\"status_code\":500,\"path_pattern\":\"/orders\"}]");
        assertInvalidRule("[{\"status_code\":404,\"path_pattern\":\"orders\"}]");
        assertInvalidRule("[{\"status_code\":404,\"path_pattern\":\"/orders?x=1\"}]");
        assertInvalidRule("[{\"status_code\":404,\"path_pattern\":\"/orders/*/items\"}]");
        assertInvalidRule("[{\"status_code\":404,\"path_pattern\":\"/orders\",\"methods\":\"GET\"}]");
        assertInvalidRule("[{\"status_code\":404,\"path_pattern\":\"/orders\",\"methods\":[1]}]");
        assertInvalidRule("[{\"status_code\":404,\"path_pattern\":\"/orders\",\"methods\":[\"TRACE\"]}]");
    }

    private static void assertInvalidPolicy(String capturePolicy) {
        assertThat(RemoteConfigParser.parse(
                "{\"capture_policy\":" + capturePolicy + "}", 60_000L, 0L)).isNull();
    }

    private static void assertInvalidRule(String rules) {
        assertInvalidPolicy("""
                {"capture_logs":"warning","capture_request_events":"failures_only",
                 "capture_breadcrumbs":"exception_only","capture_probe_events":"buffer_only",
                 "immediate_client_error_path_rules":
                """ + rules + "}");
    }
}
