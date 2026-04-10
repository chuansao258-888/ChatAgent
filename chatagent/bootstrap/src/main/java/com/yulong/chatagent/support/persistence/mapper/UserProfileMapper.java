package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.UserProfile;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/**
 * Low-level MyBatis mapper for the {@code user_profile} table.
 */
public interface UserProfileMapper {

    UserProfile selectByUserId(String userId);

    int insert(UserProfile userProfile);

    int upsert(UserProfile userProfile);
}
