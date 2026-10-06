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

@RestController
@RequestMapping("/api/v1/settings/model")
public class GlobalModelSettingsController {
    private final GlobalModelSettingsService settings;
    private final GlobalModelCatalogService catalog;

    public GlobalModelSettingsController(GlobalModelSettingsService settings, GlobalModelCatalogService catalog) {
        this.settings = settings;
        this.catalog = catalog;
    }

    @GetMapping
    public GlobalModelSettings get() {
        return settings.get();
    }

    @PutMapping
    public GlobalModelSettings update(@RequestBody GlobalModelSettings value) {
        return settings.update(catalog.validate(value));
    }

    @GetMapping("/chatgpt-models")
    public List<GlobalModelCatalogService.ModelOption> models() {
        return catalog.chatGptModels();
    }
}
