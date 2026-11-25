package com.debugbundle.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mandatory bounded protection for application-owned telemetry, independent of custom redaction options. */
public final class TelemetryPrivacy {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REDACTED = "[REDACTED]";
    static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_CUSTOM_KEYS = 128;
    private static final int MAX_CUSTOM_KEY_LENGTH = 64;
    private static final List<String> FIELDS = List.of(
            "password", "secret", "token", "api_key", "apikey", "access_token", "refresh_token",
            "private_key", "accessToken", "refreshToken", "privateKey", "clientSecret", "passwd", "card_number", "credit_card", "cvv", "cvc", "pin", "expiry",
            "phone", "bearer", "session_id", "otp", "verification_code", "authorization", "cookie",
            "ssn", "client_secret", "x_api_key", "set_cookie", "proxy_authorization"
    );
    private static final Pattern HEADER = Pattern.compile("\\b(Authorization|Proxy-Authorization|Cookie|Set-Cookie)\\s*:\\s*[^\\r\\n]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER = Pattern.compile("\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/-]{6,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN = Pattern.compile("\\bdbundle_(?:proj|mem|probe|agent|invite|org_invite|org_transfer)_[A-Za-z0-9_-]+\\b");
    private static final Pattern PEM = Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----[\\s\\S]*?-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", Pattern.CASE_INSENSITIVE);
    private static final Pattern PEM_START = Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("\\bhttps?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CARD = Pattern.compile("(?<![A-Za-z0-9_-])(?:[0-9][ -]?){12,18}[0-9](?![A-Za-z0-9_-])");
    private static final Pattern ENCODED_LABEL = Pattern.compile("(?:password|token|secret|authorization|cookie)%3[ad]", Pattern.CASE_INSENSITIVE);
    private static final Pattern MALFORMED_LABEL = Pattern.compile("(?:password|token|secret|authorization|cookie)[\"']?\\s*[:=]", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEFAULT_ASSIGNMENT = assignmentPattern(FIELDS);
    static final CompiledPolicy DEFAULT_POLICY = new CompiledPolicy(List.of(), List.of());

    private TelemetryPrivacy() {
    }

    public static Object protect(Object value, Set<String> additionalFields) {
        return protect(value, additionalFields, Set.of());
    }

    public static Object protect(Object value, Set<String> additionalFields, Set<String> exactFields) {
        return protect(value, compile(additionalFields, exactFields));
    }

    public static boolean hasSafeEventIdentity(Map<String, Object> event, Set<String> extra) {
        return hasSafeEventIdentity(event, extra, Set.of());
    }

    public static boolean hasSafeEventIdentity(Map<String, Object> event, Set<String> extra, Set<String> exact) {
        return hasSafeEventIdentity(event, compile(extra, exact));
    }

    static CompiledPolicy compile(Collection<String> additionalFields, Collection<String> exactFields) {
        if (additionalFields == null || exactFields == null
                || additionalFields.size() > MAX_CUSTOM_KEYS
                || exactFields.size() > MAX_CUSTOM_KEYS
                || additionalFields.size() + exactFields.size() > MAX_CUSTOM_KEYS) {
            throw new IllegalArgumentException("unsafe_input");
        }
        List<String> validAdditional = new ArrayList<>(additionalFields.size());
        for (String field : additionalFields) {
            if (field == null || field.isBlank() || field.length() > MAX_CUSTOM_KEY_LENGTH) {
                throw new IllegalArgumentException("unsafe_input");
            }
            validAdditional.add(field.trim());
        }
        List<String> validExact = new ArrayList<>(exactFields.size());
        for (String field : exactFields) {
            if (field == null || field.isBlank() || field.length() > MAX_CUSTOM_KEY_LENGTH) {
                throw new IllegalArgumentException("unsafe_input");
            }
            validExact.add(field.trim());
        }
        if (validAdditional.isEmpty() && validExact.isEmpty()) {
            return DEFAULT_POLICY;
        }
        return new CompiledPolicy(validAdditional, validExact);
    }

    static CompiledPolicy tryCompile(Collection<String> additionalFields, Collection<String> exactFields) {
        try {
            return compile(additionalFields, exactFields);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static Object protect(Object value, CompiledPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("unsafe_input");
        }
        Work work = new Work(policy);
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

    static boolean hasSafeEventIdentity(Map<String, Object> event, CompiledPolicy policy) {
        if (event == null || policy == null) {
            return false;
        }
        List<Object> values = new ArrayList<>();
        for (String field : List.of("schema_version", "sdk_name", "sdk_version")) {
            values.add(event.get(field));
        }
        Object correlation = event.get("correlation");
        if (correlation != null) {
            if (!(correlation instanceof Map<?, ?> fields) || fields.size() > 8) {
                return false;
            }
            values.addAll(fields.values());
        }
        for (Object value : values) {
            if (value != null && (!(value instanceof String) || !value.equals(protect(value, policy)))) {
                return false;
            }
        }
        return true;
    }

    static int utf8JsonBytes(Object value) throws Exception {
        return JSON.writeValueAsBytes(value).length;
    }

    static String asciiLower(String value) {
        StringBuilder out = null;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch >= 'A' && ch <= 'Z') {
                if (out == null) {
                    out = new StringBuilder(value.length());
                    out.append(value, 0, index);
                }
                out.append((char) (ch + 32));
            } else if (out != null) {
                out.append(ch);
            }
        }
        return out == null ? value : out.toString();
    }

    static final class CompiledPolicy {
        private final Set<String> keys;
        private final Pattern assignment;
        private final Set<String> exactKeys;
        private final Pattern exactAssignment;

        private CompiledPolicy(List<String> additional, List<String> exact) {
            this.assignment = additional.isEmpty() ? DEFAULT_ASSIGNMENT : assignmentPattern(assignmentFields(additional));
            Set<String> broadKeys = new HashSet<>();
            for (String field : FIELDS) {
                broadKeys.add(canonical(field));
            }
            for (String field : additional) {
                broadKeys.add(canonical(field));
            }
            this.keys = Set.copyOf(broadKeys);
            if (exact.isEmpty()) {
                this.exactKeys = Set.of();
                this.exactAssignment = null;
            } else {
                Set<String> normalizedExact = new HashSet<>(exact.size());
                for (String field : exact) {
                    normalizedExact.add(asciiLower(field));
                }
                this.exactKeys = Set.copyOf(normalizedExact);
                this.exactAssignment = exactAssignmentPattern(exact);
            }
        }
    }

    private static final class Work {
        private int nodes;
        private int bytes;
        private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        private final CompiledPolicy policy;

        private Work(CompiledPolicy policy) {
            this.policy = policy;
        }
    }

    private static List<String> assignmentFields(List<String> additional) {
        List<String> fields = new ArrayList<>(FIELDS);
        fields.addAll(additional.stream().sorted().toList());
        return fields;
    }

    private static Pattern assignmentPattern(List<String> fields) {
        String labels = fields.stream().map(Pattern::quote).collect(java.util.stream.Collectors.joining("|"));
        return Pattern.compile("\\b(" + labels + ")\\b([\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s&,;]+)", Pattern.CASE_INSENSITIVE);
    }

    private static Pattern exactAssignmentPattern(List<String> exact) {
        String labels = exact.stream()
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("|"));
        // Non-identifier boundaries redact `tag=secret` while preserving `tag_name=script` and `x-tag-name=script`.
        return Pattern.compile(
                "(?<![A-Za-z0-9_-])(" + labels + ")(?![A-Za-z0-9_-])([\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s&,;]+)",
                Pattern.CASE_INSENSITIVE
        );
    }

    private static String canonical(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static boolean sensitiveKey(String key, Work work) {
        String[] parts = key.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        for (int start = 0; start < parts.length; start++) {
            StringBuilder joined = new StringBuilder();
            for (int end = start; end < parts.length; end++) {
                joined.append(parts[end]);
                if (work.policy.keys.contains(joined.toString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean exactSensitiveKey(String key, Work work) {
        return !work.policy.exactKeys.isEmpty() && work.policy.exactKeys.contains(asciiLower(key.trim()));
    }

    private static void count(Work work, String text) {
        work.bytes += text.getBytes(StandardCharsets.UTF_8).length;
        if (work.bytes > MAX_BYTES) {
            throw new IllegalArgumentException("budget_exceeded");
        }
    }

    private static Object visit(Object value, Work work, int depth, boolean structured) throws Exception {
        if (++work.nodes > 4096) {
            throw new IllegalArgumentException("budget_exceeded");
        }
        if (depth > 16) {
            return REDACTED;
        }
        if (value instanceof String text) {
            if (text.length() > 16 * 1024 || text.getBytes(StandardCharsets.UTF_8).length > 16 * 1024) {
                return REDACTED;
            }
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
            if (!work.seen.add(map)) {
                return "[Circular]";
            }
            try {
                if (map.size() > 256) {
                    return REDACTED;
                }
                Map<String, Object> output = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("unsafe_input");
                    }
                    if (key.length() > 128) {
                        continue;
                    }
                    count(work, key);
                    if (!scrubText(key, work).equals(key)) {
                        continue;
                    }
                    boolean redactKey = sensitiveKey(key, work) || exactSensitiveKey(key, work);
                    output.put(key, redactKey ? REDACTED : visit(entry.getValue(), work, depth + 1, structured));
                }
                return output;
            } finally {
                work.seen.remove(map);
            }
        }
        if (value instanceof List<?> list) {
            if (!work.seen.add(list)) {
                return "[Circular]";
            }
            try {
                if (list.size() > 256) {
                    return REDACTED;
                }
                List<Object> output = new ArrayList<>(list.size());
                for (Object item : list) {
                    output.add(visit(item, work, depth + 1, structured));
                }
                return output;
            } finally {
                work.seen.remove(list);
            }
        }
        if (value == null || value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof Double number && Double.isFinite(number)) {
            return value;
        }
        if (value instanceof Float number && Float.isFinite(number)) {
            return value;
        }
        throw new IllegalArgumentException("unsafe_input");
    }

    private static String scrubText(String text, Work work) {
        return scrubText(text, work, true);
    }

    private static String scrubText(String text, Work work, boolean scanUrls) {
        if (PEM_START.matcher(text).find() && !PEM.matcher(text).find()) {
            return REDACTED;
        }
        String output = ENCODED_LABEL.matcher(text).find() ? URLDecoder.decode(text, StandardCharsets.UTF_8) : text;
        output = PEM.matcher(output).replaceAll(REDACTED);
        output = HEADER.matcher(output).replaceAll("$1: [REDACTED]");
        output = BEARER.matcher(output).replaceAll("$1 [REDACTED]");
        output = TOKEN.matcher(output).replaceAll(REDACTED);
        output = work.policy.assignment.matcher(output).replaceAll("$1$2[REDACTED]");
        if (work.policy.exactAssignment != null) {
            output = work.policy.exactAssignment.matcher(output).replaceAll("$1$2[REDACTED]");
        }
        Matcher cards = CARD.matcher(output);
        StringBuffer cardOutput = new StringBuffer();
        while (cards.find()) {
            cards.appendReplacement(cardOutput, validCard(cards.group()) ? REDACTED : Matcher.quoteReplacement(cards.group()));
        }
        cards.appendTail(cardOutput);
        if (!scanUrls) {
            return cardOutput.toString();
        }
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

    private static boolean validCard(String value) {
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() < 13 || digits.length() > 19 || digits.matches("([0-9])\\1+")) {
            return false;
        }
        int sum = 0;
        for (int index = digits.length() - 1; index >= 0; index--) {
            int digit = digits.charAt(index) - '0';
            if ((digits.length() - index) % 2 == 0) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
        }
        return sum % 10 == 0;
    }

    private static String scrubUrl(String raw, Work work) {
        try {
            URI uri = URI.create(raw);
            if (uri.getHost() == null) {
                return REDACTED;
            }
            StringBuilder output = new StringBuilder(uri.getScheme()).append("://");
            if (uri.getRawUserInfo() != null) {
                output.append("REDACTED@");
            }
            output.append(uri.getHost());
            if (uri.getPort() != -1) {
                output.append(':').append(uri.getPort());
            }
            output.append(uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
            if (uri.getRawQuery() != null) {
                List<String> pairs = new ArrayList<>();
                for (String pair : uri.getRawQuery().split("&", -1)) {
                    String[] parts = pair.split("=", 2);
                    String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                    String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                    if (key.length() > 128 || !scrubText(key, work, false).equals(key)) {
                        continue;
                    }
                    if (sensitiveKey(key, work) || exactSensitiveKey(key, work) || !scrubText(value, work, false).equals(value)) {
                        value = REDACTED;
                    }
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
