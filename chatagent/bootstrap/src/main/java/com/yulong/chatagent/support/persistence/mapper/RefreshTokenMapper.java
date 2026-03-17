package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.RefreshToken;

public interface RefreshTokenMapper {
    int insert(RefreshToken refreshToken);

    RefreshToken selectByTokenHash(String tokenHash);

    int revokeById(String id);

    int revokeByUserId(String userId);
}
