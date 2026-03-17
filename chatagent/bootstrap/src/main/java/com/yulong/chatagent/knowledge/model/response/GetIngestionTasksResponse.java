package com.yulong.chatagent.knowledge.model.response;

import com.yulong.chatagent.knowledge.model.vo.IngestionTaskVO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GetIngestionTasksResponse {
    private IngestionTaskVO[]  ingestionTasks;
}
