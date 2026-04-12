package com.yulong.chatagent.agent.prompt;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Centralized prompt template loader.
 * <p>
 * Reads prompt files from {@code classpath:prompts/}, caches them in memory,
 * and provides variable substitution via {@code {{variableName}}} syntax.
 */
@Component
@Slf4j
public class PromptLoader {

    private static final String CLASSPATH_PREFIX = "classpath:prompts/";
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    private final ResourceLoader resourceLoader;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public PromptLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void logStartup() {
        log.info("PromptLoader initialized — templates will be loaded lazily from classpath:prompts/");
    }

    /**
     * Load a prompt template by its relative path under {@code classpath:prompts/}.
     * The raw template string is cached after first load.
     *
     * @param templatePath relative path, e.g. {@code "agent/default-system-prompt.md"}
     * @return the template content
     * @throws IllegalStateException if the template file cannot be found or read
     */
    public String load(String templatePath) {
        return cache.computeIfAbsent(templatePath, this::readTemplate);
    }

    /**
     * Load and render a prompt template with variable substitution.
     * Variables use {@code {{variableName}}} syntax.
     * <p>
     * The raw template is cached; substitution is applied on every call.
     *
     * @param templatePath relative path under {@code classpath:prompts/}
     * @param variables    map of variable names to replacement values
     * @return the rendered prompt with all variables substituted
     * @throws IllegalStateException if the template is missing or a variable has no mapping
     */
    public String render(String templatePath, Map<String, String> variables) {
        String template = load(templatePath);
        return substituteVariables(templatePath, template, variables);
    }

    private String readTemplate(String templatePath) {
        String location = CLASSPATH_PREFIX + templatePath;
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("Prompt template not found: " + location);
        }
        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            log.debug("Loaded prompt template: {} ({} chars)", templatePath, content.length());
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt template: " + location, e);
        }
    }

    private String substituteVariables(String templatePath, String template, Map<String, String> variables) {
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer(template.length() + 256);
        while (matcher.find()) {
            String varName = matcher.group(1);
            String replacement = variables.get(varName);
            if (replacement == null) {
                throw new IllegalStateException(
                        "Missing variable '" + varName + "' in template '" + templatePath + "'");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
