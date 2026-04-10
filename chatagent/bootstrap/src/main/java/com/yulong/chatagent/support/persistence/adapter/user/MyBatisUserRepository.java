package com.yulong.chatagent.support.persistence.adapter.user;

import com.yulong.chatagent.user.converter.UserConverter;
import com.yulong.chatagent.support.persistence.entity.User;
import com.yulong.chatagent.support.persistence.mapper.UserMapper;
import com.yulong.chatagent.user.model.dto.UserDTO;
import com.yulong.chatagent.user.port.UserRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

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

    @Override
    public boolean update(UserDTO user) {
        return userMapper.updateById(toEntity(user)) > 0;
    }

    @Override
    public List<UserDTO> findPage(String keyword, String status, int limit, int offset) {
        return toDTOList(userMapper.selectPage(keyword, status, limit, offset));
    }

    @Override
    public long count(String keyword, String status) {
        return userMapper.countPage(keyword, status);
    }

    @Override
    public List<UserDTO> findActiveAdminsForUpdate() {
        return toDTOList(userMapper.selectActiveAdminsForUpdate());
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

    private List<UserDTO> toDTOList(List<User> entities) {
        List<UserDTO> result = new ArrayList<>();
        if (entities == null) {
            return result;
        }
        for (User entity : entities) {
            result.add(toDTO(entity));
        }
        return result;
    }
}
