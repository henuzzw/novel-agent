package com.novelagent.canon.application;

import static com.novelagent.platform.support.Text.isBlank;

import com.novelagent.planning.application.PlanningMaterialSyncService;
import com.novelagent.writing.domain.FactProposal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TypedCanonMaterializer {
    private final CanonFactStore facts;
    private final Map<String, CanonFactProjector> projectors;
    private final List<CanonFactProjector> registeredProjectors;
    private final EntityResolutionService entityResolution;
    private final PlanningMaterialSyncService planningMaterials;

    public TypedCanonMaterializer(
            CanonFactStore facts,
            List<CanonFactProjector> projectors,
            EntityResolutionService entityResolution,
            PlanningMaterialSyncService planningMaterials) {
        this.facts = facts;
        var registered = new LinkedHashMap<String, CanonFactProjector>();
        for (var projector : projectors) {
            for (String type : projector.factTypes()) {
                String key = normalizedType(type);
                if (registered.putIfAbsent(key, projector) != null) {
                    throw new IllegalStateException("正史事实类型重复注册：" + key);
                }
            }
        }
        this.projectors = Map.copyOf(registered);
        this.registeredProjectors = List.copyOf(projectors);
        this.entityResolution = entityResolution;
        this.planningMaterials = planningMaterials;
    }

    public void materialize(UUID projectId, int chapterNumber, UUID commitId, long canonVersion,
            List<FactProposal> facts) {
        for (FactProposal proposal : facts) {
            var projector = projectors.get(normalizedType(proposal.factType()));
            UUID subjectId = projector != null && !projector.requiresSubject(proposal)
                    ? null : resolveSubject(projectId, commitId, canonVersion, proposal);
            UUID factId = this.facts.insert(projectId, commitId, canonVersion, subjectId, proposal);
            if (projector != null) {
                projector.project(new CanonFactProjector.Projection(projectId, chapterNumber, commitId,
                        canonVersion, subjectId, factId, proposal));
            }
            // Unknown types keep their authoritative story_fact without a guessed projection.
        }
        planningMaterials.syncCanonForeshadows(projectId);
    }

    public void retire(UUID commitId, long replacementVersion) {
        facts.retire(commitId, replacementVersion);
        for (var projector : registeredProjectors) {
            projector.retire(commitId, replacementVersion);
        }
        // Entity identities can be shared by later facts, so they are not retired here.
    }

    private UUID resolveSubject(UUID projectId, UUID commitId, long canonVersion,
            FactProposal proposal) {
        var reference = FactEntityReference.subject(proposal);
        return entityResolution.resolve(projectId, commitId, canonVersion,
                proposal.id(), "SUBJECT", reference.type(), reference.name(), reference.id(), proposal.evidence());
    }

    private String normalizedType(String value) {
        return (isBlank(value) ? "未命名" : value.trim()).toUpperCase(Locale.ROOT);
    }
}
