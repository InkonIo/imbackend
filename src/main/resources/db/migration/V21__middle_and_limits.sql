-- ============ 1. Промеж в графике: своё время, можно несколько в день ============
ALTER TABLE schedule_slot ADD COLUMN start_time TIME;
ALTER TABLE schedule_slot ADD COLUMN end_time   TIME;

-- раньше клетка = точка + дата + часть дня + роль; теперь у промежа ещё и время начала
ALTER TABLE schedule_slot DROP CONSTRAINT IF EXISTS schedule_slot_outlet_id_slot_date_day_part_slot_role_key;
CREATE UNIQUE INDEX ux_schedule_slot
    ON schedule_slot (outlet_id, slot_date, day_part, slot_role, COALESCE(start_time, '00:00'::time));

-- штатка промежей: 'SERVICE_MANAGER@12:00-21:00;PRODUCTION_MANAGER@11:00-19:00'
ALTER TABLE outlet_staffing ADD COLUMN middle VARCHAR(300) NOT NULL DEFAULT '';

-- ============ 2. «Когда я не могу»: пожелания менеджеров ============
CREATE TABLE staff_limit (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    weekdays   VARCHAR(20)  NOT NULL,          -- '1,2' = пн и вт (1 = пн … 7 = вс)
    day_part   VARCHAR(10),                    -- NULL = весь день
    date_from  DATE,                           -- NULL + NULL = постоянно
    date_to    DATE,
    note       VARCHAR(300),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (date_from IS NULL OR date_to IS NULL OR date_to >= date_from)
);
CREATE INDEX ix_staff_limit_user ON staff_limit (user_id);

-- ============ 3. Чек-листы промежа (только кухня и прилавок) ============
INSERT INTO checklist_template (shift_role, day_part, title)
SELECT v.role, 'MIDDLE', v.title
FROM (VALUES
    ('PRODUCTION_MANAGER', 'Кухня — промеж'),
    ('SERVICE_MANAGER',    'Прилавок — промеж')
) AS v (role, title)
WHERE NOT EXISTS (
    SELECT 1 FROM checklist_template t
    WHERE t.shift_role = v.role AND t.day_part = 'MIDDLE' AND t.outlet_id IS NULL
);

INSERT INTO checklist_section (template_id, title, sort_order)
SELECT t.id, 'Промеж', 1
FROM checklist_template t
WHERE t.day_part = 'MIDDLE' AND t.outlet_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM checklist_section s WHERE s.template_id = t.id);

INSERT INTO checklist_item (section_id, sort_order, title, photo_mode)
SELECT s.id, v.ord, v.title, 'NONE'
FROM checklist_section s
JOIN checklist_template t ON t.id = s.template_id
JOIN (VALUES
    (1, 'Принять участок: порядок, запасы, чистота'),
    (2, 'Передать участок следующему менеджеру')
) AS v (ord, title) ON TRUE
WHERE t.day_part = 'MIDDLE' AND t.outlet_id IS NULL AND s.title = 'Промеж'
  AND NOT EXISTS (SELECT 1 FROM checklist_item i WHERE i.section_id = s.id);