package com.novelagent.project.api;

import com.novelagent.project.application.CreativeStrategyService;
import com.novelagent.project.domain.CreativeStrategy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 创作策略。
 *
 * <p>查询并保存 STANDARD 或 FANQIE_GRIPPING 策略，使用项目行版本防止覆盖。策略与表达风格独立，切换不授权重写已有作品。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/settings/creative-strategy")
public class CreativeStrategyController {
    private final CreativeStrategyService service;

    public CreativeStrategyController(CreativeStrategyService service) {
        this.service = service;
    }

    public record UpdateRequest(@NotNull CreativeStrategy strategy, @NotNull @PositiveOrZero Long version) {}

    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @GetMapping
    public CreativeStrategyService.State get(@PathVariable UUID projectId) {
        return service.get(projectId);
    }

    /**
     * 保存作者提交的编辑内容，并遵循当前业务状态及预期版本约束；不隐式触发模型重新生成。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PutMapping
    public CreativeStrategyService.State update(@PathVariable UUID projectId, @Valid @RequestBody UpdateRequest request) {
        return service.update(projectId, request.strategy(), request.version());
    }
}
