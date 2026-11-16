INSERT INTO public.users (id, email, username, password_hash, role_id)
SELECT v.id, v.email, v.username, '$2y$10$uMuA9ZzumqGY/hjhzARD/OdWmBcyJXPZqDyrfAPAnkbxxBm8c2QsC', r.id
FROM (VALUES
    (1001, 'anna.petrosyan@polytechnic.am', 'Anna Petrosyan'),
    (1002, 'davit.sargsyan@polytechnic.am', 'Davit Sargsyan')
) AS v (id, email, username)
JOIN public.roles r ON r.name = 'student'
ON CONFLICT (id) DO NOTHING;

SELECT set_config('app.current_user_id', '1001', true);

INSERT INTO public.users_favourites (user_id, book_id, added_at) VALUES
    (1001, 1, '2026-04-01 10:00+04'),
    (1001, 61, '2026-04-02 10:00+04'),
    (1001, 101, '2026-04-03 10:00+04')
ON CONFLICT DO NOTHING;

SELECT set_config('app.current_user_id', '1002', true);

INSERT INTO public.users_favourites (user_id, book_id, added_at) VALUES
    (1002, 2, '2026-04-01 10:00+04'),
    (1002, 62, '2026-04-02 10:00+04'),
    (1002, 102, '2026-04-03 10:00+04')
ON CONFLICT DO NOTHING;
