package com.debugbundle.sdk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared internal protocol primitives for maintained adapters and relays. */
public final class EvidenceProtocol {
    public static final class State {
        private final EvidenceDeliveryState state;
        public State(Object pin) { state = new EvidenceDeliveryState(pin); }
        public Map<String, Object> descriptor() { return EvidenceDeliveryState.descriptor(Map.of("version", 1,
                "features", EvidenceWire.FEATURES.stream().filter(state::supports).toList())); }
        public boolean observe(Object descriptor) { return state.observe(descriptor); }
        public Map<String, Object> filter(Map<String, Object> event) { return state.filter(event, true); }
    }
    public record Ack(boolean success, boolean complete, boolean lost, int accepted,
            List<Integer> invalidIndices, Map<String, Object> capabilities) { }
    private EvidenceProtocol() { }
    public static Map<String, Object> descriptor(Object value) { return EvidenceDeliveryState.descriptor(value); }
    public static Map<String, Object> parsePin(String value) { return EvidenceDeliveryState.parsePin(value); }
    public static boolean valid(Map<String, Object> event) { return BeforeSendProcessor.isValid(event); }
    public static Map<String, Object> fit(Map<String, Object> event) { return IngestionWire.fit(event); }
    public static String url(String endpoint) { return EvidenceDeliveryState.url(endpoint); }
    public static List<List<Map<String, Object>>> chunks(List<Map<String, Object>> events) {
        List<List<Map<String, Object>>> chunks = new ArrayList<>();
        int offset = 0;
        while (offset < events.size()) {
            int length = IngestionWire.prefix(events.subList(offset, events.size()));
            if (length == 0) throw new IllegalArgumentException("event exceeds native request budget");
            chunks.add(List.copyOf(events.subList(offset, offset + length))); offset += length;
        }
        return chunks;
    }
    public static Ack acknowledge(String body, int count, boolean required, State state) {
        var ack = IngestionAcknowledgementDecision.decide(body, count, required);
        boolean lost = state.state.observeBody(body, required, ack.kind() == IngestionAcknowledgementDecision.Kind.ACKNOWLEDGED);
        List<Integer> invalid = ack.terminalErrors().stream().filter(error -> error.reason().equals("invalid_event"))
                .map(IngestionAcknowledgementDecision.IngestionError::index).toList();
        return new Ack(ack.kind() != IngestionAcknowledgementDecision.Kind.PROTOCOL_FAILURE,
                ack.kind() == IngestionAcknowledgementDecision.Kind.ACKNOWLEDGED && ack.accepted() == count,
                lost, ack.accepted(), invalid, state.descriptor());
    }
    public static Map<String, Object> intersection(List<Map<String, Object>> descriptors) {
        if (descriptors.isEmpty() || descriptors.size() > 8) return descriptor(null);
        List<String> features = new ArrayList<>(EvidenceWire.FEATURES);
        for (var entry : descriptors) features.retainAll((List<?>) descriptor(entry).get("features"));
        return descriptor(Map.of("version", 1, "features", features));
    }
}
