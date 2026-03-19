package com.yulong.chatagent.user.infrastructure.persistence.mapper;

import com.yulong.chatagent.user.infrastructure.persistence.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/**
 * Low-level MyBatis mapper for the {@code t_user} table.
 */
public interface UserMapper {
    int insert(User user);

    User selectById(String id);

    User selectByUsername(String username);

    int updateById(User user);
}
