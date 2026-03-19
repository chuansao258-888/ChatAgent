package com.yulong.chatagent.user.config;

import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.user.application.JwtTokenService;
import com.yulong.chatagent.user.converter.UserConverter;
import com.yulong.chatagent.user.model.dto.JwtClaims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@Slf4j
/**
 * Protects authenticated endpoints by validating the access token from the
 * Authorization header and populating {@link UserContext}.
 */
public class JwtAuthenticationInterceptor implements HandlerInterceptor {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;
    private final UserConverter userConverter;

    public JwtAuthenticationInterceptor(JwtTokenService jwtTokenService,
                                        UserConverter userConverter) {
        this.jwtTokenService = jwtTokenService;
        this.userConverter = userConverter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        // Servlet threads are reused, so always clear any stale user state first.
        UserContext.clear();

        String accessToken = resolveAccessToken(request);
        if (!StringUtils.hasText(accessToken)) {
            log.info("JWT auth rejected: missing access token, method={}, uri={}",
                    request.getMethod(),
                    request.getRequestURI());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing access token");
            return false;
        }

        if (!jwtTokenService.isAccessTokenValid(accessToken)) {
            log.warn("JWT auth rejected: invalid access token, method={}, uri={}, accessToken={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    accessToken);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid access token");
            return false;
        }

        JwtClaims claims = jwtTokenService.parseAccessToken(accessToken);
        // Store only the minimal authenticated user snapshot needed during the
        // lifetime of this request.
        UserContext.set(userConverter.toLoginUser(claims));
        log.info("JWT auth accepted: method={}, uri={}, userId={}, username={}, accessToken={}",
                request.getMethod(),
                request.getRequestURI(),
                claims.getUserId(),
                claims.getUsername(),
                accessToken);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        // Guarantee context cleanup even when the handler throws.
        UserContext.clear();
    }

    private String resolveAccessToken(HttpServletRequest request) {
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        // Keep header parsing in one place so the token service only handles
        // raw JWT strings and stays independent from HTTP formatting details.
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }
}
