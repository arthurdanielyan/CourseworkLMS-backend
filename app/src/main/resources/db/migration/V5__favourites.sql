ALTER TABLE public.users_favourites
    ADD COLUMN added_at timestamptz NOT NULL DEFAULT now(),
    DROP CONSTRAINT fk_user,
    DROP CONSTRAINT fk_book,
    ADD CONSTRAINT users_favourites_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users (id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    ADD CONSTRAINT users_favourites_book_id_fkey FOREIGN KEY (book_id) REFERENCES public.books (id)
        ON UPDATE CASCADE ON DELETE CASCADE;

CREATE FUNCTION public.enforce_own_favourites() RETURNS trigger
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

    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        IF OLD.user_id IS DISTINCT FROM curr_user_id THEN
            RAISE EXCEPTION 'Access Denied: You can only change your own favourites.'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        IF NEW.user_id IS DISTINCT FROM curr_user_id THEN
            RAISE EXCEPTION 'Access Denied: You can only change your own favourites.'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER users_favourites_owner_trigger BEFORE INSERT OR UPDATE OR DELETE ON public.users_favourites
    FOR EACH ROW EXECUTE FUNCTION public.enforce_own_favourites();
