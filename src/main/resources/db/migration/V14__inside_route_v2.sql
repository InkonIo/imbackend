-- 1. Чистим пункты общих маршрутов инсайда (в прошлых сменах хранятся копии, история не пострадает)
DELETE FROM checklist_section
WHERE template_id IN (SELECT id FROM checklist_template WHERE shift_role = 'INSIDE' AND outlet_id IS NULL);

-- 2. Копии для точек выключаем, чтобы везде работал новый общий маршрут
UPDATE checklist_template SET active = FALSE WHERE shift_role = 'INSIDE' AND outlet_id IS NOT NULL;

-- 3. Разделы
INSERT INTO checklist_section (template_id, title, sort_order)
SELECT t.id, s.title, s.ord
FROM checklist_template t
JOIN (VALUES
    ('MORNING', 'Открытие 08:00–10:00', 1),
    ('MORNING', 'В течение смены 10:00–16:30', 2),
    ('MORNING', 'Без привязки ко времени', 3),
    ('MORNING', 'Регламент дня', 4),
    ('EVENING', 'Приём смены 16:00–17:00', 1),
    ('EVENING', 'Вечер 17:00–22:00', 2),
    ('EVENING', 'Закрытие 22:00–00:30', 3),
    ('EVENING', 'Без привязки ко времени', 4),
    ('EVENING', 'Регламент дня', 5)
) AS s (day_part, title, ord) ON s.day_part = t.day_part
WHERE t.shift_role = 'INSIDE' AND t.outlet_id IS NULL;

-- 4. Пункты
-- колонки: часть дня, раздел, порядок, текст, норма мин, с, до, фото, день недели (1=пн…7=вс), проверяет директор, в Telegram
INSERT INTO checklist_item (section_id, sort_order, title, duration_min, due_from, due_to,
                            photo_mode, weekday, director_review, telegram_notify)
