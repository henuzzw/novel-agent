package com.novelagent.project.api;

import com.novelagent.project.application.GlobalModelCatalogService;
import com.novelagent.project.application.GlobalModelSettingsService;
import com.novelagent.project.domain.GlobalModelSettings;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局模型配置。
 *
 * <p>提供当前用户默认供应商、模型及推理强度的查询和保存，以及可选模型目录。配置跨项目生效，后续出站调用各自冻结实际参数。</p>
 */
@RestController
@RequestMapping("/api/v1/settings/model")
public class GlobalModelSettingsController {
    private final GlobalModelSettingsService settings;
    private final GlobalModelCatalogService catalog;

    public GlobalModelSettingsController(GlobalModelSettingsService settings, GlobalModelCatalogService catalog) {
        this.settings = settings;
        this.catalog = catalog;
    }

    /**
     * 查询当前用户模型设置，无持久化记录时返回配置默认值，不为读取自动写数据库。
     */
    @GetMapping
    public GlobalModelSettings get() {
        return settings.get();
    }

    /**
     * 按设置行版本插入或更新当前用户配置，未实际写入则报告版本冲突；返回递增后的配置版本。
     *
     * @param value 当前业务对象或作者编辑值，具体类型由方法签名确定。
     */
    @PutMapping
    public GlobalModelSettings update(@RequestBody GlobalModelSettings value) {
        return settings.update(catalog.validate(value));
    }

    /**
     * 返回当前接入可用模型目录供界面选择，不进行小说生成。
     *
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping("/chatgpt-models")
    public List<GlobalModelCatalogService.ModelOption> models() {
        return catalog.chatGptModels();
    }
}
