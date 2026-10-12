package com.novelagent.ingest.infrastructure;

import com.novelagent.agent.application.GenerationStoppedException;
import com.novelagent.ingest.domain.ImportPlanningMode;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Short, project-scoped status writes; callers validate ownership and sources before claiming a run. */
@Repository
public class ImportedPlanningStore {
    private final JdbcTemplate jdbc;

    public ImportedPlanningStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void claim(UUID projectId, UUID importId, ImportPlanningMode mode, boolean resume) {
        String allowedStatus = resume ? "planning_status IN ('FAILED', 'CANCELLED')" : "planning_status <> 'GENERATING'";
        if (jdbc.update("UPDATE work_import SET planning_status = 'GENERATING', planning_mode = ?, planning_error = NULL"
                + " WHERE project_id = ? AND id = ? AND " + allowedStatus, mode.name(), projectId, importId) != 1) {
            throw new IllegalArgumentException("该导入的雪花规划正在生成，请勿重复启动");
        }
    }

    public void ready(UUID projectId, UUID importId) {
        jdbc.update("UPDATE work_import SET planning_status = 'PLAN_READY' WHERE project_id = ? AND id = ?", projectId, importId);
    }

    public void failed(UUID projectId, UUID importId, RuntimeException error) {
        String message = Objects.toString(error.getMessage(), error.getClass().getSimpleName());
        jdbc.update("UPDATE work_import SET planning_status = ?, planning_error = ? WHERE project_id = ? AND id = ?",
                error instanceof GenerationStoppedException ? "CANCELLED" : "FAILED",
                message.substring(0, Math.min(1000, message.length())), projectId, importId);
    }
}
