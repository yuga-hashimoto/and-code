CREATE TABLE IF NOT EXISTS reports (
  id TEXT PRIMARY KEY NOT NULL,
  reason TEXT NOT NULL CHECK (reason IN ('HARMFUL', 'HARASSMENT', 'DECEPTIVE', 'OTHER')),
  excerpt TEXT NOT NULL CHECK (length(excerpt) BETWEEN 1 AND 4000),
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS reports_created_at ON reports(created_at);
