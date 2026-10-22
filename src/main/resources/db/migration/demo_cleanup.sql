-- Удаляет всё, что создал demo_seed.sql. Реальные данные не затрагиваются.
-- Смена каскадом уносит маршруты, пункты, фото, флаги, метрики, активность и проверки.
DELETE FROM inventory_count  WHERE id IN (SELECT id FROM demo_ids WHERE kind = 'inv');
DELETE FROM user_notification WHERE id IN (SELECT id FROM demo_ids WHERE kind = 'notif');
DELETE FROM shift_sheet      WHERE id IN (SELECT id FROM demo_ids WHERE kind = 'sheet');
DELETE FROM outlet_note      WHERE id IN (SELECT id FROM demo_ids WHERE kind = 'note');
DELETE FROM shift_session    WHERE id IN (SELECT id FROM demo_ids WHERE kind = 'shift');
DROP TABLE IF EXISTS demo_ids;