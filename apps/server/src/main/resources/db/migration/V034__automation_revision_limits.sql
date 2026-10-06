ALTER TABLE automation_run
    ADD COLUMN max_auto_revision_rounds integer NOT NULL DEFAULT 0,
    ADD COLUMN max_generation_steps integer NOT NULL DEFAULT 100,
    ADD CONSTRAINT automation_revision_rounds_range CHECK (max_auto_revision_rounds BETWEEN 0 AND 3),
    ADD CONSTRAINT automation_generation_steps_range CHECK (max_generation_steps BETWEEN 1 AND 500),
    ADD CONSTRAINT automation_revision_requires_quality CHECK (max_auto_revision_rounds = 0 OR quality_review_enabled),
    ADD CONSTRAINT automation_revision_requires_model CHECK (max_auto_revision_rounds = 0 OR provider <> 'LOCAL_TEMPLATE');
