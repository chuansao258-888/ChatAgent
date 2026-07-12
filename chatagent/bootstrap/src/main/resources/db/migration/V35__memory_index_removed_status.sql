ALTER TABLE memory_item DROP CONSTRAINT IF EXISTS ck_memory_item_index_status;
ALTER TABLE memory_item ADD CONSTRAINT ck_memory_item_index_status
    CHECK (index_status IN ('pending', 'indexed', 'failed', 'removed'));