SELECT s.id, i.ord, i.title, i.dur, i.dfrom::time, i.dto::time, i.photo, i.wd, i.review, i.tg
FROM (VALUES
    -- ===================== УТРО =====================
    -- Открытие 08:00–10:00
    ('MORNING', 1, 1,  'Включить бактерицидные лампы', NULL, '08:00', '08:05', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 1, 2,  'Во всех раковинах есть мыло, бумажные полотенца, туалетная бумага', NULL, '08:05', '08:10', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 1, 3,  'Принять ночников: серые тележки (особенно ножки), порядок хим. шкафа, чистота перчаток', 20, '08:10', '08:30', 'REQUIRED', NULL, TRUE, TRUE),
    ('MORNING', 1, 4,  'Проверить масло и отходы на пустые бочки, оставить заявку', NULL, '08:30', '08:35', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 1, 5,  'Заполнить чек-лист, распечатать бланки на день', NULL, '08:35', '09:00', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 1, 6,  'Принять сейф, заполнить бланк MR', NULL, '08:35', '09:00', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 1, 7,  'Заполнить shift report', NULL, '08:35', '09:00', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 1, 8,  'Проверить журнал жира и ПТО', NULL, '08:35', '09:00', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 1, 9,  'Принять инвентаризацию (фото РОСВ), сверить остатки на складах по РОСВ', 20, '09:00', '09:20', 'REQUIRED', NULL, FALSE, FALSE),
    ('MORNING', 1, 10, 'Склады: сроки и правильность ротации фреш, норма складирования', NULL, '09:20', '09:50', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 1, 11, 'Этаж: проверить сроки, заполнить раздел «Сроки» в чек-листе', NULL, '09:20', '09:50', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 1, 12, 'Обход ДЛК по таблице 24/2', NULL, '09:20', '09:50', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 1, 13, 'iiko: кассовые смены за прошлый день закрыты', NULL, '09:50', '10:00', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 1, 14, 'IM Space: открыть кассовую смену на сегодня', NULL, '09:50', '10:00', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 1, 15, 'Зарегистрировать кассира, открыть шторку, включить вывеску', NULL, '09:50', '10:00', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 1, 16, 'Проверить стоп-лист', NULL, '09:50', '10:00', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 1, 17, 'Заполнить бракераж', NULL, '09:50', '10:00', 'NONE', NULL, FALSE, FALSE),
    -- В течение смены 10:00–16:30
    ('MORNING', 2, 1,  'Выключить бактерицидные лампы', NULL, '10:00', '10:30', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 2, 2,  'ЕКЛБП, мойка рук, чек-листы менеджеров участков (3 фото)', NULL, '10:00', '10:30', 'REQUIRED', NULL, FALSE, FALSE),
    ('MORNING', 2, 3,  'Проверить концентрат DR', NULL, '10:00', '10:30', 'REQUIRED', NULL, FALSE, FALSE),
    ('MORNING', 2, 4,  'Проверить ловушки', NULL, '10:00', '10:30', 'REQUIRED', NULL, FALSE, FALSE),
    ('MORNING', 2, 5,  'Психрометр', NULL, '10:00', '10:30', 'REQUIRED', NULL, FALSE, FALSE),
    ('MORNING', 2, 6,  'Портянки за прошлый день в папки, записать время ночников', NULL, '10:00', '10:30', 'REQUIRED', NULL, FALSE, FALSE),
    ('MORNING', 2, 7,  'Температура салата: подзаправочный стол, кулер, холодильник (3 фото)', NULL, '10:00', '10:30', 'REQUIRED', NULL, FALSE, FALSE),
    ('MORNING', 2, 8,  'Надуть шары', NULL, '10:00', '10:30', 'REQUIRED', NULL, FALSE, FALSE),
    ('MORNING', 2, 9,  'Обход с ДЛК: пополнение по маршруту', NULL, '10:30', '10:50', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 2, 10, 'Лук вытащили на восстановление? Есть размороженные десерты?', NULL, '10:30', '10:50', 'REQUIRED', NULL, FALSE, FALSE),
    ('MORNING', 2, 11, 'Чистота складов (3 фото)', NULL, '10:30', '10:50', 'REQUIRED', NULL, FALSE, FALSE),
    ('MORNING', 2, 12, 'Ежечасный отчёт, почта, обход этажа, фото складов', NULL, '11:00', '11:20', 'REQUIRED', NULL, FALSE, FALSE),
    ('MORNING', 2, 13, 'Сходить на брейк', NULL, NULL, '12:00', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 2, 14, 'Ежечасный отчёт, почта, обход этажа, фото складов', NULL, '12:00', '12:20', 'REQUIRED', NULL, FALSE, FALSE),
    ('MORNING', 2, 15, 'E-predict: планирование конфигурации', NULL, '12:20', '12:30', 'REQUIRED', NULL, FALSE, FALSE),
    ('MORNING', 2, 16, 'Ежечасный отчёт, почта, обход этажа, фото складов', NULL, '13:00', '13:20', 'REQUIRED', NULL, FALSE, FALSE),
    ('MORNING', 2, 17, 'Работа на этаже (пик тайм)', NULL, '13:00', '14:00', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 2, 18, 'Ежечасный отчёт, почта, обход этажа, фото складов', NULL, '14:00', '14:20', 'REQUIRED', NULL, FALSE, FALSE),
    ('MORNING', 2, 19, 'Пополнить кулер и фризер по таблице 24/2', NULL, '14:30', '15:00', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 2, 20, 'Ежечасный отчёт, почта, обход этажа, фото складов', NULL, '15:00', '15:20', 'REQUIRED', NULL, FALSE, FALSE),
    ('MORNING', 2, 21, 'Портянка: все сотрудники были на брейке и 15', NULL, '15:30', '16:00', 'REQUIRED', NULL, FALSE, FALSE),
    ('MORNING', 2, 22, 'Проверочная: проверить бланки MR', NULL, '16:00', '16:30', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 2, 23, 'Пересдать смену вечеру: перекрытие смен, написать отчёт', NULL, '16:00', '16:30', 'NONE', NULL, FALSE, FALSE),
    ('MORNING', 2, 24, 'Пробиться домой', NULL, '16:30', NULL, 'REQUIRED', NULL, FALSE, TRUE),
    -- Без привязки ко времени
    ('MORNING', 3, 1,  'Составить и подтвердить КЛН (фото завершённых КЛН)', NULL, NULL, NULL, 'REQUIRED', NULL, FALSE, FALSE),
    -- Регламент дня (инсайд, утро)
    ('MORNING', 4, 1,  'Помыть серые бадейки', NULL, NULL, NULL, 'REQUIRED', 1, FALSE, FALSE),
    ('MORNING', 4, 2,  'Помыть красные лотки (10 шт)', NULL, NULL, NULL, 'REQUIRED', 2, FALSE, FALSE),
    ('MORNING', 4, 3,  'Отмыть кухонные бадейки от клея', NULL, NULL, NULL, 'REQUIRED', 3, FALSE, FALSE),
    ('MORNING', 4, 4,  'Помыть ножки всех стеллажей прилавка', NULL, NULL, NULL, 'REQUIRED', 7, FALSE, FALSE),

    -- ===================== ВЕЧЕР =====================
    -- Приём смены 16:00–17:00
    ('EVENING', 1, 1,  'Готовый чек-лист (влияет на ОРП)', NULL, '16:00', '16:05', 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 1, 2,  'Приём смены: на все письма есть ответы', NULL, '16:05', '16:30', 'NONE', NULL, FALSE, FALSE),
    ('EVENING', 1, 3,  'Приём смены: пересказ проблем (продукт, оборудование, люди)', NULL, '16:05', '16:30', 'NONE', NULL, FALSE, FALSE),
    ('EVENING', 1, 4,  'Обход складов, проверка их состояния', NULL, '16:05', '16:30', 'NONE', NULL, FALSE, FALSE),
    ('EVENING', 1, 5,  'Чистота ресторана: СП, туалет, этаж', NULL, '16:05', '16:30', 'NONE', NULL, FALSE, FALSE),
    ('EVENING', 1, 6,  'Проверка бланков', NULL, '16:05', '16:30', 'NONE', NULL, FALSE, FALSE),
    ('EVENING', 1, 7,  'Сроки хранения и DR', NULL, '16:05', '16:30', 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 1, 8,  'Психрометр', NULL, '16:05', '16:30', 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 1, 9,  'Проверить ловушки', NULL, '16:05', '16:30', 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 1, 10, 'ПТО, журнал жира, shift report заполнены', NULL, '16:05', '16:30', 'NONE', NULL, FALSE, FALSE),
    ('EVENING', 1, 11, 'E-predict: планирование конфигурации', NULL, '16:30', '16:40', 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 1, 12, 'Температура салата: подзаправочный стол, кулер, холодильник (3 фото)', NULL, '16:40', '17:00', 'REQUIRED', NULL, FALSE, FALSE),
    -- Вечер 17:00–22:00
    ('EVENING', 2, 1,  'Ежечасный отчёт, почта, обход этажа, фото складов и чек-листа с отчётом', NULL, '17:00', '17:20', 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 2, 2,  'Ежечасный отчёт, почта, обход этажа, фото чек-листа с отчётом', NULL, '18:00', '18:20', 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 2, 3,  'Проверочная: закрыть сумку, проверить бланки MR', NULL, '18:00', '18:30', 'NONE', NULL, FALSE, FALSE),
    ('EVENING', 2, 4,  'Ежечасный отчёт, почта, обход этажа, фото чек-листа с отчётом', NULL, '19:00', '19:20', 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 2, 5,  'Бланк с подписями: почасовая мойка рук и мелкий инвентарь', NULL, '19:30', '20:00', 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 2, 6,  'Температура салата: подзаправочный стол, кулер, холодильник (3 фото)', NULL, '19:30', '20:00', 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 2, 7,  'Ежечасный отчёт, почта, обход этажа, фото чек-листа с отчётом', NULL, '20:00', '20:20', 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 2, 8,  'Ежечасный отчёт, почта, обход этажа, фото чек-листа с отчётом', NULL, '21:00', '21:20', 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 2, 9,  'Минимизация подзаправочного стола', NULL, '21:20', '21:30', 'NONE', NULL, FALSE, FALSE),
    ('EVENING', 2, 10, 'Проверить наличие химии для ночников', NULL, '21:30', '22:00', 'NONE', NULL, FALSE, FALSE),
    -- Закрытие 22:00–00:30 (время до 06:00 = следующие сутки)
    ('EVENING', 3, 1,  'Ежедневная инвентаризация', 60, '22:00', '00:00', 'NONE', NULL, FALSE, FALSE),
    ('EVENING', 3, 2,  'Заполнить журнал жира, замер масла', NULL, '23:30', '23:45', 'NONE', NULL, FALSE, FALSE),
    ('EVENING', 3, 3,  'Сыр чеддер и эмменталь в первичной упаковке с таймером', NULL, '23:45', '23:50', 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 3, 4,  'Снять кассу, проверить депозит, закрыть кассовую смену', NULL, '23:50', '00:00', 'NONE', NULL, FALSE, FALSE),
    ('EVENING', 3, 5,  'Выключить оборудование, шторки, вывеску', NULL, '00:00', '00:05', 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 3, 6,  'Ночники надели перчатки и маски', NULL, '00:05', '00:10', 'REQUIRED', NULL, FALSE, TRUE),
    ('EVENING', 3, 7,  'Отмывка: комбимашина', NULL, '00:10', '00:20', 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 3, 8,  'Отмывка: кофемашина и бункер для молока', NULL, '00:10', '00:20', 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 3, 9,  'Отмывка: башня напитков', NULL, '00:10', '00:20', 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 3, 10, 'Отмывка: поверхность зоны кассы', NULL, '00:10', '00:20', 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 3, 11, 'Отмывка: подзаправочный стол', NULL, NULL, NULL, 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 3, 12, 'Портянка: все сотрудники были на брейке и 15', NULL, '00:10', '00:20', 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 3, 13, 'Ежечасный отчёт, почта, обход этажа, фото складов и чек-листа, отчёт за день', NULL, '00:20', '00:30', 'REQUIRED', NULL, FALSE, FALSE),
    ('EVENING', 3, 14, 'Пробиться домой', NULL, '00:30', NULL, 'REQUIRED', NULL, FALSE, TRUE),
    -- Без привязки ко времени
    ('EVENING', 4, 1,  'Сходить на брейк', NULL, NULL, NULL, 'NONE', NULL, FALSE, FALSE),
    ('EVENING', 4, 2,  'Составить и подтвердить КЛН (фото завершённых КЛН)', NULL, NULL, '00:30', 'REQUIRED', NULL, FALSE, FALSE),
    -- Регламент дня: инсайд (вечер), фото до 00:30
    ('EVENING', 5, 1,  'Помыть стеллажи для булочек (3 шт)', NULL, NULL, '00:30', 'REQUIRED', 1, FALSE, FALSE),
    ('EVENING', 5, 2,  'Помыть красные лотки (30 шт)', NULL, NULL, '00:30', 'REQUIRED', 2, FALSE, FALSE),
    ('EVENING', 5, 3,  'Помыть куриные и рыбные лотки', NULL, NULL, '00:30', 'REQUIRED', 3, FALSE, FALSE),
    ('EVENING', 5, 4,  'Помыть мясные лотки', NULL, NULL, '00:30', 'REQUIRED', 4, FALSE, FALSE),
    ('EVENING', 5, 5,  'Помыть контейнер для фри и топпинги', NULL, NULL, '00:30', 'REQUIRED', 7, FALSE, FALSE),
    -- Регламент дня: приём регламента у ДЛК, фото до 00:30
    ('EVENING', 5, 6,  'Принять регламент ДЛК: фризер', NULL, NULL, '00:30', 'REQUIRED', 1, FALSE, FALSE),
    ('EVENING', 5, 7,  'Принять регламент ДЛК: кулер', NULL, NULL, '00:30', 'REQUIRED', 2, FALSE, FALSE),
    ('EVENING', 5, 8,  'Принять регламент ДЛК: сухой склад', NULL, NULL, '00:30', 'REQUIRED', 3, FALSE, FALSE),
    ('EVENING', 5, 9,  'Принять регламент ДЛК: мультиплекс', NULL, NULL, '00:30', 'REQUIRED', 4, FALSE, FALSE),
    ('EVENING', 5, 10, 'Принять регламент ДЛК: сухой склад', NULL, NULL, '00:30', 'REQUIRED', 7, FALSE, FALSE)
) AS i (day_part, sec, ord, title, dur, dfrom, dto, photo, wd, review, tg)
JOIN checklist_template t ON t.shift_role = 'INSIDE' AND t.day_part = i.day_part AND t.outlet_id IS NULL
JOIN checklist_section s ON s.template_id = t.id AND s.sort_order = i.sec;