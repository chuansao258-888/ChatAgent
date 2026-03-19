package com.yulong.chatagent.user.application;

import com.yulong.chatagent.user.model.request.LoginRequest;
import com.yulong.chatagent.user.model.request.RegisterRequest;
import com.yulong.chatagent.user.model.response.LoginResponse;
import com.yulong.chatagent.user.model.vo.LoginUserVO;

/**
 * Application-level contract for authentication and current-user queries.
 */
public interface AuthService {

    /**
     * Creates a new user account and immediately opens a logged-in session.
     *
     * @param request registration input containing username and password
     * @return login payload with a new access token and current user data
     */
    LoginResponse register(RegisterRequest request);

    /**
     * Authenticates an existing user and opens a new session.
     *
     * @param request login input containing username and password
     * @return login payload with a new access token and current user data
     */
    LoginResponse login(LoginRequest request);

    /**
     * Renews an existing session by rotating the refresh token and issuing
     * a fresh access token.
     *
     * @param refreshToken opaque refresh token provided by the client
     * @return login payload containing the new tokens and current user data
     */
    LoginResponse refresh(String refreshToken);

    /**
     * Revokes the current renewable session represented by the refresh token.
     *
     * @param refreshToken opaque refresh token to revoke
     */
    void logout(String refreshToken);

    /**
     * Returns the authenticated user snapshot stored in the request context.
     *
     * @return current authenticated user view
     */
    LoginUserVO currentUser();
}
