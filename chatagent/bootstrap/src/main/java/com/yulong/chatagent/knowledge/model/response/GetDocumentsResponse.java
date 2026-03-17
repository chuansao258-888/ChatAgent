package com.yulong.chatagent.knowledge.model.response;

import com.yulong.chatagent.knowledge.model.vo.DocumentVO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GetDocumentsResponse {
    private DocumentVO[] documents;
}


