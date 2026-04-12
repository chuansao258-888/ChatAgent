package com.yulong.chatagent;

import com.yulong.chatagent.agent.prompt.PromptLoader;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Shared test helper that creates a real PromptLoader reading from classpath:prompts/.
 * Uses the actual .md files in src/main/resources/prompts/ so tests verify real content.
 */
public final class TestPromptLoader {

    private TestPromptLoader() {
    }

    public static PromptLoader create() {
        return new PromptLoader(new DefaultResourceLoader());
    }
}
