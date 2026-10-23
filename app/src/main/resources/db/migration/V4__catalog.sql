SELECT set_config(
    'app.current_user_id',
    (SELECT u.id::text FROM public.users u JOIN public.roles r ON r.id = u.role_id WHERE r.name = 'admin' ORDER BY u.id LIMIT 1),
    true
);

UPDATE public.books
SET added_by = NULL
WHERE added_by IN (SELECT u.id FROM public.users u JOIN public.roles r ON r.id = u.role_id WHERE r.name = 'admin');

ALTER TABLE public.books
    DROP CONSTRAINT added_by,
    ADD CONSTRAINT books_added_by_fkey FOREIGN KEY (added_by) REFERENCES public.users (id)
        ON UPDATE CASCADE ON DELETE SET NULL;

CREATE OR REPLACE FUNCTION public.enforce_book_rules() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    curr_user_id integer := public.current_app_user_id();
    can_edit_any boolean := public.has_permission('books.edit');
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT public.has_permission('books.add') THEN
            RAISE EXCEPTION 'Access Denied: You do not have permission to add books.'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF NOT can_edit_any THEN
            NEW.added_by := curr_user_id;
            NEW.total_copies := 0;
            NEW.copies_available := 0;
            NEW.max_reservation_days := 14;
        END IF;
        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN
        IF can_edit_any THEN
            RETURN NEW;
        END IF;
        IF pg_trigger_depth() > 1
            AND (to_jsonb(NEW) - 'copies_available' - 'rating') = (to_jsonb(OLD) - 'copies_available' - 'rating') THEN
            RETURN NEW;
        END IF;
        IF NOT (public.has_permission('books.edit.own') AND COALESCE(OLD.added_by = curr_user_id, false)) THEN
            RAISE EXCEPTION 'Access Denied: You can only update books you personally added.'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF NEW.added_by IS DISTINCT FROM OLD.added_by THEN
            RAISE EXCEPTION 'Access Denied: You cannot change the owner of this book.'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF NEW.total_copies IS DISTINCT FROM OLD.total_copies
            OR NEW.copies_available IS DISTINCT FROM OLD.copies_available
            OR NEW.max_reservation_days IS DISTINCT FROM OLD.max_reservation_days THEN
            RAISE EXCEPTION 'Access Denied: Only administrators can change copies or loan length.'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN NEW;

    ELSE
        IF public.has_permission('books.delete') THEN
            RETURN OLD;
        END IF;
        IF public.has_permission('books.delete.own') AND OLD.added_by = curr_user_id THEN
            IF OLD.total_copies > 0 THEN
                RAISE EXCEPTION 'Access Denied: A book with library copies can only be deleted by an administrator.'
                    USING ERRCODE = 'insufficient_privilege';
            END IF;
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'Access Denied: You can only delete books you personally added.'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
END;
$$;

ALTER TABLE public.publishers ALTER COLUMN name SET NOT NULL;

ALTER TABLE public.book_languages ALTER COLUMN name SET NOT NULL;
