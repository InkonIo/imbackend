INSERT INTO city (name) VALUES
    ('Алматы'), ('Астана'), ('Актобе'), ('Атырау'), ('Караганда'), ('Костанай');

INSERT INTO outlet (city_id, name, address)
SELECT id, 'Dostyk Plaza', 'Самал-2, 111' FROM city WHERE name = 'Алматы';