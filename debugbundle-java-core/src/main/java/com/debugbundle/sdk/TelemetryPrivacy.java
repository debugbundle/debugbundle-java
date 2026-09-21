package com.debugbundle.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mandatory bounded protection for application-owned telemetry, independent of custom redaction options. */
public final class TelemetryPrivacy {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REDACTED = "[REDACTED]";
    private static final int MAX_BYTES = 256 * 1024;
    private static final List<String> FIELDS = List.of(
            "password", "secret", "token", "api_key", "apikey", "access_token", "refresh_token",
            "private_key", "accessToken", "refreshToken", "privateKey", "clientSecret", "passwd", "card_number", "credit_card", "cvv", "cvc", "pin", "expiry",
            "phone", "bearer", "session_id", "otp", "verification_code", "authorization", "cookie",
            "ssn", "client_secret", "x_api_key", "set_cookie", "proxy_authorization"
    );
    private static final Pattern HEADER = Pattern.compile("\\b(Authorization|Proxy-Authorization|Cookie|Set-Cookie)\\s*:\\s*[^\\r\\n]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER = Pattern.compile("\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/-]{6,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN = Pattern.compile("\\bdbundle_(?:proj|mem|probe|agent)_[A-Za-z0-9_-]+\\b");
    private static final Pattern PEM = Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----[\\s\\S]*?-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", Pattern.CASE_INSENSITIVE);
    private static final Pattern PEM_START = Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("\\bhttps?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CARD = Pattern.compile("(?<![A-Za-z0-9_-])(?:[0-9][ -]?){12,18}[0-9](?![A-Za-z0-9_-])");
    private static final Pattern ENCODED_LABEL = Pattern.compile("(?:password|token|secret|authorization|cookie)%3[ad]", Pattern.CASE_INSENSITIVE);
    private static final Pattern MALFORMED_LABEL = Pattern.compile("(?:password|token|secret|authorization|cookie)[\"']?\\s*[:=]", Pattern.CASE_INSENSITIVE);

    private TelemetryPrivacy() {
    }

    public static Object protect(Object value, Set<String> additionalFields) {
        if (additionalFields == null || additionalFields.size() > 128 || additionalFields.stream().anyMatch(
                field -> field == null || field.isBlank() || field.length() > 64)) {
            throw new IllegalArgumentException("unsafe_input");
        }
        Work work = new Work(additionalFields);
        try {
            Object result = visit(value, work, 0, true);
            if (JSON.writeValueAsBytes(result).length > MAX_BYTES) {
                throw new IllegalArgumentException("budget_exceeded");
            }
            return result;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("unsafe_input");
        }
    }

    public static boolean hasSafeEventIdentity(Map<String, Object> event, Set<String> extra) {
        List<Object> values = new ArrayList<>();
        for (String field : List.of("schema_version", "sdk_name", "sdk_version")) values.add(event.get(field));
        Object correlation = event.get("correlation");
        if (correlation != null) {
            if (!(correlation instanceof Map<?, ?> fields) || fields.size() > 8) return false;
            values.addAll(fields.values());
        }
        for (Object value : values) {
            if (value != null && (!(value instanceof String) || !value.equals(protect(value, extra)))) return false;
        }
        return true;
    }

    private static final class Work {
        private int nodes;
        private int bytes;
        private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<String> keys;
        private final Set<String> additional;

        private Work(Set<String> additional) {
            this.additional = additional;
            this.keys = new java.util.HashSet<>();
            for (String field : FIELDS) keys.add(canonical(field));
            for (String field : additional) keys.add(canonical(field));
        }
    }

    private static String canonical(String key) {
        return key.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static boolean sensitiveKey(String key, Work work) {
        String[] parts = key.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+");
        for (int start = 0; start < parts.length; start++) {
            StringBuilder joined = new StringBuilder();
            for (int end = start; end < parts.length; end++) {
                joined.append(parts[end]);
                if (work.keys.contains(joined.toString())) return true;
            }
        }
        return false;
    }

    private static void count(Work work, String text) {
        work.bytes += text.getBytes(StandardCharsets.UTF_8).length;
        if (work.bytes > MAX_BYTES) throw new IllegalArgumentException("budget_exceeded");
    }

    private static Object visit(Object value, Work work, int depth, boolean structured) throws Exception {
        if (++work.nodes > 4096) throw new IllegalArgumentException("budget_exceeded");
        if (depth > 16) return REDACTED;
        if (value instanceof String text) {
            if (text.length() > 16 * 1024 || text.getBytes(StandardCharsets.UTF_8).length > 16 * 1024) return REDACTED;
            count(work, text);
            if (structured && (text.startsWith("{") || text.startsWith("["))) {
                try {
                    Object parsed = JSON.readValue(text, Object.class);
                    if (parsed instanceof Map<?, ?> || parsed instanceof List<?>) {
                        return JSON.writeValueAsString(visit(parsed, work, 0, false));
                    }
                } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                    // Recognizable secrets in malformed structured text are withheld below.
                }
            }
            String cleaned = scrubText(text, work);
            return cleaned.equals(text) && (text.startsWith("{") || text.startsWith("["))
                    && MALFORMED_LABEL.matcher(text).find() ? REDACTED : cleaned;
        }
        if (value instanceof Map<?, ?> map) {
            if (!work.seen.add(map)) return "[Circular]";
            try {
                if (map.size() > 256) return REDACTED;
                Map<String, Object> output = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("unsafe_input");
                    if (key.length() > 128) continue;
                    count(work, key);
                    if (!scrubText(key, work).equals(key)) continue;
                    output.put(key, sensitiveKey(key, work) ? REDACTED : visit(entry.getValue(), work, depth + 1, structured));
                }
                return output;
            } finally {
                work.seen.remove(map);
            }
        }
        if (value instanceof List<?> list) {
            if (!work.seen.add(list)) return "[Circular]";
            try {
                if (list.size() > 256) return REDACTED;
                List<Object> output = new ArrayList<>(list.size());
                for (Object item : list) output.add(visit(item, work, depth + 1, structured));
                return output;
            } finally {
                work.seen.remove(list);
            }
        }
        if (value == null || value instanceof Boolean || value instanceof Integer || value instanceof Long) return value;
        if (value instanceof Double number && Double.isFinite(number)) return value;
        if (value instanceof Float number && Float.isFinite(number)) return value;
        throw new IllegalArgumentException("unsafe_input");
    }

    private static String scrubText(String text, Work work) {
        return scrubText(text, work, true);
    }

    private static String scrubText(String text, Work work, boolean scanUrls) {
        if (PEM_START.matcher(text).find() && !PEM.matcher(text).find()) return REDACTED;
        String output = ENCODED_LABEL.matcher(text).find() ? URLDecoder.decode(text, StandardCharsets.UTF_8) : text;
        output = PEM.matcher(output).replaceAll(REDACTED);
        output = HEADER.matcher(output).replaceAll("$1: [REDACTED]");
        output = BEARER.matcher(output).replaceAll("$1 [REDACTED]");
        output = TOKEN.matcher(output).replaceAll(REDACTED);
        for (String field : FIELDS) output = replaceAssignment(output, field);
        for (String field : work.additional) output = replaceAssignment(output, field);
        Matcher cards = CARD.matcher(output);
        StringBuffer cardOutput = new StringBuffer();
        while (cards.find()) cards.appendReplacement(cardOutput, validCard(cards.group()) ? REDACTED : Matcher.quoteReplacement(cards.group()));
        cards.appendTail(cardOutput);
        if (!scanUrls) return cardOutput.toString();
        Matcher urls = URL.matcher(cardOutput.toString());
        StringBuffer urlOutput = new StringBuffer();
        while (urls.find()) {
            String candidate = urls.group();
            String raw = candidate.replaceFirst("[).,;]+$", "");
            urls.appendReplacement(urlOutput, Matcher.quoteReplacement(scrubUrl(raw, work) + candidate.substring(raw.length())));
        }
        urls.appendTail(urlOutput);
        return urlOutput.toString();
    }

    private static String replaceAssignment(String text, String field) {
        Pattern pattern = Pattern.compile("\\b(" + Pattern.quote(field) + ")\\b([\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s&,;]+)", Pattern.CASE_INSENSITIVE);
        return pattern.matcher(text).replaceAll("$1$2[REDACTED]");
    }

    private static boolean validCard(String value) {
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() < 13 || digits.length() > 19 || digits.matches("([0-9])\\1+")) return false;
        int sum = 0;
        for (int index = digits.length() - 1; index >= 0; index--) {
            int digit = digits.charAt(index) - '0';
            if ((digits.length() - index) % 2 == 0) {
                digit *= 2;
                if (digit > 9) digit -= 9;
            }
            sum += digit;
        }
        return sum % 10 == 0;
    }

    private static String scrubUrl(String raw, Work work) {
        try {
            URI uri = URI.create(raw);
            if (uri.getHost() == null) return REDACTED;
            StringBuilder output = new StringBuilder(uri.getScheme()).append("://");
            if (uri.getRawUserInfo() != null) output.append("REDACTED@");
            output.append(uri.getHost());
            if (uri.getPort() != -1) output.append(':').append(uri.getPort());
            output.append(uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
            if (uri.getRawQuery() != null) {
                List<String> pairs = new ArrayList<>();
                for (String pair : uri.getRawQuery().split("&", -1)) {
                    String[] parts = pair.split("=", 2);
                    String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                    String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                    if (key.length() > 128 || !scrubText(key, work, false).equals(key)) continue;
                    if (sensitiveKey(key, work) || !scrubText(value, work, false).equals(value)) value = REDACTED;
                    pairs.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
                }
                output.append('?').append(String.join("&", pairs));
            }
            return output.toString();
        } catch (IllegalArgumentException error) {
            return REDACTED;
        }
    }
}
