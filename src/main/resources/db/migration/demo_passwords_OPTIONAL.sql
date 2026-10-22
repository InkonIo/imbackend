-- ОПЦИОНАЛЬНО. Только для демо-стенда, не для рабочей базы!
-- Задаёт всем 13 аккаунтам один пароль, чтобы можно было зайти под любым менеджером.
-- Пароль: Demo2026!   (поменяй строку ниже, если нужен другой)
-- После демо верни как было: update app_user set password_hash = '!', must_change_password = true where login in (...);
CREATE EXTENSION IF NOT EXISTS pgcrypto;
UPDATE app_user
SET password_hash = crypt('Demo2026!', gen_salt('bf', 10)), must_change_password = FALSE, active = TRUE
WHERE login IN ('almat','anar','aruzhan','saniya','zhanar','diana','aigerim','galiya',
                'bakytzhan','dilnaz','altynai','sherkhan','ladarina');