package com.debugbundle.sdk.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebugBundleBrowserRelayComplianceTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> EVENT_LIST_TYPE = new TypeReference<>() {};

    @Test
    void relayMatchesSharedComplianceFixtures(@TempDir Path tempDir) throws Exception {
        Map<String, Object> fixtures = OBJECT_MAPPER.readValue(Files.readString(fixturePath()), MAP_TYPE);

        for (Map<String, Object> fixtureCase : objectList(fixtures.get("cases"))) {
            String caseId = stringValue(fixtureCase.get("id"));
            Path caseDir = Files.createDirectories(tempDir.resolve(caseId));
            String kind = stringValue(fixtureCase.get("kind"));
            if ("sequence".equals(kind)) {
                assertSequenceCase(caseDir, fixtureCase);
            } else {
                assertSingleRequestCase(caseDir, fixtureCase);
            }
        }
    }

    private void assertSingleRequestCase(Path caseDir, Map<String, Object> fixtureCase) throws Exception {
        RecordingForwarder forwarder = new RecordingForwarder();
        DebugBundleBrowserRelay relay = new DebugBundleBrowserRelay(configFor(caseDir, objectMap(fixtureCase.get("relayOptions"))), forwarder);
        DebugBundleBrowserRelay.Response response = relay.handle(
                requestFrom(objectMap(fixtureCase.get("request"))),
                requestBody(objectMap(fixtureCase.get("request")))
        );

        assertExpectedResponse(response, objectMap(fixtureCase.get("expected")));
        assertExpectedEventFile(caseDir, fixtureCase);
        assertExpectedDeliveredMarker(caseDir, fixtureCase);
        assertExpectedForwardRequest(forwarder, fixtureCase);
    }

    private void assertSequenceCase(Path caseDir, Map<String, Object> fixtureCase) throws Exception {
        RecordingForwarder forwarder = new RecordingForwarder();
        AtomicLong currentMillis = new AtomicLong(0L);
        DebugBundleBrowserRelay relay = new DebugBundleBrowserRelay(
                configFor(caseDir, objectMap(fixtureCase.get("relayOptions"))),
                forwarder,
                () -> currentMillis.get() / 1000L
        );

        for (Map<String, Object> requestCase : objectList(fixtureCase.get("requests"))) {
            currentMillis.set(numberValue(requestCase.get("atMs")).longValue());
            DebugBundleBrowserRelay.Response response = relay.handle(
                    requestFrom(objectMap(requestCase.get("request"))),
                    requestBody(objectMap(requestCase.get("request")))
            );
            assertThat(response.status()).isEqualTo(numberValue(requestCase.get("expectedStatus")).intValue());
        }
    }

    private DebugBundleBrowserRelay.Config configFor(Path caseDir, Map<String, Object> relayOptions) {
        return new DebugBundleBrowserRelay.Config(
                stringOption(relayOptions, "projectToken"),
                stringOption(relayOptions, "endpoint"),
                stringOption(relayOptions, "projectMode"),
                caseDir.resolve("events").toString(),
                intOption(relayOptions, "rateLimitPerMinute", 60),
                booleanOption(relayOptions, "durableWrite", true),
                caseDir.resolve("spool").toString(),
                List.of(),
                stringOption(relayOptions, "service"),
                stringOption(relayOptions, "environment")
        );
    }

    private DebugBundleBrowserRelay.Request requestFrom(Map<String, Object> requestDefinition) {
        Map<String, Object> headers = objectMap(requestDefinition.get("headers"));
        return new DebugBundleBrowserRelay.Request(
                stringValue(requestDefinition.get("method")),
                headerValue(headers, "content-type"),
                stringValue(requestDefinition.get("ipAddress")),
                headerName -> headerValue(headers, headerName)
        );
    }

    private byte[] requestBody(Map<String, Object> requestDefinition) throws Exception {
        if (requestDefinition.containsKey("bodyJson")) {
            return OBJECT_MAPPER.writeValueAsBytes(requestDefinition.get("bodyJson"));
        }
        if (requestDefinition.containsKey("bodyText")) {
            return stringValue(requestDefinition.get("bodyText")).getBytes(StandardCharsets.UTF_8);
        }

        Map<String, Object> generator = objectMap(requestDefinition.get("bodyGenerator"));
        String repeatedCharacter = stringValue(generator.get("char"));
        int length = numberValue(generator.get("length")).intValue();
        return repeatedCharacter.repeat(length).getBytes(StandardCharsets.UTF_8);
    }

    private void assertExpectedResponse(DebugBundleBrowserRelay.Response response, Map<String, Object> expected) {
        assertThat(response.status()).isEqualTo(numberValue(expected.get("status")).intValue());
        if (expected.containsKey("accepted")) {
            assertThat(response.body()).containsEntry("accepted", numberValue(expected.get("accepted")).intValue());
        }
        if (expected.containsKey("rejected")) {
            assertThat(response.body()).containsEntry("rejected", numberValue(expected.get("rejected")).intValue());
        }
        if (expected.containsKey("errors")) {
            assertThat(response.body()).containsEntry("errors", expected.get("errors"));
        }
    }

    private void assertExpectedEventFile(Path caseDir, Map<String, Object> fixtureCase) throws Exception {
        if (!fixtureCase.containsKey("expectedEventFile")) {
            return;
        }

        List<Path> eventFiles = eventFiles(caseDir);
        assertThat(eventFiles).hasSize(1);
        List<Map<String, Object>> writtenEvents = OBJECT_MAPPER.readValue(Files.readString(eventFiles.get(0)), EVENT_LIST_TYPE);
        assertThat(writtenEvents).isEqualTo(fixtureCase.get("expectedEventFile"));
    }

    private void assertExpectedDeliveredMarker(Path caseDir, Map<String, Object> fixtureCase) throws Exception {
        if (!Boolean.TRUE.equals(fixtureCase.get("expectedDeliveredMarker"))) {
            return;
        }

        List<Path> markers;
        try (var files = Files.walk(caseDir)) {
            markers = files.filter(path -> path.getFileName().toString().endsWith(".delivered")).toList();
        }
        assertThat(markers).hasSize(1);
    }

    private void assertExpectedForwardRequest(RecordingForwarder forwarder, Map<String, Object> fixtureCase) {
        if (!fixtureCase.containsKey("expectedForwardRequest")) {
            return;
        }

        Map<String, Object> expectedForwardRequest = objectMap(fixtureCase.get("expectedForwardRequest"));
        assertThat(forwarder.forwardedBatches).hasSize(1);
        assertThat(forwarder.forwardedBatches.get(0)).isEqualTo(expectedForwardRequest.get("events"));
    }

    private List<Path> eventFiles(Path caseDir) throws Exception {
        try (var files = Files.walk(caseDir)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".events.json"))
                    .sorted()
                    .toList();
        }
    }

    private Path fixturePath() {
        List<Path> candidates = List.of(
                Path.of("..", "tests", "fixtures", "relay-compliance.json"),
                Path.of("tests", "fixtures", "relay-compliance.json")
        );
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Missing relay compliance fixture");
    }

    private String headerValue(Map<String, Object> headers, String requestedName) {
        String normalizedRequestedName = requestedName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(normalizedRequestedName)) {
                return stringValue(entry.getValue());
            }
        }
        return null;
    }

    private String stringOption(Map<String, Object> options, String key) {
        return options == null ? null : stringValue(options.get(key));
    }

    private int intOption(Map<String, Object> options, String key, int fallback) {
        return options == null || options.get(key) == null ? fallback : numberValue(options.get(key)).intValue();
    }

    private boolean booleanOption(Map<String, Object> options, String key, boolean fallback) {
        return options == null || options.get(key) == null ? fallback : Boolean.TRUE.equals(options.get(key));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> mapValue ? (Map<String, Object>) mapValue : null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objectList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private Number numberValue(Object value) {
        return (Number) value;
    }

    private String stringValue(Object value) {
        return value instanceof String stringValue ? stringValue : null;
    }

    private static final class RecordingForwarder implements DebugBundleBrowserRelay.RelayForwarder {
        private final List<List<Map<String, Object>>> forwardedBatches = new ArrayList<>();

        @Override
        public boolean forward(List<Map<String, Object>> acceptedEvents) {
            List<Map<String, Object>> copiedBatch = new ArrayList<>();
            for (Map<String, Object> acceptedEvent : acceptedEvents) {
                copiedBatch.add(new LinkedHashMap<>(acceptedEvent));
            }
            forwardedBatches.add(copiedBatch);
            return true;
        }
    }
}