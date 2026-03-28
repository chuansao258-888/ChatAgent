package com.yulong.chatagent.intent.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * One published intent snapshot version.
 */
@Data
@AllArgsConstructor
public class IntentVersionVO {
    private Integer version;
    private boolean active;
}
