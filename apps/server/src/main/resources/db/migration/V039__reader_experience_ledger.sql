CREATE TABLE reader_experience_plan (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    kind VARCHAR(24) NOT NULL CHECK (kind IN ('PROMISE', 'FORESHADOW')),
    title VARCHAR(200) NOT NULL,
    promise_text TEXT NOT NULL,
    setup_text TEXT NOT NULL,
    payoff_text TEXT NOT NULL,
    aftermath_text TEXT NOT NULL,
    planned_chapter INTEGER CHECK (planned_chapter > 0),
    schema_version VARCHAR(50) NOT NULL DEFAULT 'reader-experience/1',
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, project_id)
);
CREATE INDEX idx_reader_experience_project ON reader_experience_plan(project_id, created_at, id) WHERE NOT deleted;

CREATE TABLE reader_experience_event (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    plan_id UUID NOT NULL,
    entry_version BIGINT NOT NULL CHECK (entry_version > 0),
    plan_snapshot JSONB NOT NULL,
    state VARCHAR(24) NOT NULL CHECK (state IN ('SET_UP', 'REINFORCED', 'PAYOFF', 'ABANDONED', 'OPEN')),
    manuscript_id UUID NOT NULL REFERENCES manuscript_version(id),
    manuscript_row_version BIGINT NOT NULL CHECK (manuscript_row_version >= 0),
    source_fingerprint VARCHAR(64) NOT NULL CHECK (source_fingerprint ~ '^[0-9a-f]{64}$'),
    chapter_number INTEGER NOT NULL CHECK (chapter_number > 0),
    evidence TEXT NOT NULL CHECK (length(btrim(evidence)) > 0),
    evidence_tokenized TEXT NOT NULL,
    author_note TEXT NOT NULL,
    chapter_canon_commit_id UUID REFERENCES canon_commit(id),
    canon_at_submission BOOLEAN NOT NULL,
    submitted_by UUID NOT NULL,
    schema_version VARCHAR(50) NOT NULL DEFAULT 'reader-experience-event/1',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (plan_id, project_id) REFERENCES reader_experience_plan(id, project_id) ON DELETE CASCADE,
    UNIQUE (plan_id, entry_version)
);
CREATE INDEX idx_reader_experience_event ON reader_experience_event(project_id, plan_id, entry_version);

CREATE FUNCTION reader_experience_validate_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM manuscript_version m JOIN novel_project p ON p.id = m.project_id
        WHERE m.id = NEW.manuscript_id AND m.project_id = NEW.project_id
          AND m.status = 'AUTHOR_ACCEPTED' AND m.row_version = NEW.manuscript_row_version
          AND m.chapter_number = NEW.chapter_number
          AND p.owner_id = NEW.submitted_by
    ) THEN
        RAISE EXCEPTION 'Reader experience requires an owned, author-accepted manuscript with matching row version'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.chapter_canon_commit_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM canon_commit c WHERE c.id = NEW.chapter_canon_commit_id
          AND c.project_id = NEW.project_id AND c.chapter_number = NEW.chapter_number
    ) THEN
        RAISE EXCEPTION 'Reader experience canon source belongs to another project or chapter' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER reader_experience_source_guard BEFORE INSERT ON reader_experience_event
    FOR EACH ROW EXECUTE FUNCTION reader_experience_validate_source();

CREATE TABLE reader_experience_mutation (
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    submitted_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, request_id),
    FOREIGN KEY (plan_id, project_id) REFERENCES reader_experience_plan(id, project_id) ON DELETE CASCADE
);
