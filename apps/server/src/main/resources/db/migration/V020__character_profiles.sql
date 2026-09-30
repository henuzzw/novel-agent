CREATE TABLE character_profile (
    character_id UUID PRIMARY KEY REFERENCES story_entity(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    gender VARCHAR(40),
    age_description VARCHAR(100),
    identity_text TEXT,
    appearance TEXT,
    background TEXT,
    external_personality TEXT,
    internal_personality TEXT,
    core_desire TEXT,
    fear TEXT,
    flaw TEXT,
    values_text TEXT,
    speech_style TEXT,
    behavior_habits TEXT,
    secret_text TEXT,
    character_arc TEXT,
    behavior_boundaries TEXT,
    notes TEXT,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_character_profile_project ON character_profile(project_id);

INSERT INTO character_profile(character_id, project_id)
SELECT id, project_id
FROM story_entity
WHERE entity_type = 'CHARACTER' AND canon_version_to IS NULL
ON CONFLICT (character_id) DO NOTHING;
