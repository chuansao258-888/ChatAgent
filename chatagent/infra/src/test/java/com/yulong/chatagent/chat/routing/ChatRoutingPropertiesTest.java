package com.yulong.chatagent.chat.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatRoutingPropertiesTest {

    @Test
    void shouldAcceptDocumentedTimingDefaults() {
        assertThatCode(new ChatRoutingProperties()::validateTimings)
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectNonPositiveCircuitTimingsAndThreshold() {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.setFirstPacketTimeoutSeconds(0);
        assertThatThrownBy(properties::validateTimings)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("first-packet-timeout-seconds");

        properties.setFirstPacketTimeoutSeconds(1);
        properties.getHealth().setFailureThreshold(0);
        assertThatThrownBy(properties::validateTimings)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("health.failure-threshold");

        properties.getHealth().setFailureThreshold(1);
        properties.getHealth().setOpenDurationMs(0L);
        assertThatThrownBy(properties::validateTimings)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("health.open-duration-ms");

        properties.getHealth().setOpenDurationMs(1L);
        properties.getHealth().setHalfOpenFlightTimeoutMs(0L);
        assertThatThrownBy(properties::validateTimings)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("health.half-open-flight-timeout-ms");
    }

    @Test
    void shouldRejectHalfOpenTimeoutShorterThanFirstPacketTimeout() {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.setFirstPacketTimeoutSeconds(10);
        properties.getHealth().setHalfOpenFlightTimeoutMs(9_999L);

        assertThatThrownBy(properties::validateTimings)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be shorter");
    }
}
