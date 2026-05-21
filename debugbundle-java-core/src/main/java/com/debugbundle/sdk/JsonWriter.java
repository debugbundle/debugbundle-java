package com.debugbundle.sdk;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class JsonWriter {
    private JsonWriter() {
    }

    static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        appendValue(builder, value);
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
            return;
        }
        if (value instanceof String stringValue) {
            appendString(builder, stringValue);
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            builder.append(value);
            return;
        }
        if (value instanceof Map<?, ?> mapValue) {
            appendMap(builder, (Map<String, Object>) mapValue);
            return;
        }
        if (value instanceof List<?> listValue) {
            appendList(builder, listValue);
            return;
        }

        appendString(builder, String.valueOf(value));
    }

    private static void appendMap(StringBuilder builder, Map<String, Object> map) {
        builder.append('{');
        Iterator<Map.Entry<String, Object>> iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Object> entry = iterator.next();
            appendString(builder, entry.getKey());
            builder.append(':');
            appendValue(builder, entry.getValue());
            if (iterator.hasNext()) {
                builder.append(',');
            }
        }
        builder.append('}');
    }

    private static void appendList(StringBuilder builder, List<?> list) {
        builder.append('[');
        for (int index = 0; index < list.size(); index++) {
            appendValue(builder, list.get(index));
            if (index + 1 < list.size()) {
                builder.append(',');
            }
        }
        builder.append(']');
    }

    private static void appendString(StringBuilder builder, String value) {
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character <= 0x1F) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        builder.append('"');
    }
}

