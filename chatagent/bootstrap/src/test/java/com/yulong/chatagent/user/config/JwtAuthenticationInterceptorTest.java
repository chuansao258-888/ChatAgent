package com.yulong.chatagent.user.config;

import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.user.application.AuthenticatedUserSnapshotCache;
import com.yulong.chatagent.user.application.JwtTokenService;
import com.yulong.chatagent.user.converter.UserConverter;
import com.yulong.chatagent.user.model.UserStatus;
import com.yulong.chatagent.user.model.dto.JwtClaims;
import com.yulong.chatagent.user.model.dto.UserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationInterceptorTest {

    @Mock
    private JwtTokenService jwtTokenService;

    @Mock
    private UserConverter userConverter;

    @Mock
    private AuthenticatedUserSnapshotCache authenticatedUserSnapshotCache;

    private JwtAuthenticationInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new JwtAuthenticationInterceptor(jwtTokenService, userConverter, authenticatedUserSnapshotCache);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldRejectDisabledUserEvenWhenTokenIsValid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/admin/users");
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        JwtClaims claims = JwtClaims.builder()
                .userId("user-1")
                .username("user")
                .role("admin")
                .build();
        when(jwtTokenService.isAccessTokenValid("token")).thenReturn(true);
        when(jwtTokenService.parseAccessToken("token")).thenReturn(claims);
        when(authenticatedUserSnapshotCache.getByUserId("user-1")).thenReturn(UserDTO.builder()
                .id("user-1")
                .username("user")
                .role("admin")
                .status(UserStatus.DISABLED.name())
                .deleted(Boolean.FALSE)
                .build());

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        verify(userConverter, never()).toLoginUser(org.mockito.ArgumentMatchers.any(UserDTO.class));
    }

    @Test
    void shouldNotSendErrorAgainWhenCommittedSseResponseHasInvalidToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/sse/connect/session-1");
        request.setParameter("access_token", "expired-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);
        when(jwtTokenService.isAccessTokenValid("expired-token")).thenReturn(false);

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(200);
        verify(jwtTokenService).isAccessTokenValid("expired-token");
    }

    @Test
    void shouldUseCurrentUserSnapshotFromRepository() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/admin/users");
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        JwtClaims claims = JwtClaims.builder()
                .userId("user-1")
                .username("old-name")
                .role("admin")
                .build();
        UserDTO currentUser = UserDTO.builder()
                .id("user-1")
                .username("fresh-name")
                .role("user")
                .status(UserStatus.ACTIVE.name())
                .deleted(Boolean.FALSE)
                .build();
        LoginUser loginUser = LoginUser.builder()
                .userId("user-1")
                .username("fresh-name")
                .role("user")
                .build();
        when(jwtTokenService.isAccessTokenValid("token")).thenReturn(true);
        when(jwtTokenService.parseAccessToken("token")).thenReturn(claims);
        when(authenticatedUserSnapshotCache.getByUserId("user-1")).thenReturn(currentUser);
        when(userConverter.toLoginUser(currentUser)).thenReturn(loginUser);

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(UserContext.requireUser().getRole()).isEqualTo("user");
        assertThat(UserContext.requireUser().getUsername()).isEqualTo("fresh-name");
    }
}
