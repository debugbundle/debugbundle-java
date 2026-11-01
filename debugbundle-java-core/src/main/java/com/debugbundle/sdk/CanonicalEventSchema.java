package com.debugbundle.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Closed Draft 7 interpreter for owned native JSON and local vendored references. */
final class CanonicalEventSchema {
    private static final Set<String> KEYWORDS = Set.of("$schema", "$id", "$comment", "$ref", "type", "anyOf",
            "const", "enum", "properties", "required", "additionalProperties", "items", "minimum", "maximum",
            "exclusiveMinimum", "minLength", "maxLength", "minItems", "maxItems", "format", "pattern");
    private static final Set<String> TYPES = Set.of("null", "boolean", "number", "integer", "string", "array", "object");
    private static final Pattern UUID = Pattern.compile("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");
    private static final Pattern TIME = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]+)?Z");
    private final Map<String, Object> root;
    private final Map<String, Object> references = new HashMap<>();
    private final Map<String, Pattern> patterns = new HashMap<>();

    private static final class Holder {
        static final CanonicalEventSchema ENGINE = load();
    }

    private static CanonicalEventSchema load() {
        try (InputStream stream = CanonicalEventSchema.class.getResourceAsStream("event-envelope.schema.json")) {
            if (stream == null) return null;
            Map<String, Object> schema = new ObjectMapper().readValue(stream, new TypeReference<>() { });
            return new CanonicalEventSchema(schema);
        } catch (Throwable ignored) {
            return null;
        }
    }

    CanonicalEventSchema(Map<String, Object> schema) {
        root = schema;
        compile(schema);
    }

    static boolean acceptsOwned(Map<String, Object> event) {
        return Holder.ENGINE != null && Holder.ENGINE.accepts(event);
    }

    boolean accepts(Object value) {
        return check(root, value, new int[] {100_000}, 0);
    }

    private void compile(Object schema) {
        if (schema instanceof Boolean) return;
        if (!(schema instanceof Map<?, ?> node) || !KEYWORDS.containsAll(node.keySet()))
            throw new IllegalArgumentException("unsupported schema keyword");
        for (Object kind : alternatives(node.get("type")))
            if (!TYPES.contains(kind)) throw new IllegalArgumentException("unsupported schema type");
        Object format = node.get("format");
        if (format != null && !format.equals("uuid") && !format.equals("date-time"))
            throw new IllegalArgumentException("unsupported schema format");
        if (node.get("pattern") instanceof String pattern) patterns.put(pattern, Pattern.compile(pattern));
        if (node.containsKey("$ref")) resolve(node.get("$ref"));
        if (node.get("properties") instanceof Map<?, ?> fields) fields.values().forEach(this::compile);
        if (node.get("anyOf") instanceof List<?> choices) choices.forEach(this::compile);
        for (String field : List.of("items", "additionalProperties"))
            if (node.containsKey(field)) compile(node.get(field));
    }

    private Object resolve(Object reference) {
        if (!(reference instanceof String ref) || !ref.startsWith("#/"))
            throw new IllegalArgumentException("external schema reference");
        if (references.containsKey(ref)) return references.get(ref);
        Object node = root;
        for (String part : ref.substring(2).split("/")) {
            String key = part.replace("~1", "/").replace("~0", "~");
            node = node instanceof Map<?, ?> map ? map.get(key)
                    : node instanceof List<?> list ? list.get(Integer.parseInt(key)) : null;
            if (node == null) throw new IllegalArgumentException("missing schema reference");
        }
        references.put(ref, node);
        return node;
    }

    private static List<?> alternatives(Object value) {
        return value == null ? List.of() : value instanceof List<?> list ? list : List.of(value);
    }

    private boolean check(Object schema, Object value, int[] budget, int depth) {
        if (--budget[0] < 0 || depth > 64) throw new JsonValidationBudgetExceeded();
        if (schema instanceof Boolean allowed) return allowed;
        if (!(schema instanceof Map<?, ?> node)) return false;
        if (node.containsKey("$ref")) return check(resolve(node.get("$ref")), value, budget, depth + 1);
        if (node.get("anyOf") instanceof List<?> choices) {
            boolean found = false;
            for (Object child : choices) if (check(child, value, budget, depth + 1)) { found = true; break; }
            if (!found) return false;
        }
        if (node.containsKey("type") && alternatives(node.get("type")).stream().noneMatch(kind -> type(value, kind))) return false;
        if (node.containsKey("const") && !same(value, node.get("const"))) return false;
        if (node.get("enum") instanceof List<?> choices && choices.stream().noneMatch(choice -> same(value, choice))) return false;
        if (value instanceof Map<?, ?> map) {
            if (node.get("required") instanceof List<?> required && !map.keySet().containsAll(required)) return false;
            Map<?, ?> properties = node.get("properties") instanceof Map<?, ?> fields ? fields : Map.of();
            Object additional = node.containsKey("additionalProperties") ? node.get("additionalProperties") : true;
            for (Map.Entry<?, ?> child : map.entrySet()) {
                Object childSchema = properties.containsKey(child.getKey()) ? properties.get(child.getKey()) : additional;
                if (!check(childSchema, child.getValue(), budget, depth + 1)) return false;
            }
        }
        if (value instanceof List<?> list) {
            if (node.get("minItems") instanceof Number min && list.size() < min.intValue()) return false;
            if (node.get("maxItems") instanceof Number max && list.size() > max.intValue()) return false;
            if (node.containsKey("items")) for (Object child : list)
                if (!check(node.get("items"), child, budget, depth + 1)) return false;
        }
        if (value instanceof String text) {
            if (node.get("minLength") instanceof Number min && text.length() < min.intValue()) return false;
            if (node.get("maxLength") instanceof Number max && text.length() > max.intValue()) return false;
            if (node.get("pattern") instanceof String pattern && !patterns.get(pattern).matcher(text).find()) return false;
            if ("uuid".equals(node.get("format")) && !UUID.matcher(text).matches()) return false;
            if ("date-time".equals(node.get("format")) && !timestamp(text)) return false;
        }
        if (value instanceof Number number) {
            double measured = number.doubleValue();
            if (node.get("minimum") instanceof Number min && measured < min.doubleValue()) return false;
            if (node.get("maximum") instanceof Number max && measured > max.doubleValue()) return false;
            if (node.get("exclusiveMinimum") instanceof Number min && measured <= min.doubleValue()) return false;
        }
        return true;
    }

    private static boolean type(Object value, Object kind) {
        return switch (String.valueOf(kind)) {
            case "null" -> value == null;
            case "boolean" -> value instanceof Boolean;
            case "string" -> value instanceof String;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "number" -> value instanceof Number number && Double.isFinite(number.doubleValue());
            case "integer" -> value instanceof Number number && Double.isFinite(number.doubleValue())
                    && number.doubleValue() == Math.floor(number.doubleValue());
            default -> false;
        };
    }

    private static boolean same(Object value, Object choice) {
        return value instanceof Number number && choice instanceof Number expected
                ? number.doubleValue() == expected.doubleValue() : java.util.Objects.equals(value, choice);
    }

    static boolean timestamp(String value) {
        if (!TIME.matcher(value).matches()) return false;
        try {
            LocalDate.of(Integer.parseInt(value.substring(0, 4)), Integer.parseInt(value.substring(5, 7)), Integer.parseInt(value.substring(8, 10)));
            return Integer.parseInt(value.substring(11, 13)) < 24 && Integer.parseInt(value.substring(14, 16)) < 60
                    && Integer.parseInt(value.substring(17, 19)) < 60;
        } catch (DateTimeException | NumberFormatException ignored) {
            return false;
        }
    }
}
