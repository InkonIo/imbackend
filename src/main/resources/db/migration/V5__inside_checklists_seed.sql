INSERT INTO checklist_template (shift_role, day_part, title) VALUES
    ('INSIDE', 'MORNING', 'Инсайд — утро'),
    ('INSIDE', 'EVENING', 'Инсайд — вечер');

INSERT INTO checklist_section (template_id, title, sort_order)
SELECT t.id, s.title, s.ord
FROM checklist_template t
JOIN (VALUES
    ('MORNING', 'До смены', 1),
    ('MORNING', 'Открытие (готовность к 10:00)', 2),
    ('MORNING', 'Контроль в течение смены', 3),
    ('MORNING', 'Распорядок дня', 4),
    ('MORNING', 'Регламент дня', 5),
    ('EVENING', 'Приём смены', 1),
    ('EVENING', 'Контроль в течение смены', 2),
    ('EVENING', 'Отмывка с 22:00', 3),
    ('EVENING', 'Закрытие (23:00–00:00)', 4),
    ('EVENING', 'Регламент дня', 5)
) AS s (day_part, title, ord) ON s.day_part = t.day_part
WHERE t.shift_role = 'INSIDE';

INSERT INTO checklist_item (section_id, sort_order, title, duration_min, due_from, due_to,
                            photo_mode, weekday, director_review)
