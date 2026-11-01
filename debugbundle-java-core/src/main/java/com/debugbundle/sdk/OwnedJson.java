package com.debugbundle.sdk;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Native JSON ownership without application serializers or numeric accessors.
 * Standard read-only JDK views retain compatibility on the existing hook worker;
 * a view's delegated callbacks share that worker's existing callback limits. */
final class OwnedJson {
    private static final Set<Class<?>> MAP_TYPES = Set.copyOf(List.of(
            HashMap.class, LinkedHashMap.class, TreeMap.class, IdentityHashMap.class,
            Map.of().getClass(), Map.of("a", 1).getClass(), Map.of("a", 1, "b", 2).getClass(),
            Collections.emptyMap().getClass(), Collections.singletonMap("a", 1).getClass()));
    private static final Set<Class<?>> LIST_TYPES = Set.copyOf(List.of(
            ArrayList.class, LinkedList.class, Arrays.asList(1).getClass(), List.of().getClass(),
            List.of(1).getClass(), List.of(1, 2, 3).getClass(), Collections.emptyList().getClass(),
            Collections.singletonList(1).getClass()));
    private static final Set<Class<?>> MAP_VIEWS = Set.copyOf(List.of(
            Collections.unmodifiableMap(new HashMap<>()).getClass(),
            Collections.unmodifiableSortedMap(new TreeMap<>()).getClass(),
            Collections.unmodifiableNavigableMap(new TreeMap<>()).getClass()));
    private static final Set<Class<?>> LIST_VIEWS = Set.copyOf(List.of(
            Collections.unmodifiableList(new ArrayList<>()).getClass(),
            Collections.unmodifiableList(new LinkedList<>()).getClass()));

    private OwnedJson() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> copyEvent(Map<String, Object> event) {
        return (Map<String, Object>) copy(event, new Budget(), 0);
    }

    private static final class Budget {
        int nodes = 4096;
        int bytes = 8 * 1024 * 1024;
    }

    private static Object copy(Object value, Budget budget, int depth) {
        if (--budget.nodes < 0 || depth > 16 || budget.bytes < 0) throw new JsonValidationBudgetExceeded();
        if (value == null || value instanceof Boolean) {
            budget.bytes -= 5;
            return value;
        }
        if (value instanceof String text) {
            if (text.length() > budget.bytes) throw new JsonValidationBudgetExceeded();
            budget.bytes -= 2;
            for (int i = 0; i < text.length(); i++) {
                char item = text.charAt(i);
                if (item < 0x80) budget.bytes--;
                else if (item < 0x800) budget.bytes -= 2;
                else if (Character.isHighSurrogate(item) && i + 1 < text.length()
                        && Character.isLowSurrogate(text.charAt(i + 1))) {
                    budget.bytes -= 4;
                    i++;
                } else budget.bytes -= 3;
                if (budget.bytes < 0) throw new JsonValidationBudgetExceeded();
            }
            return text;
        }
        if (value instanceof Number) {
            Class<?> type = value.getClass();
            boolean primitive = type == Integer.class || type == Long.class || type == Double.class
                    || type == Float.class || type == Byte.class || type == Short.class;
            if (!primitive && type != BigInteger.class && type != BigDecimal.class)
                throw new JsonValidationBudgetExceeded();
            if (value instanceof BigInteger integer && integer.bitLength() > 1024)
                throw new JsonValidationBudgetExceeded();
            if (value instanceof BigDecimal decimal && decimal.unscaledValue().bitLength() > 4096)
                throw new JsonValidationBudgetExceeded();
            if (!Double.isFinite(((Number) value).doubleValue())) throw new IllegalArgumentException("non-finite JSON");
            budget.bytes -= 32;
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            // Direct foreign carriers are not inspected. Read-only JDK views
            // are a supported legacy hook idiom and stay on the hook worker.
            boolean view = MAP_VIEWS.contains(value.getClass());
            if (!MAP_TYPES.contains(value.getClass()) && !view) throw new JsonValidationBudgetExceeded();
            try {
                if ((long) map.size() * 2 > budget.nodes) throw new JsonValidationBudgetExceeded();
                budget.bytes -= 2 + map.size() * 2;
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> item : map.entrySet()) {
                    if (!(item.getKey() instanceof String key)) throw new IllegalArgumentException("non-string JSON key");
                    copy(key, budget, depth + 1);
                    result.put(key, copy(item.getValue(), budget, depth + 1));
                }
                return result;
            } catch (Throwable error) {
                if (view || error instanceof java.util.ConcurrentModificationException)
                    throw new JsonValidationBudgetExceeded();
                throw error;
            }
        }
        if (value instanceof List<?> list) {
            boolean view = LIST_VIEWS.contains(value.getClass());
            if (!LIST_TYPES.contains(value.getClass()) && !view)
                throw new JsonValidationBudgetExceeded();
            try {
                if (list.size() > budget.nodes) throw new JsonValidationBudgetExceeded();
                budget.bytes -= 2 + list.size();
                List<Object> result = new ArrayList<>(list.size());
                for (Object child : list) result.add(copy(child, budget, depth + 1));
                return result;
            } catch (Throwable error) {
                if (view || error instanceof java.util.ConcurrentModificationException)
                    throw new JsonValidationBudgetExceeded();
                throw error;
            }
        }
        throw new IllegalArgumentException("non-native JSON value");
    }
}
