package com.yulong.chatagent.user.converter;

import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.user.infrastructure.persistence.entity.User;
import com.yulong.chatagent.user.model.dto.JwtClaims;
import com.yulong.chatagent.user.model.dto.UserDTO;
import com.yulong.chatagent.user.model.response.LoginResponse;
import com.yulong.chatagent.user.model.vo.LoginUserVO;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Component
/**
 * Central place for conversions between persistence, token, context, and API
 * models inside the user module.
 */
public class UserConverter {

    /**
     * Converts a user DTO into the persistence entity used by MyBatis.
     *
     * @param userDTO application user DTO
     * @return persistence entity
     */
    public User toEntity(UserDTO userDTO) {
        Assert.notNull(userDTO, "UserDTO cannot be null");

        return User.builder()
                .id(userDTO.getId())
                .username(userDTO.getUsername())
                .passwordHash(userDTO.getPasswordHash())
                .role(userDTO.getRole())
                .avatar(userDTO.getAvatar())
                .status(userDTO.getStatus())
                .deleted(userDTO.getDeleted())
                .createdAt(userDTO.getCreatedAt())
                .updatedAt(userDTO.getUpdatedAt())
                .build();
    }

    /**
     * Converts a persistence entity into the application-level user DTO.
     *
     * @param user persistence entity
     * @return application user DTO
     */
    public UserDTO toDTO(User user) {
        Assert.notNull(user, "User cannot be null");

        return UserDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .passwordHash(user.getPasswordHash())
                .role(user.getRole())
                .avatar(user.getAvatar())
                .status(user.getStatus())
                .deleted(user.getDeleted())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }

    /**
     * Extracts the minimal claim set that should be embedded in a JWT.
     *
     * @param user application user DTO
     * @return token claim payload
     */
    public JwtClaims toJwtClaims(UserDTO user) {
        Assert.notNull(user, "UserDTO cannot be null");

        return JwtClaims.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .role(user.getRole())
                .build();
    }

    /**
     * Builds the request-context user object from parsed JWT claims.
     *
     * @param claims parsed access-token claims
     * @return authenticated user snapshot for {@code UserContext}
     */
    public LoginUser toLoginUser(JwtClaims claims) {
        Assert.notNull(claims, "JwtClaims cannot be null");

        return LoginUser.builder()
                .userId(claims.getUserId())
                .username(claims.getUsername())
                .role(claims.getRole())
                .build();
    }

    /**
     * Builds the request-context user object from the latest persisted user snapshot.
     *
     * @param user application user DTO
     * @return authenticated user snapshot for {@code UserContext}
     */
    public LoginUser toLoginUser(UserDTO user) {
        Assert.notNull(user, "UserDTO cannot be null");

        return LoginUser.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .role(user.getRole())
                .avatar(user.getAvatar())
                .status(user.getStatus())
                .build();
    }

    /**
     * Converts the internal request-context user into the API view returned
     * by {@code /api/user/me}.
     *
     * @param loginUser authenticated user from request context
     * @return API view of the current user
     */
    public LoginUserVO toLoginUserVO(LoginUser loginUser) {
        Assert.notNull(loginUser, "LoginUser cannot be null");

        return LoginUserVO.builder()
                .userId(loginUser.getUserId())
                .username(loginUser.getUsername())
                .role(loginUser.getRole())
                .avatar(loginUser.getAvatar())
                .status(loginUser.getStatus())
                .build();
    }

    /**
     * Assembles the response payload used by register, login, and refresh.
     *
     * @param user authenticated user DTO
     * @param accessToken newly issued JWT access token
     * @param refreshToken newly issued opaque refresh token
     * @return API response payload
     */
    public LoginResponse toLoginResponse(UserDTO user, String accessToken, String refreshToken) {
        Assert.notNull(user, "UserDTO cannot be null");
        Assert.notNull(accessToken, "accessToken cannot be null");
        Assert.notNull(refreshToken, "refreshToken cannot be null");

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .userId(user.getId())
                .username(user.getUsername())
                .role(user.getRole())
                .avatar(user.getAvatar())
                .status(user.getStatus())
                .build();
    }
}
