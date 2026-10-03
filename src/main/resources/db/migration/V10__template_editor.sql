-- маршрут может принадлежать точке (outlet_id) или быть общим (NULL)
ALTER TABLE checklist_template ADD COLUMN outlet_id BIGINT REFERENCES outlet (id) ON DELETE CASCADE;
ALTER TABLE checklist_template DROP CONSTRAINT IF EXISTS checklist_template_shift_role_day_part_key;
CREATE UNIQUE INDEX ux_template_global ON checklist_template (shift_role, day_part) WHERE outlet_id IS NULL;
CREATE UNIQUE INDEX ux_template_outlet ON checklist_template (shift_role, day_part, outlet_id) WHERE outlet_id IS NOT NULL;

-- уникальность порядка мешает менять разделы местами
ALTER TABLE checklist_section DROP CONSTRAINT IF EXISTS checklist_section_template_id_sort_order_key;

-- инструкция «как делать»
ALTER TABLE checklist_item ADD COLUMN instructions VARCHAR(2000);
ALTER TABLE checklist_run_item ADD COLUMN instructions VARCHAR(2000);