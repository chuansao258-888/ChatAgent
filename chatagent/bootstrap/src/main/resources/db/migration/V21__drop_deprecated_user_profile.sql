-- Drop the deprecated user_profile table.
-- Long-term user memory is now stored in memory_item (V20).
DROP TABLE IF EXISTS user_profile;
