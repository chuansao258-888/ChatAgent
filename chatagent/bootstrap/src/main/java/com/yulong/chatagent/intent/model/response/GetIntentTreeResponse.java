package com.yulong.chatagent.intent.model.response;

import com.yulong.chatagent.intent.model.vo.IntentNodeVO;
import com.yulong.chatagent.intent.model.vo.IntentVersionVO;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Full draft tree plus published version metadata for the internal assistant.
 */
@Data
@AllArgsConstructor
public class GetIntentTreeResponse {
    private Integer activeVersion;
    private List<IntentVersionVO> versions;
    private List<IntentNodeVO> nodes;
}
