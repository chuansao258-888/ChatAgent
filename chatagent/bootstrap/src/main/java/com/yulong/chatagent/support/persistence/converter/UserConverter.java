package com.yulong.chatagent.support.persistence.converter;

import com.yulong.chatagent.support.dto.UserDTO;
import com.yulong.chatagent.support.persistence.entity.User;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Component
public class UserConverter {

    public User toEntity(UserDTO userDTO) {
        Assert.notNull(userDTO, "UserDTO cannot be null");

        return User.builder()
                .id(userDTO.getId())
                .username(userDTO.getUsername())
                .passwordHash(userDTO.getPasswordHash())
                .role(userDTO.getRole())
                .avatar(userDTO.getAvatar())
                .createdAt(userDTO.getCreatedAt())
                .updatedAt(userDTO.getUpdatedAt())
                .build();
    }

    public UserDTO toDTO(User user) {
        Assert.notNull(user, "User cannot be null");

        return UserDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .passwordHash(user.getPasswordHash())
                .role(user.getRole())
                .avatar(user.getAvatar())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
