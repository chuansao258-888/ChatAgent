package com.yulong.chatagent.knowledge.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.Objects;

@Data
@Builder
public class IngestionTaskEventVO {
    private String taskId;
    private String documentId;
    private String status;
    private Integer chunkCount;
    private String errorMessage;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        IngestionTaskEventVO that = (IngestionTaskEventVO) o;
        return Objects.equals(taskId, that.taskId) && Objects.equals(documentId, that.documentId) && Objects.equals(status, that.status) && Objects.equals(chunkCount, that.chunkCount) && Objects.equals(errorMessage, that.errorMessage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, documentId, status, chunkCount, errorMessage);
    }

    @Override
    public String toString() {
        return "IngestionTaskEventVO{" +
                "taskId='" + taskId + '\'' +
                ", documentId='" + documentId + '\'' +
                ", status='" + status + '\'' +
                ", chunkCount=" + chunkCount +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}