SELECT s.id, i.ord, i.title, i.dur, i.dfrom::time, i.dto::time, i.photo, i.wd, i.review
FROM (VALUES
    -- ===== УТРО =====
    -- До смены
    ('MORNING', 1, 1, 'Проверить наличие туалетной бумаги, мыла и расходников', NULL, NULL, '08:00', 'NONE', NULL, FALSE),
    ('MORNING', 1, 2, 'Включить бактерицидные лампы', NULL, NULL, '08:00', 'NONE', NULL, FALSE),
    ('MORNING', 1, 3, 'Заполнить чек-лист и распечатать бланки на день', NULL, NULL, '08:00', 'NONE', NULL, FALSE),
    -- Открытие
    ('MORNING', 2, 1, 'Принять ночников: масла (пустые бочки), серые тележки и ножки, порядок хим. шкафа, чистота перчаток', 30, NULL, NULL, 'REQUIRED', NULL, TRUE),
    ('MORNING', 2, 2, 'Принять сейф', 5, NULL, NULL, 'NONE', NULL, FALSE),
    ('MORNING', 2, 3, 'Принять инвентаризацию', 20, NULL, NULL, 'NONE', NULL, FALSE),
    ('MORNING', 2, 4, 'Включить оборудование', 5, NULL, NULL, 'NONE', NULL, FALSE),
    ('MORNING', 2, 5, 'Офис: открыть кассовую смену, зарегистрировать кассира, открыть шторку, включить вывеску', 10, NULL, NULL, 'NONE', NULL, FALSE),
    ('MORNING', 2, 6, 'Сроки годности: пополнение этажа, норма складирования, ротация, сроки хранения первичной и вторичной', 30, NULL, NULL, 'NONE', NULL, FALSE),
    ('MORNING', 2, 7, 'Журналы: журнал жира, ПТО, чек-листы менеджеров участков, ЕКЛБП, бракеражный', 20, NULL, NULL, 'NONE', NULL, FALSE),
    ('MORNING', 2, 8, 'Ресторан готов к открытию', NULL, NULL, '10:00', 'NONE', NULL, FALSE),
    -- Контроль в течение смены
    ('MORNING', 3, 1, 'Психрометр', NULL, NULL, NULL, 'REQUIRED', NULL, FALSE),
    ('MORNING', 3, 2, 'Проверить ловушки (до брейка)', NULL, NULL, NULL, 'REQUIRED', NULL, FALSE),
    ('MORNING', 3, 3, 'Проверить концентрат DR (до брейка)', 5, NULL, NULL, 'REQUIRED', NULL, FALSE),
    ('MORNING', 3, 4, 'Бланк с подписями по почасовой мойке рук', NULL, NULL, NULL, 'REQUIRED', NULL, FALSE),
    ('MORNING', 3, 5, 'Температура салата', NULL, NULL, '12:00', 'REQUIRED', NULL, FALSE),
    ('MORNING', 3, 6, 'Составить и подтвердить КЛН', NULL, NULL, NULL, 'REQUIRED', NULL, FALSE),
    ('MORNING', 3, 7, 'Разложить партянки за прошлый день по папкам, записать время ночников', NULL, NULL, NULL, 'REQUIRED', NULL, FALSE),
    -- Распорядок дня
    ('MORNING', 4, 1, 'Брейк', 30, NULL, NULL, 'NONE', NULL, FALSE),
    ('MORNING', 4, 2, 'Проверить почту и письма', 5, NULL, NULL, 'NONE', NULL, FALSE),
    ('MORNING', 4, 3, 'Обход этажа и складов', 30, NULL, NULL, 'REQUIRED', NULL, FALSE),
    ('MORNING', 4, 4, 'Надуть шары', NULL, '11:00', NULL, 'NONE', NULL, FALSE),
    ('MORNING', 4, 5, 'Регламентные работы инсайда (утро)', 30, NULL, NULL, 'NONE', NULL, FALSE),
    ('MORNING', 4, 6, 'E-predict: посмотреть планирование конфигурации', 30, '12:00', '12:30', 'NONE', NULL, FALSE),
    ('MORNING', 4, 7, 'Регламентные работы ДЛК', 5, NULL, NULL, 'ON_PROBLEM', NULL, FALSE),
    ('MORNING', 4, 8, 'Работа на этаже (пик тайм)', NULL, NULL, NULL, 'NONE', NULL, FALSE),
    ('MORNING', 4, 9, 'Проверочная: закрыть сумку, проверить бланки MR', 30, '15:00', '15:30', 'NONE', NULL, FALSE),
    ('MORNING', 4, 10, 'Пересдать смену вечеру: перекрытие смен, отчёт', NULL, '16:00', NULL, 'NONE', NULL, FALSE),
    -- Регламент дня (по дням недели)
    ('MORNING', 5, 1, 'Помыть серые бадейки', NULL, NULL, NULL, 'ON_PROBLEM', 1, FALSE),
    ('MORNING', 5, 2, 'Отмыть кухонные бадейки от клея', NULL, NULL, NULL, 'ON_PROBLEM', 3, FALSE),
    ('MORNING', 5, 3, 'Помыть куриные и рыбные лотки', NULL, NULL, NULL, 'ON_PROBLEM', 5, FALSE),
    ('MORNING', 5, 4, 'Отмыть кухонные бадейки от клея', NULL, NULL, NULL, 'ON_PROBLEM', 7, FALSE),

    -- ===== ВЕЧЕР =====
    -- Приём смены
    ('EVENING', 1, 1, 'Написать чек-лист до начала смены', NULL, NULL, NULL, 'NONE', NULL, FALSE),
    ('EVENING', 1, 2, 'Принять смену', 30, NULL, NULL, 'NONE', NULL, FALSE),
    ('EVENING', 1, 3, 'Принять moneyroom', NULL, NULL, NULL, 'NONE', NULL, FALSE),
    ('EVENING', 1, 4, 'Проверить ПТО, журнал жира и shift report на заполненность', NULL, NULL, NULL, 'NONE', NULL, FALSE),
    -- Контроль в течение смены
    ('EVENING', 2, 1, 'Обход этажа: сроки годности, пополнение, норма складирования, ротация, сроки хранения', 30, NULL, NULL, 'NONE', NULL, FALSE),
    ('EVENING', 2, 2, 'Проверить E-predict', NULL, NULL, NULL, 'NONE', NULL, FALSE),
    ('EVENING', 2, 3, 'Читать и отвечать на почту', NULL, NULL, NULL, 'NONE', NULL, FALSE),
    ('EVENING', 2, 4, 'Психрометр', NULL, NULL, NULL, 'REQUIRED', NULL, FALSE),
    ('EVENING', 2, 5, 'Проверить ловушки (до брейка)', NULL, NULL, NULL, 'REQUIRED', NULL, FALSE),
    ('EVENING', 2, 6, 'Проверить концентрат DR (до брейка)', 5, NULL, NULL, 'REQUIRED', NULL, FALSE),
    ('EVENING', 2, 7, 'Бланк с подписями по почасовой мойке рук', NULL, NULL, NULL, 'REQUIRED', NULL, FALSE),
    ('EVENING', 2, 8, 'Температура салата', NULL, NULL, '17:00', 'REQUIRED', NULL, FALSE),
    ('EVENING', 2, 9, 'Температура салата', NULL, NULL, '20:00', 'REQUIRED', NULL, FALSE),
    ('EVENING', 2, 10, 'Составить и подтвердить КЛН', NULL, NULL, NULL, 'REQUIRED', NULL, FALSE),
    ('EVENING', 2, 11, 'Сыр чеддер и эмменталь в первичной упаковке с таймером', NULL, NULL, NULL, 'REQUIRED', NULL, FALSE),
    ('EVENING', 2, 12, 'Проверить архивацию всех бланков и списаний', NULL, NULL, NULL, 'ON_PROBLEM', NULL, FALSE),
    ('EVENING', 2, 13, 'Выдержка десертов и тортильи на разморозку', NULL, '22:00', NULL, 'REQUIRED', NULL, FALSE),
    ('EVENING', 2, 14, 'Проверочная: закрыть сумку, проверить бланки MR', NULL, NULL, NULL, 'NONE', NULL, FALSE),
    ('EVENING', 2, 15, 'Принять регламент у ДЛК', NULL, NULL, NULL, 'REQUIRED', NULL, FALSE),
    -- Отмывка с 22:00
    ('EVENING', 3, 1, 'Комбимашина', NULL, '22:00', NULL, 'NONE', NULL, FALSE),
    ('EVENING', 3, 2, 'Кофемашина и бункер для молока', NULL, '22:00', NULL, 'NONE', NULL, FALSE),
    ('EVENING', 3, 3, 'Башня напитков', NULL, '22:00', NULL, 'NONE', NULL, FALSE),
    ('EVENING', 3, 4, 'Поверхность зоны кассы', NULL, '22:00', NULL, 'NONE', NULL, FALSE),
    ('EVENING', 3, 5, 'Подзаправочный стол', NULL, '22:00', NULL, 'REQUIRED', NULL, FALSE),
    -- Закрытие
    ('EVENING', 4, 1, 'Заполнить журнал жира, замер масла', NULL, '23:00', NULL, 'NONE', NULL, FALSE),
    ('EVENING', 4, 2, 'Химия для ночников есть, напомнить про перчатки и маски, минимизировать подзаправочный стол', NULL, '23:00', NULL, 'NONE', NULL, FALSE),
    ('EVENING', 4, 3, 'Ежедневная инвентаризация', NULL, NULL, NULL, 'NONE', NULL, FALSE),
    ('EVENING', 4, 4, 'Снять кассу, проверить депозит, закрыть кассовую смену', NULL, NULL, NULL, 'NONE', NULL, FALSE),
    ('EVENING', 4, 5, 'В 00:00 выключить оборудование, шторки и вывеску', NULL, NULL, NULL, 'REQUIRED', NULL, FALSE),
    ('EVENING', 4, 6, 'Отправить отчёт за смену', NULL, NULL, NULL, 'NONE', NULL, FALSE),
    -- Регламент дня (по дням недели)
    ('EVENING', 5, 1, 'Помыть стеллажи для булочек (3 шт)', NULL, NULL, NULL, 'ON_PROBLEM', 1, FALSE),
    ('EVENING', 5, 2, 'Помыть красные лотки', NULL, NULL, NULL, 'ON_PROBLEM', 2, FALSE),
    ('EVENING', 5, 3, 'Помыть мясные лотки', NULL, NULL, NULL, 'ON_PROBLEM', 4, FALSE),
    ('EVENING', 5, 4, 'Помыть контейнер для фри и топинги', NULL, NULL, NULL, 'ON_PROBLEM', 7, FALSE)
) AS i (day_part, sec, ord, title, dur, dfrom, dto, photo, wd, review)
JOIN checklist_template t ON t.shift_role = 'INSIDE' AND t.day_part = i.day_part
JOIN checklist_section s ON s.template_id = t.id AND s.sort_order = i.sec;