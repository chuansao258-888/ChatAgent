package com.yulong.chatagent.support.persistence.adapter.user;

import com.yulong.chatagent.support.persistence.mapper.UserProfileMapper;
import com.yulong.chatagent.user.model.dto.UserProfileDTO;
import com.yulong.chatagent.user.port.UserProfileRepository;
import org.springframework.stereotype.Repository;

/**
 * MyBatis-backed {@link UserProfileRepository} implementation.
 */
@Repository
public class MyBatisUserProfileRepository implements UserProfileRepository {

    private final UserProfileMapper userProfileMapper;

    public MyBatisUserProfileRepository(UserProfileMapper userProfileMapper) {
        this.userProfileMapper = userProfileMapper;
    }

    @Override
    public UserProfileDTO findByUserId(String userId) {
        return userProfileMapper.selectByUserId(userId);
    }

    @Override
    public boolean saveOrUpdate(UserProfileDTO userProfile) {
        return userProfileMapper.upsert(userProfile) > 0;
    }
}
