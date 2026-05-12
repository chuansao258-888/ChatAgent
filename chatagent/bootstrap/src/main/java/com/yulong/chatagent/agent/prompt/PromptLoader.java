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
 * 集中式 Prompt 模板加载器。
 * <p>
 * 从 {@code classpath:prompts/} 读取 Markdown 模板并缓存到内存，
 * 同时支持 {@code {{variableName}}} 形式的变量替换。
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
     * 按相对路径加载 Prompt 模板。
     * <p>
     * 首次读取后会缓存原始模板文本，后续调用不再访问 classpath 资源。
     *
     * @param templatePath 相对路径，例如 {@code "agent/default-system-prompt.md"}
     * @return 模板内容
     * @throws IllegalStateException 模板不存在或读取失败
     */
    public String load(String templatePath) {
        return cache.computeIfAbsent(templatePath, this::readTemplate);
    }

    /**
     * 加载并渲染带变量的 Prompt 模板。
     * 变量使用 {@code {{variableName}}} 语法。
     * <p>
     * 缓存的是原始模板，每次 render 都会重新做变量替换，避免不同 turn 的变量串用。
     *
     * @param templatePath 模板相对路径
     * @param variables 变量名到替换值的映射
     * @return 替换后的 Prompt 文本
     * @throws IllegalStateException 模板缺失或变量未提供
     */
    public String render(String templatePath, Map<String, String> variables) {
        String template = load(templatePath);
        return substituteVariables(templatePath, template, variables);
    }

    private String readTemplate(String templatePath) {
        // 模板统一放在 resources/prompts 下，代码中只传相对路径。
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
        // 使用 Matcher.appendReplacement，避免替换值里的 $ 或 \ 被当成正则替换语法。
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
