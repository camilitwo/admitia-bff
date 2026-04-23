ALTER TABLE users ADD COLUMN IF NOT EXISTS firebase_uid VARCHAR(128) UNIQUE;
CREATE INDEX IF NOT EXISTS idx_users_firebase_uid ON users(firebase_uid);
