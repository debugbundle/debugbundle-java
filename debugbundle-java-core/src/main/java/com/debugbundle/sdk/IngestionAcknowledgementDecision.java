package com.debugbundle.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

record IngestionAcknowledgementDecision(
        Kind kind,
        int accepted,
        List<Integer> retryableIndices,
        List<IngestionError> terminalErrors,
        String reason
) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> RETRYABLE_REASONS = Set.of(
            "rate_limited",
            "monthly_quota_exceeded",
            "analytics_quota_exceeded"
    );

    static IngestionAcknowledgementDecision decide(String body, int batchLength) {
        if (body == null || body.isBlank()) {
            return legacy();
        }
        final JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(body);
        } catch (Exception ignored) {
            return legacy();
        }
        if (!root.isObject()
                || (!root.has("accepted") && !root.has("rejected") && !root.has("errors"))) {
            return legacy();
        }
        JsonNode acceptedNode = root.get("accepted");
        JsonNode rejectedNode = root.get("rejected");
        JsonNode errorsNode = root.get("errors");
        if (!isCount(acceptedNode)
                || !isCount(rejectedNode)
                || errorsNode == null
                || !errorsNode.isArray()) {
            return protocolFailure("inconsistent_counts");
        }
        int accepted = acceptedNode.asInt();
        int rejected = rejectedNode.asInt();
        if (accepted + rejected != batchLength || errorsNode.size() != rejected) {
            return protocolFailure("inconsistent_counts");
        }

        Set<Integer> seen = new HashSet<>();
        List<Integer> retryableIndices = new ArrayList<>();
        List<IngestionError> terminalErrors = new ArrayList<>();
        for (JsonNode errorNode : errorsNode) {
            JsonNode indexNode = errorNode.get("index");
            JsonNode reasonNode = errorNode.get("reason");
            if (!errorNode.isObject()
                    || indexNode == null
                    || !indexNode.isIntegralNumber()
                    || reasonNode == null
                    || !reasonNode.isTextual()) {
                return protocolFailure("invalid_error_index");
            }
            int index = indexNode.asInt();
            String reason = reasonNode.asText();
            if (index < 0 || index >= batchLength || reason.isBlank() || !seen.add(index)) {
                return protocolFailure("invalid_error_index");
            }
            if (RETRYABLE_REASONS.contains(reason)) {
                retryableIndices.add(index);
            } else {
                terminalErrors.add(new IngestionError(index, reason));
            }
        }
        return new IngestionAcknowledgementDecision(
                Kind.ACKNOWLEDGED,
                accepted,
                List.copyOf(retryableIndices),
                List.copyOf(terminalErrors),
                null
        );
    }

    private static boolean isCount(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() && node.asInt() >= 0;
    }

    private static IngestionAcknowledgementDecision legacy() {
        return new IngestionAcknowledgementDecision(Kind.LEGACY, 0, List.of(), List.of(), null);
    }

    private static IngestionAcknowledgementDecision protocolFailure(String reason) {
        return new IngestionAcknowledgementDecision(Kind.PROTOCOL_FAILURE, 0, List.of(), List.of(), reason);
    }

    enum Kind {
        LEGACY,
        PROTOCOL_FAILURE,
        ACKNOWLEDGED
    }

    record IngestionError(int index, String reason) {
    }
}
