package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.ChatTurnMetric;
import org.apache.ibatis.annotations.Mapper;

/**
 * Low-level MyBatis mapper for {@code t_chat_turn_metric}.
 */
@Mapper
public interface ChatTurnMetricMapper {

    int insert(ChatTurnMetric chatTurnMetric);
}
