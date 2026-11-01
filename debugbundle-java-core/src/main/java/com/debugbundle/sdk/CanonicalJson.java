package com.debugbundle.sdk;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** Byte accounting for owned metadata in the accepting JSON.stringify form.
 * This is separate from final transport bytes, which depend on its serializer. */
final class CanonicalJson {
    private static final int OVER = 32769;

    private CanonicalJson() { }

    private static final class Budget {
        int remaining = OVER - 1;
        int nodes = 4096;
        void use(int bytes) {
            remaining -= bytes;
            if (remaining < 0) throw new JsonValidationBudgetExceeded();
        }
    }

    static int bytes(Object value) {
        Budget budget = new Budget();
        try {
            count(value, budget, 0);
            return OVER - 1 - budget.remaining;
        } catch (RuntimeException ignored) {
            return OVER;
        }
    }

    private static void count(Object value, Budget budget, int depth) {
        if (--budget.nodes < 0 || depth > 16) throw new JsonValidationBudgetExceeded();
        if (value == null) { budget.use(4); return; }
        if (value instanceof Boolean flag) { budget.use(flag ? 4 : 5); return; }
        if (value instanceof String text) { string(text, budget); return; }
        if (value instanceof Number number) { budget.use(numberText(number).length()); return; }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 256) throw new JsonValidationBudgetExceeded();
            budget.use(2 + Math.max(0, map.size() - 1));
            for (Map.Entry<?, ?> item : map.entrySet()) {
                if (!(item.getKey() instanceof String key)) throw new IllegalArgumentException("non-JSON key");
                count(key, budget, depth + 1);
                budget.use(1);
                count(item.getValue(), budget, depth + 1);
            }
            return;
        }
        if (value instanceof List<?> list) {
            if (list.size() > 256) throw new JsonValidationBudgetExceeded();
            budget.use(2 + Math.max(0, list.size() - 1));
            for (Object child : list) count(child, budget, depth + 1);
            return;
        }
        throw new IllegalArgumentException("non-JSON value");
    }

    private static void string(String value, Budget budget) {
        if (value.length() > budget.remaining) throw new JsonValidationBudgetExceeded();
        budget.use(2);
        for (int i = 0; i < value.length(); i++) {
            char item = value.charAt(i);
            if (item == '"' || item == '\\' || item == '\b' || item == '\f'
                    || item == '\n' || item == '\r' || item == '\t') budget.use(2);
            else if (item < 0x20) budget.use(6);
            else if (Character.isHighSurrogate(item) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) { budget.use(4); i++; }
            else if (Character.isSurrogate(item)) budget.use(6);
            else budget.use(item < 0x80 ? 1 : item < 0x800 ? 2 : 3);
        }
    }

    static boolean utf8Within(String value, int maximum) {
        if (value.length() > maximum) return false;
        int bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char item = value.charAt(i);
            if (Character.isHighSurrogate(item) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) { bytes += 4; i++; }
            else bytes += item < 0x80 ? 1 : item < 0x800 ? 2 : 3;
            if (bytes > maximum) return false;
        }
        return true;
    }

    private static String numberText(Number value) {
        Class<?> type = value.getClass();
        if (type != Integer.class && type != Long.class && type != Short.class && type != Byte.class
                && type != Double.class && type != Float.class && type != BigInteger.class && type != BigDecimal.class)
            throw new IllegalArgumentException("non-native number");
        if (value instanceof BigInteger integer && integer.bitLength() > 1024) throw new JsonValidationBudgetExceeded();
        if (value instanceof BigDecimal decimal && decimal.unscaledValue().bitLength() > 4096) throw new JsonValidationBudgetExceeded();
        double number = value instanceof Float floating ? Double.parseDouble(Float.toString(floating)) : value.doubleValue();
        if (!Double.isFinite(number)) throw new IllegalArgumentException("non-finite JSON");
        if (number == 0) return "0";

        // Work from the exact binary value rather than Java 17's older printed
        // decimal. The shortest nearest decimal that round-trips also handles
        // subnormals and long integers in the accepting JavaScript number domain.
        double absolute = Math.abs(number);
        BigDecimal exact = new BigDecimal(absolute);
        BigDecimal shortest = exact;
        for (int precision = 1; precision <= 17; precision++) {
            BigDecimal candidate = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
            if (Double.doubleToLongBits(Double.parseDouble(candidate.toString())) == Double.doubleToLongBits(absolute)) {
                shortest = candidate.stripTrailingZeros();
                break;
            }
        }
        String digits = shortest.unscaledValue().toString();
        int exponent = digits.length() - shortest.scale() - 1;
        String magnitude = exponent >= -6 && exponent < 21 ? shortest.toPlainString()
                : digits.substring(0, 1) + (digits.length() > 1 ? "." + digits.substring(1) : "")
                    + "e" + (exponent >= 0 ? "+" : "") + exponent;
        return (number < 0 ? "-" : "") + magnitude;
    }
}
