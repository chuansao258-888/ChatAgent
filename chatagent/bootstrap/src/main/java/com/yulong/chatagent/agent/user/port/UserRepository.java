package com.yulong.chatagent.agent.user.port;

import com.yulong.chatagent.support.dto.UserDTO;

public interface UserRepository {

    UserDTO findById(String id);

    UserDTO findByUsername(String username);

    boolean save(UserDTO user);
}
