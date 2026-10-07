-- A scoped, audited continuation may add exactly one attempt after the original
-- two repairs. Existing counts/history and the automatic two-repair policy stay intact.
ALTER TABLE shelter.styled_asset_steps DROP CONSTRAINT styled_asset_steps_repair_count_check;
ALTER TABLE shelter.styled_asset_steps ADD CONSTRAINT styled_asset_steps_repair_count_check
    CHECK (repair_count BETWEEN 0 AND 3);
