ALTER TABLE automation_run
    ADD COLUMN quality_review_enabled boolean NOT NULL DEFAULT false;
