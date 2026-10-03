INSERT INTO app_user (login, full_name, account_role, must_change_password) VALUES
    ('galiya',  'Галия',  'MANAGER', TRUE),
    ('aruzhan', 'Аружан', 'MANAGER', TRUE),
    ('dilnaz',  'Дильназ', 'MANAGER', TRUE);

INSERT INTO user_outlet (user_id, outlet_id)
SELECT u.id, o.id
FROM app_user u
JOIN outlet o ON o.name = 'Dostyk Plaza'
JOIN city c ON c.id = o.city_id AND c.name = 'Алматы'
WHERE u.login IN ('galiya', 'aruzhan', 'dilnaz');