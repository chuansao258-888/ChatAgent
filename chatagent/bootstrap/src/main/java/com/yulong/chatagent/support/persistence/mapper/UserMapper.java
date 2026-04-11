package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.user.model.dto.UserDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
/**
 * Low-level MyBatis mapper for the {@code t_user} table.
 */
public interface UserMapper {
    int insert(UserDTO user);

    UserDTO selectById(String id);

    UserDTO selectByUsername(String username);

    int updateById(UserDTO user);

    List<UserDTO> selectPage(@Param("keyword") String keyword,
                             @Param("status") String status,
                             @Param("limit") int limit,
                             @Param("offset") int offset);

    long countPage(@Param("keyword") String keyword,
                   @Param("status") String status);

    List<UserDTO> selectActiveAdminsForUpdate();
}
