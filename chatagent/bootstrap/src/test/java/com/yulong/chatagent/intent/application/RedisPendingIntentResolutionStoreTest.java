package com.yulong.chatagent.intent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.runtime.contract.ClarificationKind;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisPendingIntentResolutionStoreTest {

    @Test
    void shouldReadLegacyPayloadWithSafeDefaults() {
        Fixture fixture = fixture();
        when(fixture.operations().get("chatagent:intent:pending:session"))
                .thenReturn("{\"sessionId\":\"session\",\"candidateNodeIds\":[\"a\"],\"originalQuery\":\"q\"}");

        PendingIntentResolution pending = fixture.store().get("session");

        assertThat(pending.getClarificationKind()).isEqualTo(ClarificationKind.ROUTE_CHOICE);
        assertThat(pending.attemptCountOrZero()).isZero();
        assertThat(pending.orderedCandidateNodeIds()).containsExactly("a");
    }

    @Test
    void shouldPersistTypedCandidateOrderingAndCompatibilityFields() throws Exception {
        Fixture fixture = fixture();
        PendingIntentResolution pending = PendingIntentResolution.builder()
                .sessionId("session")
                .candidateNodeIds(List.of("a", "b"))
                .orderedCandidates(List.of(
                        new PendingIntentResolution.PendingCandidate("a", 1.2d, 1),
                        new PendingIntentResolution.PendingCandidate("b", 1.0d, 2)))
                .clarificationKind(ClarificationKind.ROUTE_CHOICE)
                .attemptCount(1)
                .policyProfileVersion("v1")
                .contractVersion("v1")
                .missingDimensions(List.of(MissingDimension.CONFIRMATION))
                .knownRouteNodeId("a")
                .actionIdentity("action-1")
                .build();

        fixture.store().save(pending);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(fixture.operations()).set(anyString(), payload.capture(), any(Duration.class));
        PendingIntentResolution restored = fixture.mapper().readValue(payload.getValue(), PendingIntentResolution.class);
        assertThat(restored.getPolicyProfileVersion()).isEqualTo("v1");
        assertThat(restored.getOrderedCandidates()).extracting(PendingIntentResolution.PendingCandidate::nodeId)
                .containsExactly("a", "b");
        assertThat(restored.getMissingDimensions()).containsExactly(MissingDimension.CONFIRMATION);
        assertThat(restored.getKnownRouteNodeId()).isEqualTo("a");
        assertThat(restored.getActionIdentity()).isEqualTo("action-1");
    }

    @Test
    void shouldDeleteExpiredPayload() throws Exception {
        Fixture fixture = fixture();
        PendingIntentResolution expired = PendingIntentResolution.builder()
                .sessionId("session")
                .candidateNodeIds(List.of("a"))
                .expiresAt(Instant.now().minusSeconds(1))
                .build();
        when(fixture.operations().get("chatagent:intent:pending:session"))
                .thenReturn(fixture.mapper().writeValueAsString(expired));

        assertThat(fixture.store().get("session")).isNull();

        verify(fixture.template()).delete("chatagent:intent:pending:session");
    }

    @Test
    void shouldDeleteMalformedPayloadWithoutThrowing() {
        Fixture fixture = fixture();
        when(fixture.operations().get("chatagent:intent:pending:session"))
                .thenReturn("not-json");

        assertThat(fixture.store().get("session")).isNull();

        verify(fixture.template()).delete("chatagent:intent:pending:session");
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(operations);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new Fixture(new RedisPendingIntentResolutionStore(template, mapper, 5),
                template, operations, mapper);
    }

    private record Fixture(
            RedisPendingIntentResolutionStore store,
            StringRedisTemplate template,
            ValueOperations<String, String> operations,
            ObjectMapper mapper
    ) {
    }
}
