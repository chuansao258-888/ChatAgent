package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.exception.ClientException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminAccessServiceTest {

    private final AdminAccessService adminAccessService = new AdminAccessService();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldThrowForbiddenWhenCurrentUserIsNotAdmin() {
        UserContext.set(LoginUser.builder()
                .userId("user-1")
                .role("user")
                .build());

        assertThatThrownBy(adminAccessService::requireAdmin)
                .isInstanceOf(ClientException.class)
                .extracting("errorCode")
                .isEqualTo(BaseErrorCode.FORBIDDEN);
    }

    @Test
    void shouldReturnCurrentUserWhenRoleIsAdmin() {
        LoginUser admin = LoginUser.builder()
                .userId("admin-1")
                .role("admin")
                .build();
        UserContext.set(admin);

        assertThat(adminAccessService.requireAdmin()).isEqualTo(admin);
    }
}
