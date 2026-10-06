package com.novelagent.writing.application;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.api.ManuscriptLocalEditRequest;
import com.novelagent.writing.api.ManuscriptLocalEditResponse;
import com.novelagent.writing.api.ManuscriptResponse;
import com.novelagent.writing.domain.ManuscriptLocalEditSelection;
import com.novelagent.writing.infrastructure.ManuscriptLocalEditModel;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ManuscriptLocalEditService {
    private final ManuscriptLocalEditStore store;
    private final ManuscriptLocalEditModel model;

    public ManuscriptLocalEditService(ManuscriptLocalEditStore store, ManuscriptLocalEditModel model) {
        this.store = store;
        this.model = model;
    }

    public ManuscriptLocalEditResponse edit(UUID projectId, int chapter, ManuscriptLocalEditRequest request) {
        if (chapter < 1 || !request.authorized() || request.provider() == null || request.sourceRowVersion() == null
                || request.sourceRowVersion() < 0 || request.sourceManuscriptId() == null
                || request.instruction() == null || request.instruction().isBlank() || request.instruction().length() > 2000) {
            throw new IllegalArgumentException("请明确授权局部编辑并填写修改要求");
        }
        var source = store.snapshot(projectId, chapter, request.sourceManuscriptId(), request.sourceRowVersion());
        ManuscriptLocalEditSelection selection = ManuscriptLocalEditSelection.resolve(source.rendered().body(),
                request.selection(), request.offset(), request.occurrence());
        if (request.provider() == ModelProvider.LOCAL_TEMPLATE) {
            return new ManuscriptLocalEditResponse("NOT_ASSESSED", "本地模板不支持局部改写，未创建正文版本。",
                    source.sourceId(), source.sourceRowVersion(), selection.text(), selection.occurrence(),
                    selection.offset(), null, null);
        }
        String replacement = model.replace(projectId, source, selection, request.provider(), request.instruction());
        selection.replace(source.rendered().body(), replacement);
        ManuscriptResponse draft = store.save(source, selection, replacement, request);
        return new ManuscriptLocalEditResponse("DRAFT_CREATED", "局部编辑草稿已保存", source.sourceId(),
                source.sourceRowVersion(), selection.text(), selection.occurrence(), selection.offset(), replacement, draft);
    }
}
