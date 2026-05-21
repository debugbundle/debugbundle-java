package com.debugbundle.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class TriggerTokenResolver {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String TOKEN_PREFIX = "dbundle_probe_";
    private static final String QUERY_PARAMETER_NAME = "_debug_probe";
    private static final String HEADER_NAME = "x-debugbundle-probe-trigger";

    private TriggerTokenResolver() {
    }

    static List<RemoteProbeDirective> resolveRequestTriggerDirectives(
            Map<String, Object> request,
            String triggerTokenKey,
            long nowMillis
    ) {
        if (request == null || triggerTokenKey == null || triggerTokenKey.isBlank()) {
            return List.of();
        }

        String token = extractTriggerToken(request);
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return List.of();
        }

        String encoded = token.substring(TOKEN_PREFIX.length());
        int separatorIndex = encoded.indexOf('.');
        if (separatorIndex <= 0 || separatorIndex >= encoded.length() - 1) {
            return List.of();
        }

        String payloadSegment = encoded.substring(0, separatorIndex);
        String signatureSegment = encoded.substring(separatorIndex + 1);
        if (!hasValidSignature(payloadSegment, signatureSegment, triggerTokenKey)) {
            return List.of();
        }

        TriggerTokenPayload payload = decodePayload(payloadSegment);
        if (payload == null) {
            return List.of();
        }

        try {
            if (Instant.parse(payload.triggerExpiresAt()).toEpochMilli() <= nowMillis) {
                return List.of();
            }
        } catch (RuntimeException error) {
            return List.of();
        }

        return List.of(new RemoteProbeDirective(
                payload.activationId(),
                payload.labelPattern(),
                payload.service(),
                payload.environment(),
                payload.triggerExpiresAt()
        ));
    }

    @SuppressWarnings("unchecked")
    private static String extractTriggerToken(Map<String, Object> request) {
        Object headersValue = request.get("headers");
        if (headersValue instanceof Map<?, ?> rawHeaders) {
            String headerToken = extractMapValue((Map<String, Object>) rawHeaders, HEADER_NAME, true);
            if (headerToken != null) {
                return headerToken;
            }
        }

        Object queryValue = request.get("query");
        if (queryValue instanceof Map<?, ?> rawQuery) {
            return extractMapValue((Map<String, Object>) rawQuery, QUERY_PARAMETER_NAME, false);
        }

        return null;
    }

    private static TriggerTokenPayload decodePayload(String payloadSegment) {
        try {
            byte[] decodedBytes = decodeBase64UrlBytes(payloadSegment);
            if (decodedBytes == null) {
                return null;
            }

            JsonNode node = OBJECT_MAPPER.readTree(decodedBytes);
            if (node == null || !node.isObject()) {
                return null;
            }

            String activationId = asNonBlankString(node.get("activation_id"));
            String labelPattern = asNonBlankString(node.get("label_pattern"));
            String service = asNonBlankString(node.get("service"));
            String environment = asNonBlankString(node.get("environment"));
            String triggerExpiresAt = asNonBlankString(node.get("trigger_expires_at"));
            if (activationId == null
                    || labelPattern == null
                    || service == null
                    || environment == null
                    || triggerExpiresAt == null) {
                return null;
            }

            Instant.parse(triggerExpiresAt);
            return new TriggerTokenPayload(activationId, labelPattern, service, environment, triggerExpiresAt);
        } catch (Exception error) {
            return null;
        }
    }

    private static boolean hasValidSignature(String payloadSegment, String signatureSegment, String triggerTokenKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(triggerTokenKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(payloadSegment.getBytes(StandardCharsets.UTF_8));
            byte[] actual = decodeBase64UrlBytes(signatureSegment);
            if (actual == null || actual.length != expected.length) {
                return false;
            }
            return java.security.MessageDigest.isEqual(expected, actual);
        } catch (Exception error) {
            return false;
        }
    }

    private static byte[] decodeBase64UrlBytes(String value) {
        try {
            int padding = value.length() % 4;
            String normalized = padding == 0 ? value : value + "=".repeat(4 - padding);
            return Base64.getUrlDecoder().decode(normalized);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String extractMapValue(Map<String, Object> mapping, String key, boolean caseInsensitive) {
        for (Map.Entry<String, Object> entry : mapping.entrySet()) {
            String candidateKey = entry.getKey();
            boolean matches = caseInsensitive
                    ? candidateKey != null && candidateKey.toLowerCase(Locale.ROOT).equals(key.toLowerCase(Locale.ROOT))
                    : key.equals(candidateKey);
            if (!matches) {
                continue;
            }

            Object value = entry.getValue();
            if (value instanceof String stringValue && !stringValue.isBlank()) {
                return stringValue;
            }

            if (value instanceof List<?> listValue) {
                for (Object item : listValue) {
                    if (item instanceof String stringItem && !stringItem.isBlank()) {
                        return stringItem;
                    }
                }
            }
        }
        return null;
    }

    private static String asNonBlankString(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        String value = node.asText(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private record TriggerTokenPayload(
            String activationId,
            String labelPattern,
            String service,
            String environment,
            String triggerExpiresAt
    ) {}
}
