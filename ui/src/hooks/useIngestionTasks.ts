import { useCallback, useEffect, useMemo, useState } from "react";
import {
  getIngestionTasksByKbId,
  type IngestionTaskVO,
} from "../api/api.ts";

const ACTIVE_TASK_STATUSES = new Set(["PENDING", "RUNNING"]);

export function useIngestionTasks(kbId: string | undefined) {
  const [tasks, setTasks] = useState<IngestionTaskVO[]>([]);
  const [loading, setLoading] = useState(false);

  const fetchTasks = useCallback(async () => {
    if (!kbId) {
      setTasks([]);
      return;
    }

    setLoading(true);
    try {
      const resp = await getIngestionTasksByKbId(kbId);
      setTasks(resp.ingestionTasks);
    } finally {
      setLoading(false);
    }
  }, [kbId]);

  useEffect(() => {
    fetchTasks();
  }, [fetchTasks]);

  const hasActiveTasks = useMemo(
    () => tasks.some((task) => ACTIVE_TASK_STATUSES.has(task.status)),
    [tasks],
  );

  const updateTask = useCallback(
    (
      taskId: string,
      patch: Partial<IngestionTaskVO> | ((task: IngestionTaskVO) => IngestionTaskVO),
    ) => {
      setTasks((currentTasks) =>
        currentTasks.map((task) => {
          if (task.id !== taskId) {
            return task;
          }
          if (typeof patch === "function") {
            return patch(task);
          }
          return {
            ...task,
            ...patch,
          };
        }),
      );
    },
    [],
  );

  return {
    tasks,
    loading,
    hasActiveTasks,
    refreshTasks: fetchTasks,
    updateTask,
  };
}
