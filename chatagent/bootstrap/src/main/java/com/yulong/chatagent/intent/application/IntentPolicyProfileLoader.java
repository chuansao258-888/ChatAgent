package com.yulong.chatagent.intent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Loads and validates immutable classpath intent-policy profiles. */
@Component
public class IntentPolicyProfileLoader {

    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final String RESOURCE_PATTERN = "classpath:intent-policy/intent-policy-%s.json";

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final IntentPolicyProperties properties;
    private final String configuredClassifierModel;
    private final Map<String, IntentPolicyProfile> cache = new ConcurrentHashMap<>();

    public IntentPolicyProfileLoader(ResourceLoader resourceLoader,
                                     ObjectMapper objectMapper,
                                     IntentPolicyProperties properties,
                                     @Value("${chatagent.intent.classifier-model:}") String configuredClassifierModel) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.configuredClassifierModel = configuredClassifierModel;
    }

    @PostConstruct
    void validateEnforceProfile() {
        if (properties.getMode() == IntentPolicyMode.ENFORCE) {
            loadConfigured();
        }
    }

    public IntentPolicyProfile loadConfigured() {
        return load(properties.getProfileVersion());
    }

    public IntentPolicyProfile load(String version) {
        if (!StringUtils.hasText(version)) {
            throw new IllegalStateException("Intent policy profile version is required");
        }
        return cache.computeIfAbsent(version.trim(), this::readAndValidate);
    }

    private IntentPolicyProfile readAndValidate(String version) {
        Resource resource = resourceLoader.getResource(RESOURCE_PATTERN.formatted(version));
        if (!resource.exists()) {
            throw new IllegalStateException("Intent policy profile not found: " + version);
        }
        try {
            IntentPolicyProfile profile = objectMapper.readValue(resource.getInputStream(), IntentPolicyProfile.class);
            validate(profile, version);
            return profile;
        } catch (IOException exception) {
            throw new IllegalStateException("Intent policy profile is unreadable: " + version, exception);
        }
    }

    private void validate(IntentPolicyProfile profile, String requestedVersion) {
        if (profile == null || !requestedVersion.equals(profile.version())) {
            throw new IllegalStateException("Intent policy profile version mismatch: " + requestedVersion);
        }
        if (!SHA256.matcher(nullToEmpty(profile.corpusManifestHash())).matches()
                || !SHA256.matcher(nullToEmpty(profile.treeFixtureManifestHash())).matches()) {
            throw new IllegalStateException("Intent policy profile hashes must be SHA-256 values");
        }
        if (!StringUtils.hasText(profile.classifierModelId())
                || !StringUtils.hasText(profile.promptVersion())
                || !StringUtils.hasText(profile.featureVersion())) {
            throw new IllegalStateException("Intent policy profile bindings are incomplete");
        }
        if (!profile.classifierModelId().equals(configuredClassifierModel)
                || !StructuredIntentClassifier.PROMPT_VERSION.equals(profile.promptVersion())
                || !IntentCandidateGenerator.FEATURE_VERSION.equals(profile.featureVersion())
                || Double.compare(profile.classifierTemperature(), StructuredIntentClassifier.TEMPERATURE) != 0
                || profile.classifierMaxTokens() != StructuredIntentClassifier.MAX_TOKENS) {
            throw new IllegalStateException("Intent policy profile does not match classifier runtime bindings");
        }
        if (profile.clarifyScore() < 0.0d
                || profile.acceptScore() < profile.clarifyScore()
                || profile.ambiguityGap() < 0.0d
                || profile.maxClassifierCandidates() < 2
                || profile.maxClarificationCandidates() < 2
                || profile.minimumReviewedExamples() < 1) {
            throw new IllegalStateException("Intent policy profile values are invalid");
        }
        validateConfidence(profile.deterministicExactConfidence());
        validateConfidence(profile.classifierAgreementConfidence());
        validateConfidence(profile.heuristicAcceptConfidence());
    }

    private void validateConfidence(double value) {
        if (value < 0.0d || value > 1.0d) {
            throw new IllegalStateException("Calibrated confidence must be in [0,1]");
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
