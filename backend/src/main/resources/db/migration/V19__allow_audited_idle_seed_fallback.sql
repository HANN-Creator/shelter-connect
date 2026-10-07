-- Preserve every recorded attempt. Only IDLE may record a fourth repair when an
-- explicit hash-bound continuation replaces a legacy mirrored idle with its seed.
-- The service still enforces the prior strategy, failed verdict and single grant.
ALTER TABLE shelter.styled_asset_steps DROP CONSTRAINT styled_asset_steps_repair_count_check;
ALTER TABLE shelter.styled_asset_steps ADD CONSTRAINT styled_asset_steps_repair_count_check
    CHECK (repair_count BETWEEN 0 AND 3 OR (action = 'IDLE' AND repair_count = 4));
