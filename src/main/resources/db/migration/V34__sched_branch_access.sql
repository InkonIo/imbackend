-- Какие рестораны (ext_branch из тайм-трекера) видит директор в «Расписании».
-- Супер-админ и назначенная ответственная видят все рестораны без записей здесь.
CREATE TABLE IF NOT EXISTS sched_branch_access (
  user_id   bigint NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  branch_id bigint NOT NULL REFERENCES ext_branch(id) ON DELETE CASCADE,
  PRIMARY KEY (user_id, branch_id)
);

-- Автозаполнение: директору даём ext_branch, название которого совпадает с названием его точки (outlet).
INSERT INTO sched_branch_access (user_id, branch_id)
SELECT DISTINCT uo.user_id, b.id
FROM user_outlet uo
JOIN app_user u ON u.id = uo.user_id AND u.account_role = 'DIRECTOR'
JOIN outlet o ON o.id = uo.outlet_id
JOIN ext_branch b ON lower(btrim(b.title)) = lower(btrim(o.name))
ON CONFLICT DO NOTHING;

-- Проверь, кому что досталось, и добавь недостающее вручную:
--   select u.full_name, b.title from sched_branch_access a
--     join app_user u on u.id=a.user_id join ext_branch b on b.id=a.branch_id order by 1,2;
--   insert into sched_branch_access values (<user_id>, <branch_id>);