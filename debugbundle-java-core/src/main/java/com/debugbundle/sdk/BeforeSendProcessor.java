package com.debugbundle.sdk;

import java.util.Map;

/** Canonical owned hook results; unavailable validation never undoes app redaction. */
final class BeforeSendProcessor {
    private BeforeSendProcessor() { }

    static Map<String, Object> apply(Map<String, Object> event, DebugBundleBeforeSend hook) {
        if (hook == null) return event;
        try {
            Map<String, Object> result = hook.apply(OwnedJson.copyEvent(event));
            if (result == null) return null;
            Map<String, Object> owned = OwnedJson.copyEvent(result);
            return validOwned(owned) ? owned : event;
        } catch (JsonValidationBudgetExceeded ignored) {
            return null;
        } catch (Throwable ignored) {
            return event;
        }
    }

    static boolean isValid(Map<String, Object> event) {
        try { return validOwned(OwnedJson.copyEvent(event)); }
        catch (Throwable ignored) { return false; }
    }

    private static boolean validOwned(Map<String, Object> event) {
        if (!CanonicalEventSchema.acceptsOwned(event)) return false;
        if ("frontend_exception".equals(event.get("event_type"))) {
            Map<?, ?> payload = (Map<?, ?>) event.get("payload");
            boolean mobile = java.util.List.of("@debugbundle/sdk-android", "@debugbundle/sdk-swift",
                    "@debugbundle/sdk-react-native").contains(event.get("sdk_name"));
            if (!(payload.get(mobile ? "device" : "browser") instanceof Map<?, ?>)) return false;
        }
        return EvidenceWire.validOwnedEvent(event);
    }
}
