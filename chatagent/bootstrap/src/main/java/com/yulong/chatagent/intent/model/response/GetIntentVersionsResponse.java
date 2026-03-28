package com.yulong.chatagent.intent.model.response;

import com.yulong.chatagent.intent.model.vo.IntentVersionVO;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Published version list for active-version switching.
 */
@Data
@AllArgsConstructor
public class GetIntentVersionsResponse {
    private List<IntentVersionVO> versions;
}
