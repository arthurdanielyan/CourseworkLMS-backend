SELECT set_config(
    'app.current_user_id',
    (SELECT u.id::text FROM public.users u JOIN public.roles r ON r.id = u.role_id WHERE r.name = 'admin' ORDER BY u.id LIMIT 1),
    true
);

ALTER TABLE public.book_reviews
    ADD CONSTRAINT book_reviews_stars_check CHECK (stars BETWEEN 1 AND 5),
    ADD CONSTRAINT book_reviews_user_id_book_id_key UNIQUE (user_id, book_id),
    DROP CONSTRAINT book_id,
    ADD CONSTRAINT book_reviews_book_id_fkey FOREIGN KEY (book_id) REFERENCES public.books (id)
        ON UPDATE CASCADE ON DELETE CASCADE;

CREATE INDEX book_reviews_book_id_idx ON public.book_reviews (book_id);

INSERT INTO public.permissions (name) VALUES ('reviews.moderate');

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r
JOIN public.permissions p ON (r.name, p.name) = ('admin', 'reviews.moderate');

CREATE OR REPLACE FUNCTION public.update_book_average_rating() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    target_book_id integer;
BEGIN
    IF TG_OP = 'DELETE' THEN
        target_book_id := OLD.book_id;
    ELSE
        target_book_id := NEW.book_id;
    END IF;

    UPDATE public.books
    SET rating = (SELECT AVG(stars) FROM public.book_reviews WHERE book_id = target_book_id)
    WHERE id = target_book_id;

    RETURN NULL;
END;
$$;

UPDATE public.books b
SET rating = (SELECT AVG(r.stars) FROM public.book_reviews r WHERE r.book_id = b.id);

CREATE FUNCTION public.enforce_review_rules() RETURNS trigger
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
        IF NEW.user_id IS DISTINCT FROM curr_user_id THEN
            RAISE EXCEPTION 'Access Denied: You can only write reviews as yourself.'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.user_id IS DISTINCT FROM curr_user_id THEN
            RAISE EXCEPTION 'Access Denied: You can only edit your own reviews.'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF NEW.user_id IS DISTINCT FROM OLD.user_id OR NEW.book_id IS DISTINCT FROM OLD.book_id THEN
            RAISE EXCEPTION 'Access Denied: A review cannot move to another user or book.'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN NEW;

    ELSE
        IF OLD.user_id IS DISTINCT FROM curr_user_id AND NOT public.has_permission('reviews.moderate') THEN
            RAISE EXCEPTION 'Access Denied: You can only delete your own reviews.'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN OLD;
    END IF;
END;
$$;

CREATE TRIGGER book_reviews_rules_trigger BEFORE INSERT OR UPDATE OR DELETE ON public.book_reviews
    FOR EACH ROW EXECUTE FUNCTION public.enforce_review_rules();
