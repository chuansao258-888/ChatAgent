package com.yulong.chatagent.admin.model.response;

import com.yulong.chatagent.admin.model.vo.AdminUserVO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GetAdminUsersResponse {

    private AdminUserVO[] users;

    private int page;

    private int size;

    private long total;
}
