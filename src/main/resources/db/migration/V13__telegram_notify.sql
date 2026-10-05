ALTER TABLE checklist_item     ADD COLUMN telegram_notify BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE checklist_run_item ADD COLUMN telegram_notify BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE checklist_item SET telegram_notify = TRUE WHERE photo_mode = 'REQUIRED';