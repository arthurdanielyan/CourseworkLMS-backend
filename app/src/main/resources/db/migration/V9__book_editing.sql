CREATE TABLE public.files (
    id uuid NOT NULL,
    content_type varchar(100) NOT NULL,
    size_bytes integer NOT NULL,
    data bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT files_pkey PRIMARY KEY (id)
);

ALTER TABLE public.books
    ADD COLUMN cover_file_id uuid,
    ADD COLUMN pdf_file_id uuid,
    ADD COLUMN deleted_at timestamptz,
    ADD CONSTRAINT books_cover_file_id_fkey FOREIGN KEY (cover_file_id) REFERENCES public.files (id) ON DELETE SET NULL,
    ADD CONSTRAINT books_pdf_file_id_fkey FOREIGN KEY (pdf_file_id) REFERENCES public.files (id) ON DELETE SET NULL;

ALTER TABLE public.books_categories
    DROP CONSTRAINT fk_book,
    ADD CONSTRAINT fk_book FOREIGN KEY (book_id) REFERENCES public.books (id) ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE public.reservations
    DROP CONSTRAINT book_id,
    ADD CONSTRAINT book_id FOREIGN KEY (book_id) REFERENCES public.books (id) ON UPDATE CASCADE ON DELETE CASCADE;

CREATE UNIQUE INDEX authors_name_lower_key ON public.authors (lower(name));

CREATE UNIQUE INDEX publishers_name_lower_key ON public.publishers (lower(name));

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
            NEW.deleted_at := NULL;
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
            OR NEW.max_reservation_days IS DISTINCT FROM OLD.max_reservation_days
            OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
            RAISE EXCEPTION 'Access Denied: Only administrators can change copies, loan length or visibility.'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN NEW;

    ELSE
        IF EXISTS (SELECT 1 FROM public.reservations WHERE book_id = OLD.id AND status = 'borrowed') THEN
            RAISE EXCEPTION 'A book with borrowed copies can only be hidden until they are returned.'
                USING ERRCODE = 'check_violation';
        END IF;
        IF public.has_permission('books.delete') THEN
            RETURN OLD;
        END IF;
        IF public.has_permission('books.delete.own') AND COALESCE(OLD.added_by = curr_user_id, false) THEN
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

CREATE FUNCTION public.adjust_copies_to_total() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.total_copies < OLD.total_copies THEN
        NEW.copies_available := OLD.copies_available - (OLD.total_copies - NEW.total_copies);
        IF NEW.copies_available < 0 THEN
            RAISE EXCEPTION 'Copies that are reserved, borrowed or offered cannot be removed.'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER books_adjust_copies_trigger BEFORE UPDATE OF total_copies ON public.books
    FOR EACH ROW EXECUTE FUNCTION public.adjust_copies_to_total();

CREATE FUNCTION public.release_added_copies() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.total_copies > OLD.total_copies THEN
        FOR i IN 1..(NEW.total_copies - OLD.total_copies) LOOP
            PERFORM public.release_copy(NEW.id);
        END LOOP;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER books_release_added_copies_trigger AFTER UPDATE OF total_copies ON public.books
    FOR EACH ROW EXECUTE FUNCTION public.release_added_copies();

CREATE FUNCTION public.enforce_book_link_rules() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    curr_user_id integer := public.current_app_user_id();
    target_book_id integer;
BEGIN
    IF pg_trigger_depth() > 1 THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        target_book_id := OLD.book_id;
    ELSE
        target_book_id := NEW.book_id;
    END IF;
    IF NOT (
        public.has_permission('books.edit')
        OR (
            public.has_permission('books.edit.own')
            AND EXISTS (SELECT 1 FROM public.books WHERE id = target_book_id AND added_by = curr_user_id)
        )
    ) THEN
        RAISE EXCEPTION 'Access Denied: You can only change books you personally added.'
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER book_authors_rules_trigger BEFORE INSERT OR UPDATE OR DELETE ON public.book_authors
    FOR EACH ROW EXECUTE FUNCTION public.enforce_book_link_rules();

CREATE TRIGGER books_categories_rules_trigger BEFORE INSERT OR UPDATE OR DELETE ON public.books_categories
    FOR EACH ROW EXECUTE FUNCTION public.enforce_book_link_rules();

CREATE FUNCTION public.enforce_book_reference_rules() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF pg_trigger_depth() > 1 THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NOT public.has_permission('books.add') THEN
            RAISE EXCEPTION 'Access Denied: Only users who add books can add authors and publishers.'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN NEW;
    END IF;

    IF NOT public.has_permission('books.edit') THEN
        RAISE EXCEPTION 'Access Denied: Only administrators can change or remove authors and publishers.'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER authors_rules_trigger BEFORE INSERT OR UPDATE OR DELETE ON public.authors
    FOR EACH ROW EXECUTE FUNCTION public.enforce_book_reference_rules();

CREATE TRIGGER publishers_rules_trigger BEFORE INSERT OR UPDATE OR DELETE ON public.publishers
    FOR EACH ROW EXECUTE FUNCTION public.enforce_book_reference_rules();
