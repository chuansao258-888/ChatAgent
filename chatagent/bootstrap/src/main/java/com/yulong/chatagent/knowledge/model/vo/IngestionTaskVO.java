package com.yulong.chatagent.knowledge.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Objects;

@Data
@Builder
public class IngestionTaskVO {

    private String id;

    private String kbId;

    private String documentId;

    private String fileType;

    private String status;

    private Integer chunkCount;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        IngestionTaskVO that = (IngestionTaskVO) o;
        return Objects.equals(id, that.id) && Objects.equals(kbId, that.kbId) && Objects.equals(documentId, that.documentId) && Objects.equals(fileType, that.fileType) && Objects.equals(status, that.status) && Objects.equals(chunkCount, that.chunkCount) && Objects.equals(errorMessage, that.errorMessage) && Objects.equals(startedAt, that.startedAt) && Objects.equals(finishedAt, that.finishedAt) && Objects.equals(createdAt, that.createdAt) && Objects.equals(updatedAt, that.updatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, kbId, documentId, fileType, status, chunkCount, errorMessage, startedAt, finishedAt, createdAt, updatedAt);
    }

    @Override
    public String toString() {
        return "IngestionTaskVO{" +
                "id='" + id + '\'' +
                ", kbId='" + kbId + '\'' +
                ", documentId='" + documentId + '\'' +
                ", fileType='" + fileType + '\'' +
                ", status='" + status + '\'' +
                ", chunkCount=" + chunkCount +
                ", errorMessage='" + errorMessage + '\'' +
                ", startedAt=" + startedAt +
                ", finishedAt=" + finishedAt +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
