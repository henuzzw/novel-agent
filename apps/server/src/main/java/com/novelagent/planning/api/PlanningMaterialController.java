package com.novelagent.planning.api;

import com.novelagent.planning.application.PlanningMaterialSyncService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 规划资料同步。
 *
 * <p>显式同步已发布圣经、大纲及可用规划资料，提供人物、关系和来源查询。同步是确定性操作，不调用模型、不制造正文事实。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/planning-materials")
public class PlanningMaterialController {
    private final PlanningMaterialSyncService service;
    public PlanningMaterialController(PlanningMaterialSyncService service) { this.service = service; }

    /**
     * 同步当前有效规划的明确资料，保留作者编辑和来源；同步不会生成正文事实。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @PostMapping("/actions/sync")
    public ResponseEntity<Void> sync(@PathVariable UUID projectId) {
        service.syncCurrent(projectId);
        return ResponseEntity.noContent().build();
    }
    /**
     * 查询当前来源保存的人物规划快照；完整蓝图与独立可编辑档案各有来源和用途。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping("/characters")
    public ResponseEntity<List<PlanningMaterialSyncService.CharacterSnapshot>> characters(@PathVariable UUID projectId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.characters(projectId));
    }
    /**
     * 返回查询范围内的人物关系，并保留规划与已发生事实各自的来源，不自动生成新关系。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param characterId 稳定人物实体 ID，不以显示姓名作为主键。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping("/relationships")
    public ResponseEntity<List<PlanningMaterialSyncService.PlannedRelationship>> relationships(@PathVariable UUID projectId,
            @RequestParam(required = false) UUID characterId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.relationships(projectId, characterId));
    }
    /**
     * 查询规划资料及台账的来源版本、有效性和关联信息，供作者判断是否需要重新同步。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping("/plan-origins")
    public ResponseEntity<List<PlanningMaterialSyncService.PlanOrigin>> origins(@PathVariable UUID projectId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.origins(projectId));
    }
}
