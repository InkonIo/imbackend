-- Третий вид заявки: AVAILABLE = «в эти дни могу работать только в такие часы».
-- Старые проверки на kind снимаем (имена генерировал Postgres) и ставим новые.
DO $$
DECLARE c record;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'emp_request'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) ILIKE '%kind%'
    LOOP
        EXECUTE format('ALTER TABLE emp_request DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE emp_request ADD CONSTRAINT chk_emp_request_kind CHECK (kind IN ('DAY_OFF', 'UNAVAILABLE', 'AVAILABLE'));
ALTER TABLE emp_request ADD CONSTRAINT chk_emp_request_times CHECK (
    (kind = 'DAY_OFF' AND time_from IS NULL AND time_to IS NULL)
    OR (kind IN ('UNAVAILABLE', 'AVAILABLE') AND time_from IS NOT NULL AND time_to IS NOT NULL AND time_to > time_from));