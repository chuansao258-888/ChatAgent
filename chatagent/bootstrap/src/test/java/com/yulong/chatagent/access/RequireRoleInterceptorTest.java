package com.yulong.chatagent.access;

import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.exception.ClientException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequireRoleInterceptorTest {

    private final RequireRoleInterceptor interceptor = new RequireRoleInterceptor();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldAllowAnnotatedAdminEndpointForAdminRole() throws NoSuchMethodException {
        UserContext.set(LoginUser.builder()
                .userId("admin-1")
                .role("ADMIN")
                .build());
        HandlerMethod handlerMethod = new HandlerMethod(
                new MethodRoleController(),
                MethodRoleController.class.getMethod("adminOnly")
        );

        assertThatCode(() -> interceptor.preHandle(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                handlerMethod
        )).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectMismatchedRole() throws NoSuchMethodException {
        UserContext.set(LoginUser.builder()
                .userId("user-1")
                .role("user")
                .build());
        HandlerMethod handlerMethod = new HandlerMethod(
                new MethodRoleController(),
                MethodRoleController.class.getMethod("adminOnly")
        );

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                handlerMethod
        ))
                .isInstanceOf(ClientException.class)
                .extracting("errorCode")
                .isEqualTo(BaseErrorCode.FORBIDDEN);
    }

    @Test
    void shouldResolveClassLevelAnnotation() throws NoSuchMethodException {
        UserContext.set(LoginUser.builder()
                .userId("admin-1")
                .role("admin")
                .build());
        HandlerMethod handlerMethod = new HandlerMethod(
                new ClassRoleController(),
                ClassRoleController.class.getMethod("index")
        );

        assertThatCode(() -> interceptor.preHandle(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                handlerMethod
        )).doesNotThrowAnyException();
    }

    static class MethodRoleController {

        @RequireRole(UserRole.ADMIN)
        public void adminOnly() {
        }
    }

    @RequireRole(UserRole.ADMIN)
    static class ClassRoleController {

        public void index() {
        }
    }
}
