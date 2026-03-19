package com.yulong.chatagent.user.infrastructure.persistence.adapter;

import com.yulong.chatagent.user.converter.UserConverter;
import com.yulong.chatagent.user.infrastructure.persistence.entity.User;
import com.yulong.chatagent.user.infrastructure.persistence.mapper.UserMapper;
import com.yulong.chatagent.user.model.dto.UserDTO;
import com.yulong.chatagent.user.port.UserRepository;
import org.springframework.stereotype.Repository;

@Repository
/**
 * MyBatis-backed {@link UserRepository} implementation.
 */
public class MyBatisUserRepository implements UserRepository {

    private final UserMapper userMapper;
    private final UserConverter userConverter;

    public MyBatisUserRepository(UserMapper userMapper,
                                 UserConverter userConverter) {
        this.userMapper = userMapper;
        this.userConverter = userConverter;
    }

    @Override
    public UserDTO findById(String id) {
        return toDTO(userMapper.selectById(id));
    }

    @Override
    public UserDTO findByUsername(String username) {
        return toDTO(userMapper.selectByUsername(username));
    }

    @Override
    public boolean save(UserDTO user) {
        User entity = toEntity(user);
        boolean saved = userMapper.insert(entity) > 0;
        if (saved) {
            // Propagate the generated identifier back to the DTO so callers
            // can continue issuing tokens without reloading the user.
            user.setId(entity.getId());
        }
        return saved;
    }

    private UserDTO toDTO(User entity) {
        if (entity == null) {
            return null;
        }
        try {
            return userConverter.toDTO(entity);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to convert user entity to dto", e);
        }
    }

    private User toEntity(UserDTO dto) {
        try {
            return userConverter.toEntity(dto);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to convert user dto to entity", e);
        }
    }
}
