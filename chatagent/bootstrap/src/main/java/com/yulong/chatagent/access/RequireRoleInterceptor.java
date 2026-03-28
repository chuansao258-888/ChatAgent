package com.yulong.chatagent.access;

import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.exception.ClientException;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Enforces {@link RequireRole} declarations on MVC handlers.
 */
@Component
public class RequireRoleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RequireRole requireRole = resolveRequireRole(handlerMethod);
        if (requireRole == null || requireRole.value().length == 0) {
            return true;
        }

        LoginUser loginUser = UserContext.requireUser();
        if (!UserRole.matchesAny(loginUser.getRole(), requireRole.value())) {
            throw new ClientException(
                    BaseErrorCode.FORBIDDEN,
                    "Required role: " + formatRoles(requireRole.value())
            );
        }
        return true;
    }

    private RequireRole resolveRequireRole(HandlerMethod handlerMethod) {
        RequireRole methodAnnotation = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(),
                RequireRole.class
        );
        if (methodAnnotation != null) {
            return methodAnnotation;
        }
        return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequireRole.class);
    }

    private String formatRoles(UserRole[] roles) {
        return Arrays.stream(roles)
                .map(UserRole::name)
                .collect(Collectors.joining(", "));
    }
}
