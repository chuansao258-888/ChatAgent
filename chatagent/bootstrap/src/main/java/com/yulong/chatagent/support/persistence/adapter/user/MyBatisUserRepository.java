package com.yulong.chatagent.support.persistence.adapter.user;

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

    public MyBatisUserRepository(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDTO findById(String id) {
        return userMapper.selectById(id);
    }

    @Override
    public UserDTO findByUsername(String username) {
        return userMapper.selectByUsername(username);
    }

    @Override
    public boolean save(UserDTO user) {
        return userMapper.insert(user) > 0;
    }

    @Override
    public boolean update(UserDTO user) {
        return userMapper.updateById(user) > 0;
    }

    @Override
    public List<UserDTO> findPage(String keyword, String status, int limit, int offset) {
        return new ArrayList<>(userMapper.selectPage(keyword, status, limit, offset));
    }

    @Override
    public long count(String keyword, String status) {
        return userMapper.countPage(keyword, status);
    }

    @Override
    public List<UserDTO> findActiveAdminsForUpdate() {
        return new ArrayList<>(userMapper.selectActiveAdminsForUpdate());
    }
}
