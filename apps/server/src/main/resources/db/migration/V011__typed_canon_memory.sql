CREATE TABLE story_entity (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    entity_type VARCHAR(32) NOT NULL,
    canonical_name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    canon_version_from BIGINT NOT NULL,
    canon_version_to BIGINT,
    source_commit_id UUID NOT NULL REFERENCES canon_commit(id) ON DELETE CASCADE,
    evidence_ref TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_story_entity_identity UNIQUE (project_id, entity_type, canonical_name)
);
CREATE INDEX idx_story_entity_active ON story_entity (project_id, entity_type)
    WHERE canon_version_to IS NULL;

CREATE TABLE story_fact (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    proposal_id VARCHAR(100) NOT NULL,
    fact_type VARCHAR(50) NOT NULL,
    subject_entity_id UUID REFERENCES story_entity(id),
    subject_text VARCHAR(300) NOT NULL,
    predicate VARCHAR(300) NOT NULL,
    object_text TEXT NOT NULL,
    canon_version_from BIGINT NOT NULL,
    canon_version_to BIGINT,
    source_commit_id UUID NOT NULL REFERENCES canon_commit(id) ON DELETE CASCADE,
    evidence_ref TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_story_fact_proposal UNIQUE (source_commit_id, proposal_id)
);
CREATE INDEX idx_story_fact_current ON story_fact (project_id, canon_version_from)
    WHERE canon_version_to IS NULL;

CREATE TABLE story_event (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    source_fact_id UUID NOT NULL UNIQUE REFERENCES story_fact(id) ON DELETE CASCADE,
    title VARCHAR(300) NOT NULL,
    summary TEXT NOT NULL,
    story_time_text VARCHAR(200),
    narrative_chapter INTEGER NOT NULL,
    importance VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    canon_version_from BIGINT NOT NULL,
    canon_version_to BIGINT,
    source_commit_id UUID NOT NULL REFERENCES canon_commit(id) ON DELETE CASCADE,
    evidence_ref TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_story_event_timeline ON story_event (project_id, narrative_chapter, canon_version_from);

CREATE TABLE entity_state_change (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    entity_id UUID NOT NULL REFERENCES story_entity(id),
    source_fact_id UUID NOT NULL UNIQUE REFERENCES story_fact(id) ON DELETE CASCADE,
    field_key VARCHAR(100) NOT NULL,
    before_value JSONB,
    after_value JSONB NOT NULL,
    story_time_text VARCHAR(200),
    narrative_chapter INTEGER NOT NULL,
    canon_version_from BIGINT NOT NULL,
    canon_version_to BIGINT,
    source_commit_id UUID NOT NULL REFERENCES canon_commit(id) ON DELETE CASCADE,
    evidence_ref TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_entity_state_history ON entity_state_change
    (project_id, entity_id, field_key, narrative_chapter, canon_version_from);

CREATE TABLE story_relationship (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    source_entity_id UUID NOT NULL REFERENCES story_entity(id),
    target_entity_id UUID NOT NULL REFERENCES story_entity(id),
    source_fact_id UUID NOT NULL UNIQUE REFERENCES story_fact(id) ON DELETE CASCADE,
    relation_type VARCHAR(100) NOT NULL,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    canon_version_from BIGINT NOT NULL,
    canon_version_to BIGINT,
    source_commit_id UUID NOT NULL REFERENCES canon_commit(id) ON DELETE CASCADE,
    evidence_ref TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_story_relationship_distinct CHECK (source_entity_id <> target_entity_id)
);
CREATE INDEX idx_story_relationship_source ON story_relationship (project_id, source_entity_id);
CREATE INDEX idx_story_relationship_target ON story_relationship (project_id, target_entity_id);

CREATE TABLE character_knowledge (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    character_id UUID NOT NULL REFERENCES story_entity(id),
    fact_id UUID NOT NULL REFERENCES story_fact(id),
    knowledge_type VARCHAR(32) NOT NULL,
    belief_truth VARCHAR(32) NOT NULL,
    confidence NUMERIC(4,3),
    narrative_chapter INTEGER NOT NULL,
    canon_version_from BIGINT NOT NULL,
    canon_version_to BIGINT,
    source_commit_id UUID NOT NULL REFERENCES canon_commit(id) ON DELETE CASCADE,
    evidence_ref TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_character_knowledge_source UNIQUE (character_id, fact_id, source_commit_id)
);
CREATE INDEX idx_character_knowledge_current ON character_knowledge (project_id, character_id)
    WHERE canon_version_to IS NULL;

CREATE TABLE foreshadow (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    source_fact_id UUID NOT NULL UNIQUE REFERENCES story_fact(id) ON DELETE CASCADE,
    title VARCHAR(300) NOT NULL,
    target_effect TEXT,
    current_status VARCHAR(32) NOT NULL,
    planned_resolve_chapter INTEGER,
    canon_version_from BIGINT NOT NULL,
    canon_version_to BIGINT,
    source_commit_id UUID NOT NULL REFERENCES canon_commit(id) ON DELETE CASCADE,
    evidence_ref TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_foreshadow_status ON foreshadow (project_id, current_status, planned_resolve_chapter)
    WHERE canon_version_to IS NULL;
