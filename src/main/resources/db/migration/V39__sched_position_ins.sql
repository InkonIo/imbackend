-- Позиция «Инсайд» для графика менеджеров (из Excel директора: INS). В автосборку сотрудников не входит.
INSERT INTO sched_position (code, title, sort, planned, note) VALUES
    ('INS', 'Инсайд', 12, FALSE, 'Менеджер на Инсайде (график менеджеров)')
ON CONFLICT (code) DO NOTHING;