package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class TriggerTokenResolverValidationTest {
    private static final long NOW = 1_773_446_400_000L;
    private static final String KEY = "trigger-key";

    @Test
    void rejectsMissingMalformedAndIncorrectlySignedTokens() {
        assertThat(resolve(null, KEY)).isEmpty();
        assertThat(resolve(Map.of(), null)).isEmpty();
        assertThat(resolve(query("other"), KEY)).isEmpty();
        assertThat(resolve(query("dbundle_probe_missing-separator"), KEY)).isEmpty();
        assertThat(resolve(query("dbundle_probe_.signature"), KEY)).isEmpty();
        assertThat(resolve(query("dbundle_probe_payload."), KEY)).isEmpty();
        assertThat(resolve(query("dbundle_probe_payload.invalid"), KEY)).isEmpty();
    }

    @Test
    void readsFirstNonBlankQueryListValueAndValidatesPayloadShape() throws Exception {
        String valid = token("""
                {"activation_id":"activation-1","label_pattern":"checkout.*","service":"checkout",
                 "environment":"production","trigger_expires_at":"2036-03-20T00:00:00Z"}
                """);
        List<RemoteProbeDirective> directives = resolve(
                Map.of("query", Map.of("_debug_probe", List.of("", valid))), KEY);

        assertThat(directives).hasSize(1);
        assertThat(directives.get(0).id()).isEqualTo("activation-1");
        assertThat(resolve(query(token("[]")), KEY)).isEmpty();
        assertThat(resolve(query(token("{}")), KEY)).isEmpty();
        assertThat(resolve(query(token("""
                {"activation_id":"activation-1","label_pattern":"*","service":"checkout",
                 "environment":"production","trigger_expires_at":"invalid"}
                """)), KEY)).isEmpty();
    }

    @Test
    void ignoresBlankAndNonStringHeaderAndQueryValues() {
        assertThat(resolve(Map.of("headers", Map.of("X-DebugBundle-Probe-Trigger", " ")), KEY)).isEmpty();
        assertThat(resolve(Map.of("headers", Map.of("X-DebugBundle-Probe-Trigger", 42)), KEY)).isEmpty();
        assertThat(resolve(Map.of("query", Map.of("_debug_probe", List.of(42, " "))), KEY)).isEmpty();
        assertThat(resolve(Map.of("query", "invalid"), KEY)).isEmpty();
    }

    private static List<RemoteProbeDirective> resolve(Map<String, Object> request, String key) {
        return TriggerTokenResolver.resolveRequestTriggerDirectives(request, key, NOW);
    }

    private static Map<String, Object> query(String token) {
        return Map.of("query", Map.of("_debug_probe", token));
    }

    private static String token(String payloadJson) throws Exception {
        String payloadSegment = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signatureSegment = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(payloadSegment.getBytes(StandardCharsets.UTF_8)));
        return "dbundle_probe_" + payloadSegment + "." + signatureSegment;
    }
}
