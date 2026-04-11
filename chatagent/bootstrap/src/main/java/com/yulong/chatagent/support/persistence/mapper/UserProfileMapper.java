package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.user.model.dto.UserProfileDTO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/**
 * Low-level MyBatis mapper for the {@code user_profile} table.
 */
public interface UserProfileMapper {

    UserProfileDTO selectByUserId(String userId);

    int insert(UserProfileDTO userProfile);

    int upsert(UserProfileDTO userProfile);
}
