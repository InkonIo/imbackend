INSERT INTO app_user (login, password_hash, full_name, account_role, active, must_change_password) VALUES
    ('almat',     '!', 'Алмат',     'MANAGER',  TRUE, TRUE),
    ('anar',      '!', 'Анар',      'MANAGER',  TRUE, TRUE),
    ('aruzhan',   '!', 'Аружан',    'MANAGER',  TRUE, TRUE),
    ('saniya',    '!', 'Сания',     'MANAGER',  TRUE, TRUE),
    ('zhanar',    '!', 'Жанар',     'MANAGER',  TRUE, TRUE),
    ('diana',     '!', 'Диана',     'MANAGER',  TRUE, TRUE),
    ('aigerim',   '!', 'Айгерим',   'MANAGER',  TRUE, TRUE),
    ('galiya',    '!', 'Галия',     'MANAGER',  TRUE, TRUE),
    ('bakytzhan', '!', 'Бакытжан',  'MANAGER',  TRUE, TRUE),
    ('dilnaz',    '!', 'Дильназ',   'MANAGER',  TRUE, TRUE),
    ('altynai',   '!', 'Алтынай',   'MANAGER',  TRUE, TRUE),
    ('sherkhan',  '!', 'Шерхан',    'MANAGER',  TRUE, TRUE),
    ('ladarina',  '!', 'Ладарина',  'DIRECTOR', TRUE, TRUE)
ON CONFLICT (login) DO NOTHING;

-- все на Dostyk Plaza
INSERT INTO user_outlet (user_id, outlet_id)
SELECT u.id, o.id
FROM app_user u
JOIN outlet o ON o.name = 'Dostyk Plaza'
WHERE u.login IN ('almat','anar','aruzhan','saniya','zhanar','diana','aigerim','galiya',
                  'bakytzhan','dilnaz','altynai','sherkhan','ladarina')
  AND NOT EXISTS (SELECT 1 FROM user_outlet x WHERE x.user_id = u.id AND x.outlet_id = o.id);

-- должность, можно ли инсайдом, участвует ли в автографике
INSERT INTO staff_profile (user_id, job_title, can_inside, schedulable)
SELECT u.id, v.job, v.inside, v.sched
FROM (VALUES
    ('almat',     'MANAGER',  FALSE, TRUE),
    ('anar',      'MANAGER',  TRUE,  TRUE),
    ('aruzhan',   'MANAGER',  TRUE,  TRUE),
    ('saniya',    'MANAGER',  FALSE, TRUE),
    ('zhanar',    'TRAINEE',  FALSE, TRUE),
    ('diana',     'DEPUTY',   TRUE,  TRUE),
    ('aigerim',   'MANAGER',  TRUE,  TRUE),
    ('galiya',    'MANAGER',  TRUE,  TRUE),
    ('bakytzhan', 'MANAGER',  FALSE, TRUE),
    ('dilnaz',    'MANAGER',  TRUE,  TRUE),
    ('altynai',   'DEPUTY',   TRUE,  TRUE),
    ('sherkhan',  'TRAINEE',  FALSE, TRUE),
    ('ladarina',  'DIRECTOR', TRUE,  FALSE)
) AS v (login, job, inside, sched)
JOIN app_user u ON u.login = v.login
ON CONFLICT (user_id) DO NOTHING;