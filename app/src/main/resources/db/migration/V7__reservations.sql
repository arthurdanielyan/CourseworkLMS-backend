SELECT set_config(
    'app.current_user_id',
    (SELECT u.id::text FROM public.users u JOIN public.roles r ON r.id = u.role_id WHERE r.name = 'admin' ORDER BY u.id LIMIT 1),
    true
);

INSERT INTO public.permissions (name) VALUES ('reservations.create'), ('reservations.manage');

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r
JOIN public.permissions p ON (r.name, p.name) IN (
    ('student', 'reservations.create'),
    ('teacher', 'reservations.create'),
    ('admin', 'reservations.manage')
);

UPDATE public.books SET max_reservation_days = 14 WHERE max_reservation_days < 1;

ALTER TABLE public.books
    ALTER COLUMN max_reservation_days SET DEFAULT 14,
    ADD CONSTRAINT books_max_reservation_days_check CHECK (max_reservation_days >= 1);

DROP TRIGGER inventory_update_trigger ON public.reservations;

ALTER TABLE public.reservations
    ALTER COLUMN created_at TYPE timestamptz USING created_at::timestamptz,
    ALTER COLUMN created_at SET DEFAULT now(),
    ADD COLUMN status varchar(20) NOT NULL DEFAULT 'reserved',
    ADD COLUMN borrowed_at timestamptz,
    ADD COLUMN finished_at timestamptz,
    ADD CONSTRAINT reservations_status_check
        CHECK (status IN ('reserved', 'borrowed', 'returned', 'cancelled', 'expired'));

UPDATE public.reservations
SET status = 'returned',
    borrowed_at = created_at,
    finished_at = return_deadline::timestamptz;

UPDATE public.books b
SET copies_available = b.total_copies - (
    SELECT count(*) FROM public.reservations r WHERE r.book_id = b.id AND r.status IN ('reserved', 'borrowed')
);

CREATE UNIQUE INDEX reservations_one_active_per_user_book ON public.reservations (user_id, book_id)
    WHERE status IN ('reserved', 'borrowed');

CREATE FUNCTION public.enforce_reservation_rules() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    curr_user_id integer := public.current_app_user_id();
    can_manage boolean := public.has_permission('reservations.manage');
    library_today date := (now() AT TIME ZONE 'Asia/Yerevan')::date;
    max_days integer;
BEGIN
    IF pg_trigger_depth() > 1 THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NOT public.has_permission('reservations.create') OR NEW.user_id IS DISTINCT FROM curr_user_id THEN
            RAISE EXCEPTION 'Access Denied: You can only reserve books for yourself.'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF NEW.status <> 'reserved' OR NEW.borrowed_at IS NOT NULL OR NEW.finished_at IS NOT NULL THEN
            RAISE EXCEPTION 'A new reservation must start as reserved.'
                USING ERRCODE = 'check_violation';
        END IF;
        SELECT max_reservation_days INTO max_days FROM public.books WHERE id = NEW.book_id;
        IF NEW.return_deadline <= library_today OR NEW.return_deadline > library_today + max_days THEN
            RAISE EXCEPTION 'The return date must be after today and within the book''s loan length.'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN
        IF NEW.user_id <> OLD.user_id
            OR NEW.book_id <> OLD.book_id
            OR NEW.created_at <> OLD.created_at
            OR NEW.return_deadline <> OLD.return_deadline THEN
            RAISE EXCEPTION 'Access Denied: Only the status of a reservation changes.'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
            RETURN NEW;
        END IF;
        IF OLD.status = 'reserved' AND NEW.status IN ('cancelled', 'expired') THEN
            IF NOT (OLD.user_id = curr_user_id OR can_manage) THEN
                RAISE EXCEPTION 'Access Denied: You can only cancel your own reservations.'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
        ELSIF (OLD.status = 'reserved' AND NEW.status = 'borrowed') OR (OLD.status = 'borrowed' AND NEW.status = 'returned') THEN
            IF NOT can_manage THEN
                RAISE EXCEPTION 'Access Denied: Only administrators hand books over and take them back.'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
        ELSE
            RAISE EXCEPTION 'A reservation cannot go from % to %.', OLD.status, NEW.status
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;

    ELSE
        RAISE EXCEPTION 'Access Denied: Reservations are kept as history.'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
END;
$$;

CREATE TRIGGER reservations_rules_trigger BEFORE INSERT OR UPDATE OR DELETE ON public.reservations
    FOR EACH ROW EXECUTE FUNCTION public.enforce_reservation_rules();

CREATE OR REPLACE FUNCTION public.manage_book_inventory() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE public.books SET copies_available = copies_available - 1
        WHERE id = NEW.book_id AND copies_available > 0;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'No copies of this book are available.'
                USING ERRCODE = 'check_violation';
        END IF;
    ELSIF OLD.status IN ('reserved', 'borrowed') AND NEW.status IN ('returned', 'cancelled', 'expired') THEN
        UPDATE public.books SET copies_available = copies_available + 1 WHERE id = NEW.book_id;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER reservations_inventory_trigger AFTER INSERT OR UPDATE OF status ON public.reservations
    FOR EACH ROW EXECUTE FUNCTION public.manage_book_inventory();
