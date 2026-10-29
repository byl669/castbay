-- Anonymous usage statistics from CastBay Desktop (POST /api/stats with "platform": "desktop"): one
-- row per installation per day, sent only by programs whose owner turned statistics on. Rows older
-- than a year are deleted daily; turning statistics off deletes the installation's rows
-- (POST /api/stats/delete, which covers this table too).
CREATE TABLE desktop_daily (
  id TEXT NOT NULL,          -- random per-installation UUID, forgotten when statistics are turned off
  day TEXT NOT NULL,         -- YYYY-MM-DD (UTC)
  app TEXT NOT NULL,         -- CastBay Desktop version
  os TEXT NOT NULL,          -- windows / linux
  os_version TEXT NOT NULL,  -- 10.0.19045, ubuntu 26.04
  arch TEXT NOT NULL,
  session TEXT NOT NULL,     -- windows / wayland / x11 / other
  screen TEXT NOT NULL,      -- the biggest screen: 2160p / 1440p / 1080p / 720p / smaller / unknown
  lang TEXT NOT NULL,
  encoders TEXT NOT NULL,    -- JSON array: the encoders that open on the computer
  counts TEXT NOT NULL,      -- JSON object of whitelisted counters
  settings TEXT NOT NULL,    -- JSON object of whitelisted settings
  received TEXT NOT NULL,
  PRIMARY KEY (id, day)
);
CREATE INDEX desktop_daily_day ON desktop_daily (day);
