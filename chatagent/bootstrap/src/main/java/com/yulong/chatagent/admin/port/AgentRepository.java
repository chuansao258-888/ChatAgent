package com.yulong.chatagent.admin.port;

import com.yulong.chatagent.support.dto.AgentDTO;

import java.util.List;

/**
 * Persistence port for administrator-defined agents.
 */
public interface AgentRepository {

    /**
     * Lists agents owned by one user.
     *
     * @param userId owner identifier
     * @return owned agents
     */
    List<AgentDTO> findByUserId(String userId);

    /**
     * Loads one agent by identifier.
     *
     * @param id agent identifier
     * @return matching agent or {@code null}
     */
    AgentDTO findById(String id);

    /**
     * Persists a new agent.
     *
     * @param agent agent to save
     * @return {@code true} on success
     */
    boolean save(AgentDTO agent);

    /**
     * Updates an existing agent.
     *
     * @param agent agent to update
     * @return {@code true} on success
     */
    boolean update(AgentDTO agent);

    /**
     * Deletes one agent by identifier.
     *
     * @param id agent identifier
     * @return {@code true} on success
     */
    boolean deleteById(String id);
}
