package com.yulong.chatagent.agent.user.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CurrentUserVO {

    private String userId;

    private String username;

    private String role;

    private String avatar;
}
