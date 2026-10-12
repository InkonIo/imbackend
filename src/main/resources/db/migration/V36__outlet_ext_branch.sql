-- Связь «точка в нашей системе (outlet)» ↔ «ресторан из тайм-трекера / отчёта iiko (ext_branch)».
-- Нужна, чтобы бланк «Чек-лист смены» брал цифры из отчёта iiko (sales_hour), а директор видел свои рестораны в расписании.
CREATE TABLE IF NOT EXISTS outlet_ext_branch (
  outlet_id bigint PRIMARY KEY REFERENCES outlet(id) ON DELETE CASCADE,
  branch_id bigint NOT NULL REFERENCES ext_branch(id) ON DELETE CASCADE
);

-- 1) Посмотри оба списка и сопоставь глазами:
--    select id, name from outlet order by name;
--    select id, title from ext_branch order by title;
-- 2) Заполни (пример: outlet 3 = ext_branch 1234):
--    insert into outlet_ext_branch (outlet_id, branch_id) values (3, 1234)
--      on conflict (outlet_id) do update set branch_id = excluded.branch_id;
-- 3) После заполнения выдай директорам доступ к их ресторанам в расписании:
--    insert into sched_branch_access (user_id, branch_id)
--    select distinct uo.user_id, m.branch_id
--    from user_outlet uo
--    join app_user u on u.id = uo.user_id and u.account_role = 'DIRECTOR'
--    join outlet_ext_branch m on m.outlet_id = uo.outlet_id
--    on conflict do nothing;
-- Процент роста целей (по умолчанию 1):
--    insert into ext_setting (key, value, updated_at) values ('sheet_goal_growth_pct', '1', now())
--      on conflict (key) do update set value = excluded.value, updated_at = now();