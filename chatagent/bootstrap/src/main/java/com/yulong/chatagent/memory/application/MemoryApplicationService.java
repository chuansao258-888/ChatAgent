package com.yulong.chatagent.memory.application;

import com.yulong.chatagent.memory.port.MemoryItemRepository;
import com.yulong.chatagent.support.dto.MemoryItemDTO;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class MemoryApplicationService {
    public enum InspectStatus { UNIQUE, AMBIGUOUS, NOT_FOUND }
    public enum CorrectStatus { UPDATED, ALREADY_APPLIED, CONFLICT, NOT_FOUND, INVALID_EVIDENCE }
    public record WritableMemory(String id, String type, String content, LocalDateTime updatedAt) {}
    public record DisplayMemory(String type, String content, LocalDateTime updatedAt) {}
    public record InspectResult(InspectStatus status, WritableMemory memory, List<DisplayMemory> candidates) {}
    public record CorrectResult(CorrectStatus status, WritableMemory memory) {}

    private final MemoryItemRepository repository;
    private final OllamaEmbeddingClient embeddingClient;
    private final UserMemoryIndexService indexService;
    private final ObjectMapper objectMapper;

    public MemoryApplicationService(MemoryItemRepository repository, OllamaEmbeddingClient embeddingClient,
                                    UserMemoryIndexService indexService, ObjectMapper objectMapper) {
        this.repository = repository; this.embeddingClient = embeddingClient;
        this.indexService = indexService; this.objectMapper = objectMapper;
    }

    public List<WritableMemory> list(String userId) {
        return repository.findByUserIdAndStatus(userId, "active").stream().map(this::writable).toList();
    }

    public InspectResult inspect(String userId, String query) {
        String needle = MemoryHashNormalizer.normalize(query);
        int length = needle.codePointCount(0, needle.length());
        if (length < 2 || length > 200) throw new IllegalArgumentException("query must contain 2-200 code points");
        List<MemoryItemDTO> matches = repository.findByUserIdAndStatus(userId, "active").stream()
                .filter(item -> MemoryHashNormalizer.normalize(item.getContent()).contains(needle)).toList();
        if (matches.isEmpty()) return new InspectResult(InspectStatus.NOT_FOUND, null, List.of());
        if (matches.size() == 1) return new InspectResult(InspectStatus.UNIQUE, writable(matches.get(0)), List.of());
        return new InspectResult(InspectStatus.AMBIGUOUS, null, matches.stream().limit(5)
                .map(i -> new DisplayMemory(i.getType(), i.getContent(), i.getUpdatedAt())).toList());
    }

    public WritableMemory create(String userId, String type, String content) {
        validate(type, content);
        String normalized = MemoryHashNormalizer.normalize(content);
        String hash = MemoryHashNormalizer.hash(userId, type, normalized);
        MemoryItemDTO existing = repository.findByUserTypeAndHash(userId, type, hash);
        if (existing != null) {
            if ("archived".equals(existing.getStatus())) repository.reactivate(userId, existing.getId());
            MemoryItemDTO result = repository.findOwnedById(userId, existing.getId());
            sync(result);
            return writable(result);
        }
        MemoryItemDTO created = repository.upsert(MemoryItemDTO.builder().userId(userId).type(type)
                .content(content.trim()).tags(List.of()).source(Map.of("origin", "user"))
                .contentHash(hash).status("active").indexStatus("pending").build());
        sync(created);
        return writable(created);
    }

    public CorrectResult correct(String userId, String id, LocalDateTime expectedUpdatedAt,
                                 String type, String newContent) {
        if (!StringUtils.hasText(id) || expectedUpdatedAt == null) return new CorrectResult(CorrectStatus.CONFLICT, null);
        MemoryItemDTO current = repository.findOwnedById(userId, id);
        if (current == null || !"active".equals(current.getStatus())) return new CorrectResult(CorrectStatus.NOT_FOUND, null);
        String desiredType = StringUtils.hasText(type) ? type.trim() : current.getType();
        validate(desiredType, newContent);
        String desired = newContent.trim();
        if (desiredType.equals(current.getType()) && desired.equals(current.getContent()))
            return new CorrectResult(CorrectStatus.ALREADY_APPLIED, writable(current));
        if (!expectedUpdatedAt.equals(current.getUpdatedAt())) return new CorrectResult(CorrectStatus.CONFLICT, null);
        String hash = MemoryHashNormalizer.hash(userId, desiredType, MemoryHashNormalizer.normalize(desired));
        MemoryItemDTO duplicate = repository.findByUserTypeAndHash(userId, desiredType, hash);
        if (duplicate != null && !id.equals(duplicate.getId())) return new CorrectResult(CorrectStatus.CONFLICT, null);
        try {
            if (!repository.correct(userId, id, expectedUpdatedAt, desiredType, desired, hash))
                return new CorrectResult(CorrectStatus.CONFLICT, null);
        } catch (DataIntegrityViolationException ex) {
            return new CorrectResult(CorrectStatus.CONFLICT, null);
        }
        MemoryItemDTO updated = repository.findOwnedById(userId, id);
        sync(updated);
        return new CorrectResult(CorrectStatus.UPDATED, writable(updated));
    }

    public boolean archive(String userId, String id) {
        MemoryItemDTO current = repository.findOwnedById(userId, id);
        if (current == null) return false;
        if ("active".equals(current.getStatus()) && !repository.archive(userId, id)) return false;
        boolean removed = indexService.deleteMemory(id);
        repository.updateIndexStatus(id, removed ? "removed" : "failed");
        return true;
    }

    public int reconcile(int limit) {
        int repaired = 0;
        for (MemoryItemDTO item : repository.findIndexCandidates(Math.max(1, limit))) {
            if ("active".equals(item.getStatus())) {
                sync(item);
            } else {
                boolean removed = indexService.deleteMemory(item.getId());
                repository.updateIndexStatus(item.getId(), removed ? "removed" : "failed");
            }
            repaired++;
        }
        return repaired;
    }

    public CorrectResult correctFromConversation(String userId, String currentInput, String evidenceQuote,
                                                 String id, LocalDateTime expected, String type, String content) {
        String input = MemoryHashNormalizer.normalize(currentInput);
        String evidence = MemoryHashNormalizer.normalize(evidenceQuote);
        String desired = MemoryHashNormalizer.normalize(content);
        if (!StringUtils.hasText(evidence) || !input.contains(evidence) || !evidence.contains(desired)
                || !(evidence.contains("纠正") || evidence.contains("改成") || evidence.contains("更正")
                || evidence.contains("不是") || evidence.contains("correct") || evidence.contains("change")))
            return new CorrectResult(CorrectStatus.INVALID_EVIDENCE, null);
        return correct(userId, id, expected, type, content);
    }

    private void validate(String type, String content) {
        if (!("fact".equals(type) || "preference".equals(type)) || !StringUtils.hasText(content))
            throw new IllegalArgumentException("type must be fact/preference and content must not be blank");
    }
    private WritableMemory writable(MemoryItemDTO i) { return new WritableMemory(i.getId(), i.getType(), i.getContent(), i.getUpdatedAt()); }
    private void sync(MemoryItemDTO item) {
        try {
            String tags = objectMapper.writeValueAsString(item.getTags() == null ? List.of() : item.getTags());
            boolean ok = indexService.upsertMemory(item.getId(), item.getUserId(), item.getType(), "active",
                    item.getContent(), tags, embeddingClient.embed(item.getContent()));
            repository.updateIndexStatus(item.getId(), ok ? "indexed" : "failed");
        } catch (Exception ex) {
            repository.updateIndexStatus(item.getId(), "failed");
        }
    }
}
