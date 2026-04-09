package com.yulong.chatagent.user.infrastructure.persistence.mapper;

import com.yulong.chatagent.user.infrastructure.persistence.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
/**
 * Low-level MyBatis mapper for the {@code t_user} table.
 */
public interface UserMapper {
    int insert(User user);

    User selectById(String id);

    User selectByUsername(String username);

    int updateById(User user);

    List<User> selectPage(@Param("keyword") String keyword,
                          @Param("status") String status,
                          @Param("limit") int limit,
                          @Param("offset") int offset);

    long countPage(@Param("keyword") String keyword,
                   @Param("status") String status);

    List<User> selectActiveAdminsForUpdate();
}
