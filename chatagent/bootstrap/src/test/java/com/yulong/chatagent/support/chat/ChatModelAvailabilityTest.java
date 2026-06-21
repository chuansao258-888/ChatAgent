package com.yulong.chatagent.support.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatModelAvailabilityTest {

    @Test
    void shouldRequireAgentChatProviderKey() {
        assertThat(new ChatModelAvailability("", "").hasConfiguredProvider()).isFalse();
        assertThat(new ChatModelAvailability("deepseek-key", "").hasConfiguredProvider()).isTrue();
        assertThat(new ChatModelAvailability("", "zai-key").hasConfiguredProvider()).isTrue();
    }
}
