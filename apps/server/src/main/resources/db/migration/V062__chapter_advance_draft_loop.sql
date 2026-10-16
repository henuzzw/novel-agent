ALTER TABLE automation_run
    ADD COLUMN max_loop_rounds integer NOT NULL DEFAULT 3 CHECK (max_loop_rounds BETWEEN 1 AND 10),
    ADD COLUMN current_loop_id uuid,
    ADD COLUMN waiting_for_publication boolean NOT NULL DEFAULT false,
    ADD COLUMN auto_publish boolean NOT NULL DEFAULT false,
    ADD COLUMN auto_memory boolean NOT NULL DEFAULT true;

ALTER TABLE draft_loop_run
    ADD COLUMN automation_id uuid REFERENCES automation_run(id) ON DELETE CASCADE,
    ADD COLUMN automation_attempt integer;

CREATE UNIQUE INDEX draft_loop_automation_attempt_chapter
    ON draft_loop_run(automation_id, automation_attempt, chapter_number) WHERE automation_id IS NOT NULL;
CREATE INDEX automation_waiting_publication ON automation_run(updated_at)
    WHERE waiting_for_publication AND status = 'WAITING_FOR_USER';
