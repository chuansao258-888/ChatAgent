package com.yulong.chatagent.admin.port;

import com.yulong.chatagent.support.dto.AgentDTO;

import java.util.List;

public interface AgentRepository {

    List<AgentDTO> findAll();

    AgentDTO findById(String id);

    boolean save(AgentDTO agent);

    boolean update(AgentDTO agent);

    boolean deleteById(String id);
}
