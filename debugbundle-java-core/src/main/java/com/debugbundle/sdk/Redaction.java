package com.debugbundle.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class Redaction {
    private static final String REDACTED = "[REDACTED]";
    private static final String CIRCULAR = "[Circular]";
    private static final String MAX_DEPTH = "[MaxDepth]";
    private static final String TRUNCATED = "[Truncated]";
    private static final int MAX_DEPTH_LIMIT = 8;
    private static final int MAX_COLLECTION_ITEMS = 100;
    private static final int MAX_STRING_LENGTH = 8_192;
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");
    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Za-z0-9]+");

    private Redaction() {
    }

    @SuppressWarnings("unchecked")
    static Object redact(Object value, Set<String> sensitiveFields) {
        return redact(value, sensitiveFields, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    @SuppressWarnings("unchecked")
    private static Object redact(Object value, Set<String> sensitiveFields, int depth, Set<Object> seen) {
        if (value == null) {
            return null;
        }
        if (value instanceof String stringValue) {
            return truncateString(stringValue);
        }
        if (depth > MAX_DEPTH_LIMIT) {
            return MAX_DEPTH;
        }
        if (value instanceof Map<?, ?> rawMap) {
            if (!seen.add(rawMap)) {
                return CIRCULAR;
            }
            Map<String, Object> redacted = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (count >= MAX_COLLECTION_ITEMS) {
                    redacted.put("_debugbundle_truncated", TRUNCATED);
                    break;
                }
                String key = String.valueOf(entry.getKey());
                if (isSensitiveKey(key, sensitiveFields)) {
                    redacted.put(key, REDACTED);
                } else {
                    redacted.put(key, redact(entry.getValue(), sensitiveFields, depth + 1, seen));
                }
                count++;
            }
            seen.remove(rawMap);
            return redacted;
        }
        if (value instanceof List<?> rawList) {
            if (!seen.add(rawList)) {
                return CIRCULAR;
            }
            List<Object> redacted = new ArrayList<>(rawList.size());
            int count = 0;
            for (Object item : rawList) {
                if (count >= MAX_COLLECTION_ITEMS) {
                    redacted.add(TRUNCATED);
                    break;
                }
                redacted.add(redact(item, sensitiveFields, depth + 1, seen));
                count++;
            }
            seen.remove(rawList);
            return redacted;
        }
        return value;
    }

    static boolean isSensitiveKey(String key, Set<String> sensitiveFields) {
        if (key == null || sensitiveFields == null || sensitiveFields.isEmpty()) {
            return false;
        }

        List<String> keySegments = segments(key);
        if (keySegments.isEmpty()) {
            return false;
        }

        String normalized = String.join("_", keySegments);
        if (sensitiveFields.contains(normalized)) {
            return true;
        }

        for (String sensitiveField : sensitiveFields) {
            List<String> sensitiveSegments = segments(sensitiveField);
            if (sensitiveSegments.isEmpty()) {
                continue;
            }
            if (matchesSegments(keySegments, sensitiveSegments)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesSegments(List<String> keySegments, List<String> sensitiveSegments) {
        if (sensitiveSegments.size() == 1) {
            return keySegments.stream().anyMatch(segment -> segment.equals(sensitiveSegments.get(0)));
        }

        for (int index = 0; index + sensitiveSegments.size() <= keySegments.size(); index++) {
            if (keySegments.subList(index, index + sensitiveSegments.size()).equals(sensitiveSegments)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> segments(String key) {
        String withCamelBoundaries = CAMEL_BOUNDARY.matcher(key).replaceAll("_");
        String[] parts = NON_ALNUM.split(withCamelBoundaries);
        List<String> segments = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                segments.add(part.toLowerCase(Locale.ROOT));
            }
        }
        return segments;
    }

    private static String truncateString(String value) {
        if (value.length() <= MAX_STRING_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_STRING_LENGTH) + TRUNCATED;
    }
}
