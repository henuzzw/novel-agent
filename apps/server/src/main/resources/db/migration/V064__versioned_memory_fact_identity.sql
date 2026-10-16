ALTER TABLE story_fact DROP CONSTRAINT uq_story_fact_proposal;
ALTER TABLE story_fact ADD CONSTRAINT uq_story_fact_proposal_version
    UNIQUE (source_commit_id, proposal_id, canon_version_from);
CREATE UNIQUE INDEX uq_story_fact_active_proposal
    ON story_fact (source_commit_id, proposal_id)
    WHERE canon_version_to IS NULL;
