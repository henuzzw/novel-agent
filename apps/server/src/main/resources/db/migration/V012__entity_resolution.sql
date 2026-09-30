CREATE TABLE entity_alias (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    entity_id UUID NOT NULL REFERENCES story_entity(id) ON DELETE CASCADE,
    alias VARCHAR(200) NOT NULL,
    alias_type VARCHAR(32) NOT NULL,
    canon_version_from BIGINT NOT NULL,
    canon_version_to BIGINT,
    source_commit_id UUID REFERENCES canon_commit(id) ON DELETE SET NULL,
    evidence_ref TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_entity_alias UNIQUE (entity_id, alias)
);
CREATE INDEX idx_entity_alias_lookup ON entity_alias (project_id, lower(alias))
    WHERE canon_version_to IS NULL;

CREATE TABLE entity_mention (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    source_commit_id UUID NOT NULL REFERENCES canon_commit(id) ON DELETE CASCADE,
    proposal_id VARCHAR(100) NOT NULL,
    mention_role VARCHAR(32) NOT NULL,
    mention_text VARCHAR(200) NOT NULL,
    requested_entity_id UUID REFERENCES story_entity(id),
    resolved_entity_id UUID NOT NULL REFERENCES story_entity(id),
    resolution_method VARCHAR(32) NOT NULL,
    confidence NUMERIC(4,3) NOT NULL,
    evidence_ref TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_entity_mention_source UNIQUE (source_commit_id, proposal_id, mention_role)
);
CREATE INDEX idx_entity_mention_entity ON entity_mention (project_id, resolved_entity_id, created_at);
