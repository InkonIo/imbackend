-- Стажёр мог ещё не попасть в kln и Таймтрекер: имя можно вписать текстом.
ALTER TABLE plan_training ADD COLUMN IF NOT EXISTS trainee_name VARCHAR(120);