package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.exception.ClientException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Minimal role gate used by administrator-only APIs until a broader RBAC annotation layer lands.
 */
@Component
public class AdminAccessService {

    public LoginUser requireAdmin() {
        LoginUser user = UserContext.requireUser();
        if (!StringUtils.hasText(user.getRole()) || !"admin".equalsIgnoreCase(user.getRole())) {
            throw new ClientException(BaseErrorCode.FORBIDDEN, "Admin access required");
        }
        return user;
    }

    public String requireAdminUserId() {
        return requireAdmin().getUserId();
    }
}
