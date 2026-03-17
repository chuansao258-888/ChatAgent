import { useEffect, useState } from "react";
import { createAgent, getAgents, deleteAgent, updateAgent, } from "../api/api.ts";
export function useAgents() {
    const [agents, setAgents] = useState([]);
    useEffect(() => {
        async function fetchData() {
            const resp = await getAgents();
            setAgents(resp.agents);
        }
        fetchData().then();
    }, []);
    async function refreshAgents() {
        const resp = await getAgents();
        setAgents(resp.agents);
    }
    async function createAgentHandle(agent) {
        await createAgent(agent);
        await refreshAgents();
    }
    async function deleteAgentHandle(agentId) {
        await deleteAgent(agentId);
        await refreshAgents();
    }
    async function updateAgentHandle(agentId, request) {
        await updateAgent(agentId, request);
        await refreshAgents();
    }
    return {
        agents,
        createAgentHandle,
        deleteAgentHandle,
        updateAgentHandle,
        refreshAgents,
    };
}
