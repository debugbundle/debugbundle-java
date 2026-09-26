package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuntimeFactsTest {
    @Test
    @SuppressWarnings("unchecked")
    void emitsCanonicalRuntimeMemoryAndNamespacesJvmSpecificFacts() {
        Map<String, Object> facts = RuntimeFacts.capture();

        assertThat(facts).doesNotContainKey("jvm_name");
        assertThat(facts.get("memory")).isInstanceOf(Map.class);
        Map<String, Object> memory = (Map<String, Object>) facts.get("memory");
        assertThat(memory).containsOnlyKeys("rss", "heap_total", "heap_used", "external", "peak");
        assertThat(memory.get("rss")).isNull();
        assertThat(memory.get("external")).isNull();
        assertThat(memory.get("peak")).isNull();
        assertThat(memory.get("heap_total")).isInstanceOf(Long.class);
        assertThat(memory.get("heap_used")).isInstanceOf(Long.class);
        assertThat(((Long) memory.get("heap_total"))).isNotNegative();
        assertThat(((Long) memory.get("heap_used")))
                .isNotNegative()
                .isLessThanOrEqualTo((Long) memory.get("heap_total"));

        assertThat(facts.get("framework_extras")).isInstanceOf(Map.class);
        Map<String, Object> extras = (Map<String, Object>) facts.get("framework_extras");
        assertThat(extras).containsOnlyKeys("jvm_name", "jvm_max_bytes");
        assertThat(extras.get("jvm_name")).isInstanceOf(String.class);
        assertThat((String) extras.get("jvm_name")).isNotBlank();
        assertThat(extras.get("jvm_max_bytes")).isInstanceOf(Long.class);
        assertThat(((Long) extras.get("jvm_max_bytes"))).isNotNegative();
    }

    @Test
    @SuppressWarnings("unchecked")
    void exceptionFactoryOutputPassesBeforeSendValidationWithoutCompatibility() {
        EventFactory factory = new EventFactory(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("test")
                        .build(),
                Set.of(),
                new LinkedHashMap<String, List<Map<String, Object>>>(),
                () -> 1_773_446_400_000L
        );

        Map<String, Object> event = factory.buildExceptionEvent(
                new IllegalStateException("failed"),
                Map.of()
        );
        Map<String, Object> accepted = BeforeSendProcessor.apply(event, value -> value);

        assertThat(accepted).isNotNull().isNotSameAs(event);
        assertThat(accepted).containsEntry("sdk_version", "3.0.1");
        Map<String, Object> payload = (Map<String, Object>) accepted.get("payload");
        Map<String, Object> runtime = (Map<String, Object>) payload.get("runtime");
        assertThat(runtime).doesNotContainKey("jvm_name");
        assertThat((Map<String, Object>) runtime.get("memory"))
                .containsOnlyKeys("rss", "heap_total", "heap_used", "external", "peak");
    }
}
