package com.yulong.chatagent.user.infrastructure.persistence.adapter;

import com.yulong.chatagent.user.infrastructure.persistence.entity.UserProfile;
import com.yulong.chatagent.user.infrastructure.persistence.mapper.UserProfileMapper;
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
        return toDTO(userProfileMapper.selectByUserId(userId));
    }

    @Override
    public boolean saveOrUpdate(UserProfileDTO userProfile) {
        return userProfileMapper.upsert(toEntity(userProfile)) > 0;
    }

    private UserProfileDTO toDTO(UserProfile entity) {
        if (entity == null) {
            return null;
        }
        return UserProfileDTO.builder()
                .userId(entity.getUserId())
                .summary(entity.getSummary())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private UserProfile toEntity(UserProfileDTO dto) {
        if (dto == null) {
            return null;
        }
        return UserProfile.builder()
                .userId(dto.getUserId())
                .summary(dto.getSummary())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }
}
