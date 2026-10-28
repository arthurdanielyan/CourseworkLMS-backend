DELETE FROM public.waitlist w
WHERE EXISTS (SELECT 1 FROM public.books b WHERE b.id = w.book_id AND b.copies_available > 0);

ALTER TABLE public.waitlist
    DROP COLUMN priority_position,
    ALTER COLUMN book_id SET NOT NULL,
    ALTER COLUMN user_id SET NOT NULL,
    ALTER COLUMN added_at SET NOT NULL,
    ADD COLUMN offered_at timestamptz,
    ADD COLUMN offer_expires_at timestamptz,
    ADD CONSTRAINT waitlist_offer_check CHECK (
        (offered_at IS NULL AND offer_expires_at IS NULL) OR (offered_at IS NOT NULL AND offer_expires_at IS NOT NULL)
    );

CREATE INDEX waitlist_book_id_added_at_idx ON public.waitlist (book_id, added_at, id);

CREATE FUNCTION public.release_copy(target_book_id integer) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
    next_entry_id integer;
BEGIN
    SELECT id INTO next_entry_id
    FROM public.waitlist
    WHERE book_id = target_book_id AND offered_at IS NULL
    ORDER BY added_at, id
    LIMIT 1
    FOR UPDATE;

    IF next_entry_id IS NULL THEN
        UPDATE public.books SET copies_available = copies_available + 1 WHERE id = target_book_id;
    ELSE
        UPDATE public.waitlist
        SET offered_at = now(), offer_expires_at = now() + interval '24 hours'
        WHERE id = next_entry_id;
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION public.manage_book_inventory() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        DELETE FROM public.waitlist
        WHERE book_id = NEW.book_id
          AND user_id = NEW.user_id
          AND offered_at IS NOT NULL
          AND offer_expires_at > now();
        IF NOT FOUND THEN
            UPDATE public.books SET copies_available = copies_available - 1
            WHERE id = NEW.book_id AND copies_available > 0;
            IF NOT FOUND THEN
                RAISE EXCEPTION 'No copies of this book are available.'
                    USING ERRCODE = 'check_violation';
            END IF;
        END IF;
    ELSIF OLD.status IN ('reserved', 'borrowed') AND NEW.status IN ('returned', 'cancelled', 'expired') THEN
        PERFORM public.release_copy(NEW.book_id);
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION public.enforce_waitlist_rules() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    curr_user_id integer := public.current_app_user_id();
BEGIN
    IF pg_trigger_depth() > 1 THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NOT public.has_permission('reservations.create') OR NEW.user_id IS DISTINCT FROM curr_user_id THEN
            RAISE EXCEPTION 'Access Denied: You can only join a waitlist yourself.'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF NEW.offered_at IS NOT NULL THEN
            RAISE EXCEPTION 'A new waitlist entry cannot start with an offer.'
                USING ERRCODE = 'check_violation';
        END IF;
        IF EXISTS (SELECT 1 FROM public.books WHERE id = NEW.book_id AND copies_available > 0) THEN
            RAISE EXCEPTION 'A book with free copies is reserved, not waited for.'
                USING ERRCODE = 'check_violation';
        END IF;
        IF EXISTS (
            SELECT 1 FROM public.reservations
            WHERE book_id = NEW.book_id AND user_id = NEW.user_id AND status IN ('reserved', 'borrowed')
        ) THEN
            RAISE EXCEPTION 'You already have this book reserved or borrowed.'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Access Denied: Waitlist entries change only through the library''s offers.'
            USING ERRCODE = 'insufficient_privilege';

    ELSE
        IF OLD.user_id IS DISTINCT FROM curr_user_id AND NOT public.has_permission('reservations.manage') THEN
            RAISE EXCEPTION 'Access Denied: You can only leave a waitlist yourself.'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN OLD;
    END IF;
END;
$$;

CREATE TRIGGER waitlist_rules_trigger BEFORE INSERT OR UPDATE OR DELETE ON public.waitlist
    FOR EACH ROW EXECUTE FUNCTION public.enforce_waitlist_rules();

CREATE FUNCTION public.pass_on_declined_offer() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF pg_trigger_depth() = 1 AND OLD.offered_at IS NOT NULL THEN
        PERFORM public.release_copy(OLD.book_id);
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER waitlist_offer_release_trigger AFTER DELETE ON public.waitlist
    FOR EACH ROW EXECUTE FUNCTION public.pass_on_declined_offer();
