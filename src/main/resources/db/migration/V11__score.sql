ALTER TABLE shift_metrics ADD COLUMN confirmed_high   INTEGER NOT NULL DEFAULT 0;
ALTER TABLE shift_metrics ADD COLUMN confirmed_medium INTEGER NOT NULL DEFAULT 0;
CREATE INDEX ix_metrics_role_date ON shift_metrics (shift_role, shift_date);