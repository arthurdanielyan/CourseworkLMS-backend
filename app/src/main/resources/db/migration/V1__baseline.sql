--
-- PostgreSQL database dump
--
-- The LMS database as it stood on 2026-11-16, schema and data, taken with
-- pg_dump --no-owner --no-privileges --inserts. Databases that already hold it are baselined at this version.
-- Never edit this file: changes go in a new migration.
--

-- Dumped from database version 18.2
-- Dumped by pg_dump version 18.2

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: adjust_copies_to_total(); Type: FUNCTION; Schema: public; Owner: -
--

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


--
-- Name: current_app_user_id(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.current_app_user_id() RETURNS integer
    LANGUAGE plpgsql STABLE
    AS $$
DECLARE
    user_id integer := NULLIF(current_setting('app.current_user_id', true), '')::integer;
BEGIN
    IF user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated: app.current_user_id is not set.' USING ERRCODE = 'L0001';
    END IF;
    RETURN user_id;
END;
$$;


--
-- Name: enforce_book_link_rules(); Type: FUNCTION; Schema: public; Owner: -
--

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


--
-- Name: enforce_book_reference_rules(); Type: FUNCTION; Schema: public; Owner: -
--

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


--
-- Name: enforce_book_rules(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_book_rules() RETURNS trigger
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


--
-- Name: enforce_catalog_list_rules(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_catalog_list_rules() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF pg_trigger_depth() = 1 AND NOT public.has_permission('catalog.manage') THEN
        RAISE EXCEPTION 'Access Denied: Only administrators manage categories and languages.'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: enforce_notification_rules(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_notification_rules() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    acting_user_id integer := NULLIF(current_setting('app.current_user_id', true), '')::integer;
BEGIN
    IF (to_jsonb(NEW) - 'is_read' - 'emailed_at') IS DISTINCT FROM (to_jsonb(OLD) - 'is_read' - 'emailed_at') THEN
        RAISE EXCEPTION 'Access Denied: A notification only changes by being read or emailed.'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.is_read IS DISTINCT FROM OLD.is_read AND OLD.user_id IS DISTINCT FROM acting_user_id THEN
        RAISE EXCEPTION 'Access Denied: You can only mark your own notifications as read.'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: enforce_own_favourites(); Type: FUNCTION; Schema: public; Owner: -
--

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


--
-- Name: enforce_reservation_rules(); Type: FUNCTION; Schema: public; Owner: -
--

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


--
-- Name: enforce_review_rules(); Type: FUNCTION; Schema: public; Owner: -
--

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


--
-- Name: enforce_teacher_email_management(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_teacher_email_management() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT public.has_permission('users.manage') THEN
        RAISE EXCEPTION 'Access Denied: Only administrators can modify teacher emails.'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: enforce_user_management(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_user_management() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    curr_user_id integer := public.current_app_user_id();
BEGIN
    IF public.has_permission('users.manage') THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.id = curr_user_id THEN
        -- Email decides the role through teacher_emails, so only the username and password are self-service.
        IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.email IS DISTINCT FROM OLD.email
            OR NEW.role_id IS DISTINCT FROM OLD.role_id
            OR NEW.registered_at IS DISTINCT FROM OLD.registered_at THEN
            RAISE EXCEPTION 'Access Denied: You can only change your own username and password.'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Access Denied: Administrative privileges required to modify user accounts.'
        USING ERRCODE = 'insufficient_privilege';
END;
$$;


--
-- Name: enforce_waitlist_rules(); Type: FUNCTION; Schema: public; Owner: -
--

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


--
-- Name: has_permission(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.has_permission(required_perm text) RETURNS boolean
    LANGUAGE plpgsql STABLE
    AS $$
BEGIN
    RETURN EXISTS (
        SELECT 1 FROM public.users u
        JOIN public.role_permissions rp ON u.role_id = rp.role_id
        JOIN public.permissions p ON rp.permission_id = p.id
        WHERE u.id = public.current_app_user_id()
        AND p.name = required_perm
    );
END;
$$;


--
-- Name: manage_book_inventory(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.manage_book_inventory() RETURNS trigger
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


--
-- Name: pass_on_declined_offer(); Type: FUNCTION; Schema: public; Owner: -
--

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


--
-- Name: release_added_copies(); Type: FUNCTION; Schema: public; Owner: -
--

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


--
-- Name: release_copy(integer); Type: FUNCTION; Schema: public; Owner: -
--

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


--
-- Name: update_book_average_rating(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_book_average_rating() RETURNS trigger
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


--
-- Name: validate_and_assign_role(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_and_assign_role() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.email NOT LIKE '%@polytechnic.am' THEN
        RAISE EXCEPTION 'Registration restricted: Email must end with @polytechnic.am'
            USING ERRCODE = 'check_violation';
    END IF;
    -- The client never chooses a role: a Teacher Email signs up as a teacher, everyone else as a student.
    IF EXISTS (SELECT 1 FROM public.teacher_emails WHERE email = NEW.email) THEN
        NEW.role_id := (SELECT id FROM public.roles WHERE name = 'teacher');
    ELSE
        NEW.role_id := (SELECT id FROM public.roles WHERE name = 'student');
    END IF;
    RETURN NEW;
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: authors; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.authors (
    id integer NOT NULL,
    name character varying(50) NOT NULL
);


--
-- Name: authors_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.authors_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: authors_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.authors_id_seq OWNED BY public.authors.id;


--
-- Name: book_authors; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.book_authors (
    book_id integer NOT NULL,
    author_id integer NOT NULL
);


--
-- Name: book_languages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.book_languages (
    id integer NOT NULL,
    name character varying(50) NOT NULL
);


--
-- Name: book_languages_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.book_languages_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: book_languages_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.book_languages_id_seq OWNED BY public.book_languages.id;


--
-- Name: book_reviews; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.book_reviews (
    id integer NOT NULL,
    user_id integer,
    book_id integer NOT NULL,
    stars smallint NOT NULL,
    review text,
    created_at date DEFAULT CURRENT_DATE NOT NULL,
    CONSTRAINT book_reviews_stars_check CHECK (((stars >= 1) AND (stars <= 5)))
);


--
-- Name: book_reviews_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.book_reviews_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: book_reviews_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.book_reviews_id_seq OWNED BY public.book_reviews.id;


--
-- Name: books; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.books (
    id integer NOT NULL,
    title character varying(100) NOT NULL,
    subtitle character varying(100),
    pdf_url text,
    rating real,
    description text NOT NULL,
    publication_year integer,
    edition character varying(50),
    cover_image_url text,
    total_copies integer NOT NULL,
    copies_available integer NOT NULL,
    added_by integer,
    publisher_id integer NOT NULL,
    language_id integer NOT NULL,
    max_reservation_days integer DEFAULT 14 NOT NULL,
    cover_file_id uuid,
    pdf_file_id uuid,
    deleted_at timestamp with time zone,
    CONSTRAINT books_max_reservation_days_check CHECK ((max_reservation_days >= 1))
);


--
-- Name: books_categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.books_categories (
    book_id integer NOT NULL,
    category_id integer NOT NULL
);


--
-- Name: TABLE books_categories; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.books_categories IS 'many-to-many relationship table between books and categories';


--
-- Name: books_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.books_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: books_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.books_id_seq OWNED BY public.books.id;


--
-- Name: categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.categories (
    id integer NOT NULL,
    name character varying(50) NOT NULL
);


--
-- Name: categories_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.categories_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: categories_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.categories_id_seq OWNED BY public.categories.id;


--
-- Name: files; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.files (
    id uuid NOT NULL,
    content_type character varying(100) NOT NULL,
    size_bytes integer NOT NULL,
    data bytea NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: notifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notifications (
    id integer NOT NULL,
    user_id integer NOT NULL,
    title text NOT NULL,
    message text NOT NULL,
    type character varying(30) NOT NULL,
    is_read boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    book_id integer,
    dedupe_key character varying(100) NOT NULL,
    emailed_at timestamp with time zone,
    CONSTRAINT notifications_type_check CHECK (((type)::text = ANY ((ARRAY['waitlist_offer'::character varying, 'pickup_reminder'::character varying, 'reservation_expired'::character varying, 'return_reminder'::character varying, 'overdue'::character varying])::text[])))
);


--
-- Name: notifications_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.notifications_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: notifications_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.notifications_id_seq OWNED BY public.notifications.id;


--
-- Name: password_resets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.password_resets (
    user_id integer NOT NULL,
    code_hash text NOT NULL,
    code_expires_at timestamp with time zone NOT NULL,
    failed_attempts smallint DEFAULT 0 NOT NULL,
    last_sent_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: pending_registrations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pending_registrations (
    email character varying(255) NOT NULL,
    username character varying(100) NOT NULL,
    password_hash text NOT NULL,
    code_hash text NOT NULL,
    code_expires_at timestamp with time zone NOT NULL,
    failed_attempts smallint DEFAULT 0 NOT NULL,
    last_sent_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: permissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.permissions (
    id integer NOT NULL,
    name character varying(100) NOT NULL
);


--
-- Name: permissions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.permissions_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: permissions_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.permissions_id_seq OWNED BY public.permissions.id;


--
-- Name: publishers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.publishers (
    id integer NOT NULL,
    name character varying(50) NOT NULL
);


--
-- Name: publishers_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.publishers_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: publishers_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.publishers_id_seq OWNED BY public.publishers.id;


--
-- Name: refresh_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.refresh_tokens (
    id bigint NOT NULL,
    user_id integer NOT NULL,
    token_hash text NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    revoked_at timestamp with time zone,
    replaced_by_id bigint
);


--
-- Name: refresh_tokens_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.refresh_tokens_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: refresh_tokens_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.refresh_tokens_id_seq OWNED BY public.refresh_tokens.id;


--
-- Name: reservations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.reservations (
    id integer NOT NULL,
    user_id integer NOT NULL,
    book_id integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    return_deadline date NOT NULL,
    status character varying(20) DEFAULT 'reserved'::character varying NOT NULL,
    borrowed_at timestamp with time zone,
    finished_at timestamp with time zone,
    CONSTRAINT reservations_status_check CHECK (((status)::text = ANY ((ARRAY['reserved'::character varying, 'borrowed'::character varying, 'returned'::character varying, 'cancelled'::character varying, 'expired'::character varying])::text[])))
);


--
-- Name: reservations_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.reservations_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: reservations_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.reservations_id_seq OWNED BY public.reservations.id;


--
-- Name: role_permissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.role_permissions (
    role_id integer NOT NULL,
    permission_id integer NOT NULL
);


--
-- Name: roles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.roles (
    id integer NOT NULL,
    name character varying(50) NOT NULL
);


--
-- Name: roles_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.roles_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: roles_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.roles_id_seq OWNED BY public.roles.id;


--
-- Name: teacher_emails; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.teacher_emails (
    id integer NOT NULL,
    email character varying(255) NOT NULL
);


--
-- Name: teacher_emails_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.teacher_emails_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: teacher_emails_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.teacher_emails_id_seq OWNED BY public.teacher_emails.id;


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id integer NOT NULL,
    email character varying(255) NOT NULL,
    username character varying(100) NOT NULL,
    password_hash text NOT NULL,
    registered_at timestamp with time zone DEFAULT now() NOT NULL,
    role_id integer NOT NULL
);


--
-- Name: users_favourites; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users_favourites (
    user_id integer NOT NULL,
    book_id integer NOT NULL,
    added_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE users_favourites; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.users_favourites IS 'many-to-many relationship table between users and their favourite books';


--
-- Name: users_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.users_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: users_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.users_id_seq OWNED BY public.users.id;


--
-- Name: waitlist; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.waitlist (
    id integer NOT NULL,
    book_id integer NOT NULL,
    user_id integer NOT NULL,
    added_at timestamp with time zone DEFAULT now() NOT NULL,
    offered_at timestamp with time zone,
    offer_expires_at timestamp with time zone,
    CONSTRAINT waitlist_offer_check CHECK ((((offered_at IS NULL) AND (offer_expires_at IS NULL)) OR ((offered_at IS NOT NULL) AND (offer_expires_at IS NOT NULL))))
);


--
-- Name: waitlist_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.waitlist_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: waitlist_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.waitlist_id_seq OWNED BY public.waitlist.id;


--
-- Name: authors id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authors ALTER COLUMN id SET DEFAULT nextval('public.authors_id_seq'::regclass);


--
-- Name: book_languages id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.book_languages ALTER COLUMN id SET DEFAULT nextval('public.book_languages_id_seq'::regclass);


--
-- Name: book_reviews id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.book_reviews ALTER COLUMN id SET DEFAULT nextval('public.book_reviews_id_seq'::regclass);


--
-- Name: books id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.books ALTER COLUMN id SET DEFAULT nextval('public.books_id_seq'::regclass);


--
-- Name: categories id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.categories ALTER COLUMN id SET DEFAULT nextval('public.categories_id_seq'::regclass);


--
-- Name: notifications id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications ALTER COLUMN id SET DEFAULT nextval('public.notifications_id_seq'::regclass);


--
-- Name: permissions id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.permissions ALTER COLUMN id SET DEFAULT nextval('public.permissions_id_seq'::regclass);


--
-- Name: publishers id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.publishers ALTER COLUMN id SET DEFAULT nextval('public.publishers_id_seq'::regclass);


--
-- Name: refresh_tokens id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens ALTER COLUMN id SET DEFAULT nextval('public.refresh_tokens_id_seq'::regclass);


--
-- Name: reservations id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reservations ALTER COLUMN id SET DEFAULT nextval('public.reservations_id_seq'::regclass);


--
-- Name: roles id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.roles ALTER COLUMN id SET DEFAULT nextval('public.roles_id_seq'::regclass);


--
-- Name: teacher_emails id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.teacher_emails ALTER COLUMN id SET DEFAULT nextval('public.teacher_emails_id_seq'::regclass);


--
-- Name: users id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users ALTER COLUMN id SET DEFAULT nextval('public.users_id_seq'::regclass);


--
-- Name: waitlist id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.waitlist ALTER COLUMN id SET DEFAULT nextval('public.waitlist_id_seq'::regclass);


--
-- Data for Name: authors; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.authors VALUES
	(1, 'Robert C. Martin'),
	(2, 'Martin Fowler'),
	(3, 'Eric Evans'),
	(4, 'Donald Knuth'),
	(5, 'Joshua Bloch'),
	(6, 'Hovhannes Tumanyan'),
	(7, 'Yeghishe Charents'),
	(8, 'Raffi'),
	(9, 'Paruyr Sevak'),
	(10, 'Hrant Matevosyan'),
	(11, 'Fyodor Dostoevsky'),
	(12, 'Leo Tolstoy'),
	(13, 'Anton Chekhov'),
	(14, 'Mikhail Bulgakov'),
	(15, 'Aleksandr Pushkin'),
	(16, 'Kent Beck'),
	(17, 'Andrew Hunt'),
	(18, 'David Thomas'),
	(19, 'Erich Gamma'),
	(20, 'Richard Helm'),
	(21, 'Ralph Johnson'),
	(22, 'John Vlissides'),
	(23, 'Brian Kernighan'),
	(24, 'Dennis Ritchie'),
	(25, 'Bjarne Stroustrup'),
	(26, 'Nikolay Kun'),
	(27, 'Levon Shant'),
	(28, 'Zabel Yesayan'),
	(29, 'Vardan Mamikonyan'),
	(30, 'N.K. Baghdasaryan');


--
-- Data for Name: book_authors; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.book_authors VALUES
	(1, 2),
	(2, 3),
	(3, 4),
	(4, 5),
	(5, 6),
	(6, 7),
	(7, 8),
	(8, 9),
	(9, 10),
	(10, 11),
	(11, 12),
	(12, 13),
	(13, 14),
	(14, 15),
	(15, 16),
	(16, 17),
	(17, 18),
	(18, 19),
	(19, 20),
	(20, 21),
	(21, 22),
	(22, 23),
	(23, 24),
	(24, 25),
	(25, 26),
	(26, 27),
	(27, 28),
	(28, 29),
	(29, 30),
	(30, 1),
	(31, 2),
	(32, 3),
	(33, 4),
	(34, 5),
	(35, 6),
	(36, 7),
	(37, 8),
	(38, 9),
	(39, 10),
	(40, 11),
	(41, 12),
	(42, 13),
	(43, 14),
	(44, 15),
	(45, 16),
	(46, 17),
	(47, 18),
	(48, 19),
	(49, 20),
	(50, 21);
INSERT INTO public.book_authors VALUES
	(51, 22),
	(52, 23),
	(53, 24),
	(54, 25),
	(55, 26),
	(56, 27),
	(57, 28),
	(58, 29),
	(59, 30),
	(60, 1),
	(61, 2),
	(62, 3),
	(63, 4),
	(64, 5),
	(65, 6),
	(66, 7),
	(67, 8),
	(68, 9),
	(69, 10),
	(70, 11),
	(71, 12),
	(72, 13),
	(73, 14),
	(74, 15),
	(75, 16),
	(76, 17),
	(77, 18),
	(78, 19),
	(79, 20),
	(80, 21),
	(81, 22),
	(82, 23),
	(83, 24),
	(84, 25),
	(85, 26),
	(86, 27),
	(87, 28),
	(88, 29),
	(89, 30),
	(90, 1),
	(91, 2),
	(92, 3),
	(93, 4),
	(94, 5),
	(95, 6),
	(96, 7),
	(97, 8),
	(98, 9),
	(99, 10),
	(100, 11);
INSERT INTO public.book_authors VALUES
	(101, 12),
	(102, 13),
	(103, 14),
	(104, 15),
	(105, 16),
	(106, 17),
	(107, 18),
	(108, 19),
	(109, 20),
	(110, 21),
	(111, 22),
	(112, 23),
	(113, 24),
	(114, 25),
	(115, 26),
	(116, 27),
	(117, 28),
	(118, 29),
	(119, 30),
	(120, 1),
	(121, 2),
	(122, 3),
	(123, 4),
	(124, 5),
	(125, 6),
	(126, 7),
	(127, 8),
	(128, 9),
	(129, 10),
	(130, 11),
	(131, 12),
	(132, 13),
	(133, 14),
	(134, 15),
	(135, 16),
	(136, 17),
	(137, 18),
	(138, 19),
	(139, 20),
	(140, 21),
	(141, 22),
	(142, 23),
	(143, 24),
	(144, 25),
	(145, 26),
	(146, 27),
	(147, 28),
	(148, 29),
	(149, 30),
	(150, 1);
INSERT INTO public.book_authors VALUES
	(151, 2),
	(152, 3),
	(153, 4),
	(154, 5),
	(155, 6),
	(156, 7),
	(157, 8),
	(158, 9),
	(159, 10),
	(160, 11),
	(161, 12),
	(162, 13),
	(163, 14),
	(164, 15),
	(165, 16),
	(166, 17),
	(167, 18),
	(168, 19),
	(169, 20),
	(170, 21),
	(171, 22),
	(172, 23),
	(173, 24),
	(174, 25),
	(175, 26),
	(176, 27),
	(177, 28),
	(178, 29),
	(179, 30),
	(180, 1),
	(181, 2),
	(182, 3),
	(183, 4),
	(184, 5),
	(185, 6),
	(186, 7),
	(187, 8),
	(188, 9),
	(189, 10),
	(190, 11),
	(191, 12),
	(192, 13),
	(193, 14),
	(194, 15),
	(195, 16),
	(196, 17),
	(197, 18),
	(198, 19),
	(199, 20),
	(200, 21);
INSERT INTO public.book_authors VALUES
	(1, 13),
	(2, 14),
	(3, 15),
	(4, 16),
	(5, 17),
	(6, 18),
	(7, 19),
	(8, 20),
	(9, 21),
	(10, 22),
	(11, 23),
	(12, 24),
	(13, 25),
	(14, 26),
	(15, 27),
	(16, 28),
	(17, 29),
	(18, 30),
	(19, 1),
	(20, 2),
	(21, 3),
	(22, 4),
	(23, 5),
	(24, 6),
	(25, 7);


--
-- Data for Name: book_languages; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.book_languages VALUES
	(1, 'English'),
	(2, 'Armenian'),
	(3, 'Russian');


--
-- Data for Name: book_reviews; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.book_reviews VALUES
	(3, 4, 1, 5, 'The best book on software craftsmanship.', '2026-04-17'),
	(6, 4, 61, 5, 'Шедевр мировой литературы. Глубокий психологизм.', '2026-04-04'),
	(9, 4, 2, 4, 'Solid patterns, helped me with my senior project.', '2026-04-12'),
	(12, 4, 5, 3, 'Interesting history of OS/360.', '2026-04-19'),
	(15, 4, 63, 5, 'Одна из самых любимых книг.', '2026-04-19'),
	(18, 4, 8, 4, 'The math is hard but the algorithms are explained well.', '2026-04-19'),
	(21, 4, 11, 4, 'Classic Dragon book.', '2026-04-19'),
	(24, 4, 14, 5, 'TDD is the only way to write reliable code.', '2026-04-19'),
	(27, 4, 17, 4, 'Clean architecture is key.', '2026-04-19'),
	(30, 4, 20, 4, 'Great intro to microservices.', '2026-04-19'),
	(1, NULL, 1, 5, 'Essential for every developer.', '2026-04-09'),
	(4, NULL, 101, 5, 'Շատ օգտակար գիրք է քննություններին պատրաստվելու համար:', '2026-04-11'),
	(7, NULL, 61, 4, 'Тяжелая, но обязательная к прочтению книга.', '2026-04-07'),
	(10, NULL, 3, 5, 'Refactoring is a daily habit now.', '2026-04-19'),
	(13, NULL, 102, 5, 'Թումանյանի հերթական գլուխգործոցը:', '2026-04-19'),
	(16, NULL, 64, 4, 'Прекрасный слог Пушкина.', '2026-04-19'),
	(19, NULL, 104, 5, 'Պատմական կարևոր դրվագներ:', '2026-04-19'),
	(22, NULL, 12, 5, 'Helped me understand low-level systems.', '2026-04-19'),
	(25, NULL, 15, 5, 'The C bible.', '2026-04-19'),
	(28, NULL, 18, 5, 'Automation is the future.', '2026-04-19'),
	(2, NULL, 1, 4, 'Great concepts, though some examples are a bit dated.', '2026-04-14'),
	(5, NULL, 101, 3, 'Լավն է, բայց որոշ մասեր բարդ են ձևակերպված:', '2026-04-18'),
	(8, NULL, 2, 5, 'The "Gang of Four" classic. Must have on your shelf.', '2026-03-30'),
	(11, NULL, 4, 4, 'Pragmatic advice for real-world problems.', '2026-04-19'),
	(14, NULL, 103, 5, 'Էպոսը կարդալիս հպարտություն ես զգում:', '2026-04-19'),
	(17, NULL, 7, 5, 'A massive but necessary book for construction.', '2026-04-19'),
	(20, NULL, 105, 3, 'Չարենցի ոճը յուրահատուկ է:', '2026-04-19'),
	(23, NULL, 13, 4, 'If you deal with legacy code, read this.', '2026-04-19'),
	(26, NULL, 16, 5, 'Best practices for Java.', '2026-04-19'),
	(29, NULL, 19, 5, 'Knuth is a genius.', '2026-04-19');


--
-- Data for Name: books; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.books VALUES
	(180, 'Հայկական գրականության ընտրանի 180', 'Edition 2026', 'https://www.gutenberg.org/files/1180/1180-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 180.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430180-L.jpg', 5, 5, 4, 1, 2, 14, NULL, NULL, NULL),
	(190, 'Հայկական գրականության ընտրանի 190', 'Edition 2026', 'https://www.gutenberg.org/files/1190/1190-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 190.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430190-L.jpg', 15, 15, 4, 11, 2, 14, NULL, NULL, NULL),
	(20, 'English Technical Reference Vol. 20', 'Edition 2026', NULL, 4, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 20.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430020-L.jpg', 5, 5, NULL, 1, 1, 14, NULL, NULL, NULL),
	(183, 'Հայկական գրականության ընտրանի 183', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_183.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 183.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430183-L.jpg', 8, 8, 4, 4, 2, 29, NULL, NULL, NULL),
	(184, 'Հայկական գրականության ընտրանի 184', 'Edition 2026', 'https://www.gutenberg.org/files/1184/1184-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 184.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430184-L.jpg', 9, 9, 4, 5, 2, 30, NULL, NULL, NULL),
	(188, 'Հայկական գրականության ընտրանի 188', 'Edition 2026', 'https://www.gutenberg.org/files/1188/1188-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 188.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430188-L.jpg', 13, 13, 4, 9, 2, 34, NULL, NULL, NULL),
	(189, 'Հայկական գրականության ընտրանի 189', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_189.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 189.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430189-L.jpg', 14, 14, 4, 10, 2, 35, NULL, NULL, NULL),
	(191, 'Հայկական գրականության ընտրանի 191', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_191.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 191.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430191-L.jpg', 16, 16, 4, 12, 2, 37, NULL, NULL, NULL),
	(192, 'Հայկական գրականության ընտրանի 192', 'Edition 2026', 'https://www.gutenberg.org/files/1192/1192-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 192.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430192-L.jpg', 17, 17, 4, 13, 2, 38, NULL, NULL, NULL),
	(193, 'Հայկական գրականության ընտրանի 193', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_193.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 193.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430193-L.jpg', 18, 18, 4, 14, 2, 39, NULL, NULL, NULL),
	(194, 'Հայկական գրականության ընտրանի 194', 'Edition 2026', 'https://www.gutenberg.org/files/1194/1194-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 194.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430194-L.jpg', 19, 19, 4, 15, 2, 40, NULL, NULL, NULL),
	(195, 'Հայկական գրականության ընտրանի 195', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_195.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 195.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430195-L.jpg', 20, 20, 4, 16, 2, 41, NULL, NULL, NULL),
	(196, 'Հայկական գրականության ընտրանի 196', 'Edition 2026', 'https://www.gutenberg.org/files/1196/1196-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 196.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430196-L.jpg', 21, 21, 4, 17, 2, 42, NULL, NULL, NULL),
	(197, 'Հայկական գրականության ընտրանի 197', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_197.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 197.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430197-L.jpg', 22, 22, 4, 18, 2, 43, NULL, NULL, NULL),
	(198, 'Հայկական գրականության ընտրանի 198', 'Edition 2026', 'https://www.gutenberg.org/files/1198/1198-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 198.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430198-L.jpg', 23, 23, 4, 19, 2, 44, NULL, NULL, NULL),
	(199, 'Հայկական գրականության ընտրանի 199', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_199.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 199.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430199-L.jpg', 24, 24, 4, 20, 2, 45, NULL, NULL, NULL),
	(19, 'English Technical Reference Vol. 19', 'Edition 2026', NULL, 5, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 19.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430019-L.jpg', 24, 24, NULL, 20, 1, 29, NULL, NULL, NULL),
	(10, 'English Technical Reference Vol. 10', 'Edition 2026', NULL, NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 10.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430010-L.jpg', 15, 15, NULL, 11, 1, 14, NULL, NULL, NULL),
	(40, 'English Technical Reference Vol. 40', 'Edition 2026', 'https://www.gutenberg.org/files/1040/1040-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 40.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430040-L.jpg', 5, 5, 4, 1, 1, 14, NULL, NULL, NULL),
	(60, 'English Technical Reference Vol. 60', 'Edition 2026', 'https://www.gutenberg.org/files/1060/1060-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 60.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430060-L.jpg', 5, 5, 4, 1, 1, 14, NULL, NULL, NULL),
	(70, 'Русское классическое издание №70', 'Edition 2026', 'https://www.gutenberg.org/files/1070/1070-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №70.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430070-L.jpg', 15, 15, 4, 11, 3, 14, NULL, NULL, NULL),
	(80, 'Русское классическое издание №80', 'Edition 2026', 'https://www.gutenberg.org/files/1080/1080-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №80.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430080-L.jpg', 5, 5, 4, 1, 3, 14, NULL, NULL, NULL),
	(100, 'Русское классическое издание №100', 'Edition 2026', 'https://www.gutenberg.org/files/1100/1100-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №100.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430100-L.jpg', 5, 5, 4, 1, 3, 14, NULL, NULL, NULL),
	(110, 'Հայկական գրականության ընտրանի 110', 'Edition 2026', 'https://www.gutenberg.org/files/1110/1110-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 110.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430110-L.jpg', 15, 15, 4, 11, 2, 14, NULL, NULL, NULL),
	(120, 'Հայկական գրականության ընտրանի 120', 'Edition 2026', 'https://www.gutenberg.org/files/1120/1120-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 120.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430120-L.jpg', 5, 5, 4, 1, 2, 14, NULL, NULL, NULL),
	(130, 'Հայկական գրականության ընտրանի 130', 'Edition 2026', 'https://www.gutenberg.org/files/1130/1130-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 130.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430130-L.jpg', 15, 15, 4, 11, 2, 14, NULL, NULL, NULL),
	(140, 'Հայկական գրականության ընտրանի 140', 'Edition 2026', 'https://www.gutenberg.org/files/1140/1140-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 140.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430140-L.jpg', 5, 5, 4, 1, 2, 14, NULL, NULL, NULL),
	(150, 'Հայկական գրականության ընտրանի 150', 'Edition 2026', 'https://www.gutenberg.org/files/1150/1150-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 150.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430150-L.jpg', 15, 15, 4, 11, 2, 14, NULL, NULL, NULL),
	(160, 'Հայկական գրականության ընտրանի 160', 'Edition 2026', 'https://www.gutenberg.org/files/1160/1160-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 160.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430160-L.jpg', 5, 5, 4, 1, 2, 14, NULL, NULL, NULL),
	(170, 'Հայկական գրականության ընտրանի 170', 'Edition 2026', 'https://www.gutenberg.org/files/1170/1170-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 170.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430170-L.jpg', 15, 15, 4, 11, 2, 14, NULL, NULL, NULL),
	(6, 'English Technical Reference Vol. 6', 'Edition 2026', NULL, NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 6.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430006-L.jpg', 11, 11, NULL, 7, 1, 16, NULL, NULL, NULL),
	(21, 'English Technical Reference Vol. 21', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_21.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 21.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430021-L.jpg', 6, 6, 4, 2, 1, 31, NULL, NULL, NULL),
	(22, 'English Technical Reference Vol. 22', 'Edition 2026', 'https://www.gutenberg.org/files/1022/1022-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 22.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430022-L.jpg', 7, 7, 4, 3, 1, 32, NULL, NULL, NULL),
	(23, 'English Technical Reference Vol. 23', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_23.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 23.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430023-L.jpg', 8, 8, 4, 4, 1, 33, NULL, NULL, NULL),
	(24, 'English Technical Reference Vol. 24', 'Edition 2026', 'https://www.gutenberg.org/files/1024/1024-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 24.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430024-L.jpg', 9, 9, 4, 5, 1, 34, NULL, NULL, NULL),
	(25, 'English Technical Reference Vol. 25', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_25.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 25.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430025-L.jpg', 10, 10, 4, 6, 1, 35, NULL, NULL, NULL),
	(26, 'English Technical Reference Vol. 26', 'Edition 2026', 'https://www.gutenberg.org/files/1026/1026-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 26.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430026-L.jpg', 11, 11, 4, 7, 1, 36, NULL, NULL, NULL),
	(9, 'English Technical Reference Vol. 9', 'Edition 2026', NULL, NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 9.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430009-L.jpg', 14, 14, NULL, 10, 1, 19, NULL, NULL, NULL),
	(50, 'English Technical Reference Vol. 50', 'Edition 2026', 'https://www.gutenberg.org/files/1050/1050-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 50.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430050-L.jpg', 15, 15, 4, 11, 1, 14, NULL, NULL, NULL),
	(49, 'English Technical Reference Vol. 49', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_49.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 49.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430049-L.jpg', 14, 14, 4, 10, 1, 18, NULL, NULL, NULL),
	(51, 'English Technical Reference Vol. 51', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_51.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 51.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430051-L.jpg', 16, 16, 4, 12, 1, 20, NULL, NULL, NULL),
	(62, 'Война и мир', 'Edition 2026', 'https://www.gutenberg.org/files/1062/1062-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Война и мир.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430062-L.jpg', 7, 7, 4, 3, 3, 31, NULL, NULL, NULL),
	(37, 'English Technical Reference Vol. 37', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_37.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 37.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430037-L.jpg', 22, 22, 4, 18, 1, 47, NULL, NULL, NULL),
	(38, 'English Technical Reference Vol. 38', 'Edition 2026', 'https://www.gutenberg.org/files/1038/1038-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 38.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430038-L.jpg', 23, 23, 4, 19, 1, 48, NULL, NULL, NULL),
	(39, 'English Technical Reference Vol. 39', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_39.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 39.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430039-L.jpg', 24, 24, 4, 20, 1, 49, NULL, NULL, NULL),
	(41, 'English Technical Reference Vol. 41', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_41.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 41.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430041-L.jpg', 6, 6, 4, 2, 1, 10, NULL, NULL, NULL),
	(42, 'English Technical Reference Vol. 42', 'Edition 2026', 'https://www.gutenberg.org/files/1042/1042-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 42.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430042-L.jpg', 7, 7, 4, 3, 1, 11, NULL, NULL, NULL),
	(43, 'English Technical Reference Vol. 43', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_43.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 43.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430043-L.jpg', 8, 8, 4, 4, 1, 12, NULL, NULL, NULL),
	(44, 'English Technical Reference Vol. 44', 'Edition 2026', 'https://www.gutenberg.org/files/1044/1044-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 44.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430044-L.jpg', 9, 9, 4, 5, 1, 13, NULL, NULL, NULL),
	(45, 'English Technical Reference Vol. 45', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_45.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 45.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430045-L.jpg', 10, 10, 4, 6, 1, 14, NULL, NULL, NULL);
INSERT INTO public.books VALUES
	(46, 'English Technical Reference Vol. 46', 'Edition 2026', 'https://www.gutenberg.org/files/1046/1046-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 46.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430046-L.jpg', 11, 11, 4, 7, 1, 15, NULL, NULL, NULL),
	(47, 'English Technical Reference Vol. 47', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_47.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 47.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430047-L.jpg', 12, 12, 4, 8, 1, 16, NULL, NULL, NULL),
	(48, 'English Technical Reference Vol. 48', 'Edition 2026', 'https://www.gutenberg.org/files/1048/1048-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 48.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430048-L.jpg', 13, 13, 4, 9, 1, 17, NULL, NULL, NULL),
	(52, 'English Technical Reference Vol. 52', 'Edition 2026', 'https://www.gutenberg.org/files/1052/1052-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 52.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430052-L.jpg', 17, 17, 4, 13, 1, 21, NULL, NULL, NULL),
	(53, 'English Technical Reference Vol. 53', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_53.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 53.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430053-L.jpg', 18, 18, 4, 14, 1, 22, NULL, NULL, NULL),
	(54, 'English Technical Reference Vol. 54', 'Edition 2026', 'https://www.gutenberg.org/files/1054/1054-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 54.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430054-L.jpg', 19, 19, 4, 15, 1, 23, NULL, NULL, NULL),
	(55, 'English Technical Reference Vol. 55', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_55.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 55.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430055-L.jpg', 20, 20, 4, 16, 1, 24, NULL, NULL, NULL),
	(90, 'Русское классическое издание №90', 'Edition 2026', 'https://www.gutenberg.org/files/1090/1090-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №90.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430090-L.jpg', 15, 15, 4, 11, 3, 14, NULL, NULL, NULL),
	(89, 'Русское классическое издание №89', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_89.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №89.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430089-L.jpg', 14, 14, 4, 10, 3, 17, NULL, NULL, NULL),
	(91, 'Русское классическое издание №91', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_91.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №91.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430091-L.jpg', 16, 16, 4, 12, 3, 19, NULL, NULL, NULL),
	(92, 'Русское классическое издание №92', 'Edition 2026', 'https://www.gutenberg.org/files/1092/1092-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №92.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430092-L.jpg', 17, 17, 4, 13, 3, 20, NULL, NULL, NULL),
	(56, 'English Technical Reference Vol. 56', 'Edition 2026', 'https://www.gutenberg.org/files/1056/1056-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 56.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430056-L.jpg', 21, 21, 4, 17, 1, 25, NULL, NULL, NULL),
	(57, 'English Technical Reference Vol. 57', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_57.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 57.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430057-L.jpg', 22, 22, 4, 18, 1, 26, NULL, NULL, NULL),
	(58, 'English Technical Reference Vol. 58', 'Edition 2026', 'https://www.gutenberg.org/files/1058/1058-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 58.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430058-L.jpg', 23, 23, 4, 19, 1, 27, NULL, NULL, NULL),
	(59, 'English Technical Reference Vol. 59', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_59.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 59.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430059-L.jpg', 24, 24, 4, 20, 1, 28, NULL, NULL, NULL),
	(65, 'Русское классическое издание №65', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_65.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №65.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430065-L.jpg', 10, 10, 4, 6, 3, 34, NULL, NULL, NULL),
	(66, 'Русское классическое издание №66', 'Edition 2026', 'https://www.gutenberg.org/files/1066/1066-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №66.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430066-L.jpg', 11, 11, 4, 7, 3, 35, NULL, NULL, NULL),
	(67, 'Русское классическое издание №67', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_67.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №67.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430067-L.jpg', 12, 12, 4, 8, 3, 36, NULL, NULL, NULL),
	(68, 'Русское классическое издание №68', 'Edition 2026', 'https://www.gutenberg.org/files/1068/1068-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №68.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430068-L.jpg', 13, 13, 4, 9, 3, 37, NULL, NULL, NULL),
	(69, 'Русское классическое издание №69', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_69.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №69.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430069-L.jpg', 14, 14, 4, 10, 3, 38, NULL, NULL, NULL),
	(71, 'Русское классическое издание №71', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_71.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №71.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430071-L.jpg', 16, 16, 4, 12, 3, 40, NULL, NULL, NULL),
	(72, 'Русское классическое издание №72', 'Edition 2026', 'https://www.gutenberg.org/files/1072/1072-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №72.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430072-L.jpg', 17, 17, 4, 13, 3, 41, NULL, NULL, NULL),
	(73, 'Русское классическое издание №73', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_73.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №73.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430073-L.jpg', 18, 18, 4, 14, 3, 42, NULL, NULL, NULL),
	(74, 'Русское классическое издание №74', 'Edition 2026', 'https://www.gutenberg.org/files/1074/1074-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №74.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430074-L.jpg', 19, 19, 4, 15, 3, 43, NULL, NULL, NULL),
	(75, 'Русское классическое издание №75', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_75.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №75.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430075-L.jpg', 20, 20, 4, 16, 3, 44, NULL, NULL, NULL),
	(76, 'Русское классическое издание №76', 'Edition 2026', 'https://www.gutenberg.org/files/1076/1076-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №76.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430076-L.jpg', 21, 21, 4, 17, 3, 45, NULL, NULL, NULL),
	(77, 'Русское классическое издание №77', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_77.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №77.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430077-L.jpg', 22, 22, 4, 18, 3, 46, NULL, NULL, NULL),
	(78, 'Русское классическое издание №78', 'Edition 2026', 'https://www.gutenberg.org/files/1078/1078-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №78.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430078-L.jpg', 23, 23, 4, 19, 3, 47, NULL, NULL, NULL),
	(79, 'Русское классическое издание №79', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_79.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №79.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430079-L.jpg', 24, 24, 4, 20, 3, 48, NULL, NULL, NULL),
	(81, 'Русское классическое издание №81', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_81.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №81.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430081-L.jpg', 6, 6, 4, 2, 3, 50, NULL, NULL, NULL),
	(82, 'Русское классическое издание №82', 'Edition 2026', 'https://www.gutenberg.org/files/1082/1082-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №82.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430082-L.jpg', 7, 7, 4, 3, 3, 10, NULL, NULL, NULL),
	(83, 'Русское классическое издание №83', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_83.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №83.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430083-L.jpg', 8, 8, 4, 4, 3, 11, NULL, NULL, NULL),
	(84, 'Русское классическое издание №84', 'Edition 2026', 'https://www.gutenberg.org/files/1084/1084-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №84.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430084-L.jpg', 9, 9, 4, 5, 3, 12, NULL, NULL, NULL),
	(85, 'Русское классическое издание №85', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_85.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №85.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430085-L.jpg', 10, 10, 4, 6, 3, 13, NULL, NULL, NULL),
	(86, 'Русское классическое издание №86', 'Edition 2026', 'https://www.gutenberg.org/files/1086/1086-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №86.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430086-L.jpg', 11, 11, 4, 7, 3, 14, NULL, NULL, NULL),
	(87, 'Русское классическое издание №87', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_87.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №87.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430087-L.jpg', 12, 12, 4, 8, 3, 15, NULL, NULL, NULL),
	(88, 'Русское классическое издание №88', 'Edition 2026', 'https://www.gutenberg.org/files/1088/1088-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №88.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430088-L.jpg', 13, 13, 4, 9, 3, 16, NULL, NULL, NULL),
	(93, 'Русское классическое издание №93', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_93.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №93.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430093-L.jpg', 18, 18, 4, 14, 3, 21, NULL, NULL, NULL),
	(94, 'Русское классическое издание №94', 'Edition 2026', 'https://www.gutenberg.org/files/1094/1094-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №94.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430094-L.jpg', 19, 19, 4, 15, 3, 22, NULL, NULL, NULL),
	(95, 'Русское классическое издание №95', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_95.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №95.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430095-L.jpg', 20, 20, 4, 16, 3, 23, NULL, NULL, NULL),
	(96, 'Русское классическое издание №96', 'Edition 2026', 'https://www.gutenberg.org/files/1096/1096-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №96.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430096-L.jpg', 21, 21, 4, 17, 3, 24, NULL, NULL, NULL),
	(97, 'Русское классическое издание №97', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_97.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №97.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430097-L.jpg', 22, 22, 4, 18, 3, 25, NULL, NULL, NULL),
	(98, 'Русское классическое издание №98', 'Edition 2026', 'https://www.gutenberg.org/files/1098/1098-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №98.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430098-L.jpg', 23, 23, 4, 19, 3, 26, NULL, NULL, NULL),
	(99, 'Русское классическое издание №99', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_99.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №99.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430099-L.jpg', 24, 24, 4, 20, 3, 27, NULL, NULL, NULL),
	(106, 'Հայկական գրականության ընտրանի 106', 'Edition 2026', 'https://www.gutenberg.org/files/1106/1106-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 106.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430106-L.jpg', 11, 11, 4, 7, 2, 34, NULL, NULL, NULL),
	(107, 'Հայկական գրականության ընտրանի 107', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_107.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 107.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430107-L.jpg', 12, 12, 4, 8, 2, 35, NULL, NULL, NULL),
	(108, 'Հայկական գրականության ընտրանի 108', 'Edition 2026', 'https://www.gutenberg.org/files/1108/1108-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 108.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430108-L.jpg', 13, 13, 4, 9, 2, 36, NULL, NULL, NULL),
	(109, 'Հայկական գրականության ընտրանի 109', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_109.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 109.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430109-L.jpg', 14, 14, 4, 10, 2, 37, NULL, NULL, NULL),
	(111, 'Հայկական գրականության ընտրանի 111', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_111.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 111.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430111-L.jpg', 16, 16, 4, 12, 2, 39, NULL, NULL, NULL),
	(112, 'Հայկական գրականության ընտրանի 112', 'Edition 2026', 'https://www.gutenberg.org/files/1112/1112-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 112.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430112-L.jpg', 17, 17, 4, 13, 2, 40, NULL, NULL, NULL);
INSERT INTO public.books VALUES
	(113, 'Հայկական գրականության ընտրանի 113', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_113.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 113.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430113-L.jpg', 18, 18, 4, 14, 2, 41, NULL, NULL, NULL),
	(114, 'Հայկական գրականության ընտրանի 114', 'Edition 2026', 'https://www.gutenberg.org/files/1114/1114-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 114.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430114-L.jpg', 19, 19, 4, 15, 2, 42, NULL, NULL, NULL),
	(115, 'Հայկական գրականության ընտրանի 115', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_115.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 115.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430115-L.jpg', 20, 20, 4, 16, 2, 43, NULL, NULL, NULL),
	(116, 'Հայկական գրականության ընտրանի 116', 'Edition 2026', 'https://www.gutenberg.org/files/1116/1116-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 116.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430116-L.jpg', 21, 21, 4, 17, 2, 44, NULL, NULL, NULL),
	(117, 'Հայկական գրականության ընտրանի 117', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_117.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 117.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430117-L.jpg', 22, 22, 4, 18, 2, 45, NULL, NULL, NULL),
	(118, 'Հայկական գրականության ընտրանի 118', 'Edition 2026', 'https://www.gutenberg.org/files/1118/1118-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 118.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430118-L.jpg', 23, 23, 4, 19, 2, 46, NULL, NULL, NULL),
	(119, 'Հայկական գրականության ընտրանի 119', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_119.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 119.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430119-L.jpg', 24, 24, 4, 20, 2, 47, NULL, NULL, NULL),
	(121, 'Հայկական գրականության ընտրանի 121', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_121.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 121.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430121-L.jpg', 6, 6, 4, 2, 2, 49, NULL, NULL, NULL),
	(122, 'Հայկական գրականության ընտրանի 122', 'Edition 2026', 'https://www.gutenberg.org/files/1122/1122-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 122.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430122-L.jpg', 7, 7, 4, 3, 2, 50, NULL, NULL, NULL),
	(123, 'Հայկական գրականության ընտրանի 123', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_123.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 123.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430123-L.jpg', 8, 8, 4, 4, 2, 10, NULL, NULL, NULL),
	(124, 'Հայկական գրականության ընտրանի 124', 'Edition 2026', 'https://www.gutenberg.org/files/1124/1124-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 124.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430124-L.jpg', 9, 9, 4, 5, 2, 11, NULL, NULL, NULL),
	(125, 'Հայկական գրականության ընտրանի 125', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_125.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 125.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430125-L.jpg', 10, 10, 4, 6, 2, 12, NULL, NULL, NULL),
	(126, 'Հայկական գրականության ընտրանի 126', 'Edition 2026', 'https://www.gutenberg.org/files/1126/1126-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 126.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430126-L.jpg', 11, 11, 4, 7, 2, 13, NULL, NULL, NULL),
	(127, 'Հայկական գրականության ընտրանի 127', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_127.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 127.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430127-L.jpg', 12, 12, 4, 8, 2, 14, NULL, NULL, NULL),
	(128, 'Հայկական գրականության ընտրանի 128', 'Edition 2026', 'https://www.gutenberg.org/files/1128/1128-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 128.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430128-L.jpg', 13, 13, 4, 9, 2, 15, NULL, NULL, NULL),
	(129, 'Հայկական գրականության ընտրանի 129', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_129.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 129.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430129-L.jpg', 14, 14, 4, 10, 2, 16, NULL, NULL, NULL),
	(131, 'Հայկական գրականության ընտրանի 131', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_131.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 131.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430131-L.jpg', 16, 16, 4, 12, 2, 18, NULL, NULL, NULL),
	(132, 'Հայկական գրականության ընտրանի 132', 'Edition 2026', 'https://www.gutenberg.org/files/1132/1132-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 132.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430132-L.jpg', 17, 17, 4, 13, 2, 19, NULL, NULL, NULL),
	(133, 'Հայկական գրականության ընտրանի 133', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_133.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 133.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430133-L.jpg', 18, 18, 4, 14, 2, 20, NULL, NULL, NULL),
	(134, 'Հայկական գրականության ընտրանի 134', 'Edition 2026', 'https://www.gutenberg.org/files/1134/1134-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 134.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430134-L.jpg', 19, 19, 4, 15, 2, 21, NULL, NULL, NULL),
	(135, 'Հայկական գրականության ընտրանի 135', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_135.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 135.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430135-L.jpg', 20, 20, 4, 16, 2, 22, NULL, NULL, NULL),
	(136, 'Հայկական գրականության ընտրանի 136', 'Edition 2026', 'https://www.gutenberg.org/files/1136/1136-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 136.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430136-L.jpg', 21, 21, 4, 17, 2, 23, NULL, NULL, NULL),
	(137, 'Հայկական գրականության ընտրանի 137', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_137.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 137.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430137-L.jpg', 22, 22, 4, 18, 2, 24, NULL, NULL, NULL),
	(138, 'Հայկական գրականության ընտրանի 138', 'Edition 2026', 'https://www.gutenberg.org/files/1138/1138-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 138.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430138-L.jpg', 23, 23, 4, 19, 2, 25, NULL, NULL, NULL),
	(139, 'Հայկական գրականության ընտրանի 139', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_139.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 139.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430139-L.jpg', 24, 24, 4, 20, 2, 26, NULL, NULL, NULL),
	(141, 'Հայկական գրականության ընտրանի 141', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_141.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 141.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430141-L.jpg', 6, 6, 4, 2, 2, 28, NULL, NULL, NULL),
	(142, 'Հայկական գրականության ընտրանի 142', 'Edition 2026', 'https://www.gutenberg.org/files/1142/1142-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 142.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430142-L.jpg', 7, 7, 4, 3, 2, 29, NULL, NULL, NULL),
	(143, 'Հայկական գրականության ընտրանի 143', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_143.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 143.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430143-L.jpg', 8, 8, 4, 4, 2, 30, NULL, NULL, NULL),
	(144, 'Հայկական գրականության ընտրանի 144', 'Edition 2026', 'https://www.gutenberg.org/files/1144/1144-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 144.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430144-L.jpg', 9, 9, 4, 5, 2, 31, NULL, NULL, NULL),
	(145, 'Հայկական գրականության ընտրանի 145', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_145.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 145.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430145-L.jpg', 10, 10, 4, 6, 2, 32, NULL, NULL, NULL),
	(146, 'Հայկական գրականության ընտրանի 146', 'Edition 2026', 'https://www.gutenberg.org/files/1146/1146-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 146.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430146-L.jpg', 11, 11, 4, 7, 2, 33, NULL, NULL, NULL),
	(147, 'Հայկական գրականության ընտրանի 147', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_147.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 147.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430147-L.jpg', 12, 12, 4, 8, 2, 34, NULL, NULL, NULL),
	(148, 'Հայկական գրականության ընտրանի 148', 'Edition 2026', 'https://www.gutenberg.org/files/1148/1148-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 148.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430148-L.jpg', 13, 13, 4, 9, 2, 35, NULL, NULL, NULL),
	(149, 'Հայկական գրականության ընտրանի 149', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_149.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 149.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430149-L.jpg', 14, 14, 4, 10, 2, 36, NULL, NULL, NULL),
	(151, 'Հայկական գրականության ընտրանի 151', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_151.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 151.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430151-L.jpg', 16, 16, 4, 12, 2, 38, NULL, NULL, NULL),
	(152, 'Հայկական գրականության ընտրանի 152', 'Edition 2026', 'https://www.gutenberg.org/files/1152/1152-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 152.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430152-L.jpg', 17, 17, 4, 13, 2, 39, NULL, NULL, NULL),
	(153, 'Հայկական գրականության ընտրանի 153', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_153.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 153.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430153-L.jpg', 18, 18, 4, 14, 2, 40, NULL, NULL, NULL),
	(154, 'Հայկական գրականության ընտրանի 154', 'Edition 2026', 'https://www.gutenberg.org/files/1154/1154-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 154.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430154-L.jpg', 19, 19, 4, 15, 2, 41, NULL, NULL, NULL),
	(155, 'Հայկական գրականության ընտրանի 155', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_155.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 155.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430155-L.jpg', 20, 20, 4, 16, 2, 42, NULL, NULL, NULL),
	(156, 'Հայկական գրականության ընտրանի 156', 'Edition 2026', 'https://www.gutenberg.org/files/1156/1156-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 156.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430156-L.jpg', 21, 21, 4, 17, 2, 43, NULL, NULL, NULL),
	(157, 'Հայկական գրականության ընտրանի 157', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_157.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 157.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430157-L.jpg', 22, 22, 4, 18, 2, 44, NULL, NULL, NULL),
	(158, 'Հայկական գրականության ընտրանի 158', 'Edition 2026', 'https://www.gutenberg.org/files/1158/1158-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 158.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430158-L.jpg', 23, 23, 4, 19, 2, 45, NULL, NULL, NULL),
	(159, 'Հայկական գրականության ընտրանի 159', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_159.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 159.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430159-L.jpg', 24, 24, 4, 20, 2, 46, NULL, NULL, NULL),
	(161, 'Հայկական գրականության ընտրանի 161', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_161.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 161.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430161-L.jpg', 6, 6, 4, 2, 2, 48, NULL, NULL, NULL),
	(162, 'Հայկական գրականության ընտրանի 162', 'Edition 2026', 'https://www.gutenberg.org/files/1162/1162-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 162.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430162-L.jpg', 7, 7, 4, 3, 2, 49, NULL, NULL, NULL),
	(163, 'Հայկական գրականության ընտրանի 163', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_163.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 163.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430163-L.jpg', 8, 8, 4, 4, 2, 50, NULL, NULL, NULL),
	(164, 'Հայկական գրականության ընտրանի 164', 'Edition 2026', 'https://www.gutenberg.org/files/1164/1164-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 164.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430164-L.jpg', 9, 9, 4, 5, 2, 10, NULL, NULL, NULL),
	(165, 'Հայկական գրականության ընտրանի 165', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_165.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 165.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430165-L.jpg', 10, 10, 4, 6, 2, 11, NULL, NULL, NULL),
	(185, 'Հայկական գրականության ընտրանի 185', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_185.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 185.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430185-L.jpg', 10, 10, 4, 6, 2, 31, NULL, NULL, NULL),
	(186, 'Հայկական գրականության ընտրանի 186', 'Edition 2026', 'https://www.gutenberg.org/files/1186/1186-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 186.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430186-L.jpg', 11, 11, 4, 7, 2, 32, NULL, NULL, NULL);
INSERT INTO public.books VALUES
	(187, 'Հայկական գրականության ընտրանի 187', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_187.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 187.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430187-L.jpg', 12, 12, 4, 8, 2, 33, NULL, NULL, NULL),
	(166, 'Հայկական գրականության ընտրանի 166', 'Edition 2026', 'https://www.gutenberg.org/files/1166/1166-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 166.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430166-L.jpg', 11, 11, 4, 7, 2, 12, NULL, NULL, NULL),
	(167, 'Հայկական գրականության ընտրանի 167', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_167.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 167.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430167-L.jpg', 12, 12, 4, 8, 2, 13, NULL, NULL, NULL),
	(168, 'Հայկական գրականության ընտրանի 168', 'Edition 2026', 'https://www.gutenberg.org/files/1168/1168-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 168.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430168-L.jpg', 13, 13, 4, 9, 2, 14, NULL, NULL, NULL),
	(169, 'Հայկական գրականության ընտրանի 169', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_169.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 169.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430169-L.jpg', 14, 14, 4, 10, 2, 15, NULL, NULL, NULL),
	(171, 'Հայկական գրականության ընտրանի 171', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_171.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 171.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430171-L.jpg', 16, 16, 4, 12, 2, 17, NULL, NULL, NULL),
	(172, 'Հայկական գրականության ընտրանի 172', 'Edition 2026', 'https://www.gutenberg.org/files/1172/1172-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 172.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430172-L.jpg', 17, 17, 4, 13, 2, 18, NULL, NULL, NULL),
	(173, 'Հայկական գրականության ընտրանի 173', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_173.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 173.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430173-L.jpg', 18, 18, 4, 14, 2, 19, NULL, NULL, NULL),
	(174, 'Հայկական գրականության ընտրանի 174', 'Edition 2026', 'https://www.gutenberg.org/files/1174/1174-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 174.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430174-L.jpg', 19, 19, 4, 15, 2, 20, NULL, NULL, NULL),
	(175, 'Հայկական գրականության ընտրանի 175', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_175.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 175.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430175-L.jpg', 20, 20, 4, 16, 2, 21, NULL, NULL, NULL),
	(176, 'Հայկական գրականության ընտրանի 176', 'Edition 2026', 'https://www.gutenberg.org/files/1176/1176-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 176.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430176-L.jpg', 21, 21, 4, 17, 2, 22, NULL, NULL, NULL),
	(177, 'Հայկական գրականության ընտրանի 177', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_177.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 177.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430177-L.jpg', 22, 22, 4, 18, 2, 23, NULL, NULL, NULL),
	(178, 'Հայկական գրականության ընտրանի 178', 'Edition 2026', 'https://www.gutenberg.org/files/1178/1178-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 178.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430178-L.jpg', 23, 23, 4, 19, 2, 24, NULL, NULL, NULL),
	(179, 'Հայկական գրականության ընտրանի 179', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_179.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 179.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430179-L.jpg', 24, 24, 4, 20, 2, 25, NULL, NULL, NULL),
	(61, 'Преступление и наказание', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_61.pdf', 4.5, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Преступление и наказание.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430061-L.jpg', 6, 6, 4, 2, 3, 30, NULL, NULL, NULL),
	(63, 'Русское классическое издание №63', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_63.pdf', 5, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №63.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430063-L.jpg', 8, 8, 4, 4, 3, 32, NULL, NULL, NULL),
	(3, 'Refactoring', 'Edition 2026', NULL, 5, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Refactoring.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430003-L.jpg', 8, 8, NULL, 4, 1, 13, NULL, NULL, NULL),
	(181, 'Հայկական գրականության ընտրանի 181', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_181.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 181.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430181-L.jpg', 6, 6, 4, 2, 2, 27, NULL, NULL, NULL),
	(182, 'Հայկական գրականության ընտրանի 182', 'Edition 2026', 'https://www.gutenberg.org/files/1182/1182-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 182.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430182-L.jpg', 7, 7, 4, 3, 2, 28, NULL, NULL, NULL),
	(102, 'Անուշ', 'Edition 2026', 'https://www.gutenberg.org/files/1102/1102-pdf.pdf', 5, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Անուշ.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430102-L.jpg', 7, 7, 4, 3, 2, 30, NULL, NULL, NULL),
	(64, 'Русское классическое издание №64', 'Edition 2026', 'https://www.gutenberg.org/files/1064/1064-pdf.pdf', 4, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Русское классическое издание №64.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430064-L.jpg', 9, 9, 4, 5, 3, 33, NULL, NULL, NULL),
	(104, 'Հայկական գրականության ընտրանի 104', 'Edition 2026', 'https://www.gutenberg.org/files/1104/1104-pdf.pdf', 5, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 104.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430104-L.jpg', 9, 9, 4, 5, 2, 32, NULL, NULL, NULL),
	(2, 'Design Patterns', 'Edition 2026', NULL, 4.5, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Design Patterns.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430002-L.jpg', 7, 7, NULL, 3, 1, 12, NULL, NULL, NULL),
	(4, 'English Technical Reference Vol. 4', 'Edition 2026', NULL, 4, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 4.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430004-L.jpg', 9, 9, NULL, 5, 1, 14, NULL, NULL, NULL),
	(103, 'Հայկական գրականության ընտրանի 103', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_103.pdf', 5, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 103.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430103-L.jpg', 8, 8, 4, 4, 2, 31, NULL, NULL, NULL),
	(105, 'Հայկական գրականության ընտրանի 105', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_105.pdf', 3, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 105.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430105-L.jpg', 10, 10, 4, 6, 2, 33, NULL, NULL, NULL),
	(30, 'English Technical Reference Vol. 30', 'Edition 2026', 'https://www.gutenberg.org/files/1030/1030-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 30.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430030-L.jpg', 15, 15, 4, 11, 1, 14, NULL, NULL, NULL),
	(27, 'English Technical Reference Vol. 27', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_27.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 27.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430027-L.jpg', 12, 12, 4, 8, 1, 37, NULL, NULL, NULL),
	(1, 'Clean Code', 'Edition 2026', NULL, 4.6666665, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Clean Code.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430001-L.jpg', 6, 6, NULL, 2, 1, 11, NULL, NULL, NULL),
	(28, 'English Technical Reference Vol. 28', 'Edition 2026', 'https://www.gutenberg.org/files/1028/1028-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 28.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430028-L.jpg', 13, 13, 4, 9, 1, 38, NULL, NULL, NULL),
	(29, 'English Technical Reference Vol. 29', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_29.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 29.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430029-L.jpg', 14, 14, 4, 10, 1, 39, NULL, NULL, NULL),
	(31, 'English Technical Reference Vol. 31', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_31.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 31.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430031-L.jpg', 16, 16, 4, 12, 1, 41, NULL, NULL, NULL),
	(32, 'English Technical Reference Vol. 32', 'Edition 2026', 'https://www.gutenberg.org/files/1032/1032-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 32.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430032-L.jpg', 17, 17, 4, 13, 1, 42, NULL, NULL, NULL),
	(33, 'English Technical Reference Vol. 33', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_33.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 33.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430033-L.jpg', 18, 18, 4, 14, 1, 43, NULL, NULL, NULL),
	(34, 'English Technical Reference Vol. 34', 'Edition 2026', 'https://www.gutenberg.org/files/1034/1034-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 34.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430034-L.jpg', 19, 19, 4, 15, 1, 44, NULL, NULL, NULL),
	(35, 'English Technical Reference Vol. 35', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_35.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 35.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430035-L.jpg', 20, 20, 4, 16, 1, 45, NULL, NULL, NULL),
	(36, 'English Technical Reference Vol. 36', 'Edition 2026', 'https://www.gutenberg.org/files/1036/1036-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 36.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430036-L.jpg', 21, 21, 4, 17, 1, 46, NULL, NULL, NULL),
	(5, 'English Technical Reference Vol. 5', 'Edition 2026', NULL, 3, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 5.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430005-L.jpg', 10, 10, NULL, 6, 1, 15, NULL, NULL, NULL),
	(8, 'English Technical Reference Vol. 8', 'Edition 2026', NULL, 4, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 8.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430008-L.jpg', 13, 13, NULL, 9, 1, 18, NULL, NULL, NULL),
	(11, 'English Technical Reference Vol. 11', 'Edition 2026', NULL, 4, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 11.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430011-L.jpg', 16, 16, NULL, 12, 1, 21, NULL, NULL, NULL),
	(14, 'English Technical Reference Vol. 14', 'Edition 2026', NULL, 5, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 14.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430014-L.jpg', 19, 19, NULL, 15, 1, 24, NULL, NULL, NULL),
	(17, 'English Technical Reference Vol. 17', 'Edition 2026', NULL, 4, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 17.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430017-L.jpg', 22, 22, NULL, 18, 1, 27, NULL, NULL, NULL),
	(200, 'Հայկական գրականության ընտրանի 200', 'Edition 2026', 'https://www.gutenberg.org/files/1200/1200-pdf.pdf', NULL, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Հայկական գրականության ընտրանի 200.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430200-L.jpg', 5, 5, 4, 1, 2, 14, NULL, NULL, NULL),
	(12, 'English Technical Reference Vol. 12', 'Edition 2026', NULL, 5, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 12.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430012-L.jpg', 17, 17, NULL, 13, 1, 22, NULL, NULL, NULL),
	(15, 'English Technical Reference Vol. 15', 'Edition 2026', NULL, 5, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 15.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430015-L.jpg', 20, 20, NULL, 16, 1, 25, NULL, NULL, NULL),
	(18, 'English Technical Reference Vol. 18', 'Edition 2026', NULL, 5, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 18.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430018-L.jpg', 23, 23, NULL, 19, 1, 28, NULL, NULL, NULL),
	(101, 'Կոնֆլիկտաբանություն', 'Edition 2026', 'https://archive.org/download/opensource/library_resource_101.pdf', 4, 'A high-quality academic resource. This book provides extensive knowledge and documentation on Կոնֆլիկտաբանություն.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430101-L.jpg', 6, 6, 4, 2, 2, 29, NULL, NULL, NULL),
	(7, 'English Technical Reference Vol. 7', 'Edition 2026', NULL, 5, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 7.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430007-L.jpg', 12, 12, NULL, 8, 1, 17, NULL, NULL, NULL),
	(13, 'English Technical Reference Vol. 13', 'Edition 2026', NULL, 4, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 13.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430013-L.jpg', 18, 18, NULL, 14, 1, 23, NULL, NULL, NULL),
	(16, 'English Technical Reference Vol. 16', 'Edition 2026', NULL, 5, 'A high-quality academic resource. This book provides extensive knowledge and documentation on English Technical Reference Vol. 16.', NULL, NULL, 'https://covers.openlibrary.org/b/id/8430016-L.jpg', 21, 21, NULL, 17, 1, 26, NULL, NULL, NULL);


--
-- Data for Name: books_categories; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.books_categories VALUES
	(1, 2),
	(2, 3),
	(3, 4),
	(4, 5),
	(5, 6),
	(6, 7),
	(7, 8),
	(8, 9),
	(9, 10),
	(10, 11),
	(11, 12),
	(12, 13),
	(13, 14),
	(14, 15),
	(15, 16),
	(16, 17),
	(17, 18),
	(18, 19),
	(19, 20),
	(20, 1),
	(21, 2),
	(22, 3),
	(23, 4),
	(24, 5),
	(25, 6),
	(26, 7),
	(27, 8),
	(28, 9),
	(29, 10),
	(30, 11),
	(31, 12),
	(32, 13),
	(33, 14),
	(34, 15),
	(35, 16),
	(36, 17),
	(37, 18),
	(38, 19),
	(39, 20),
	(40, 1),
	(41, 2),
	(42, 3),
	(43, 4),
	(44, 5),
	(45, 6),
	(46, 7),
	(47, 8),
	(48, 9),
	(49, 10),
	(50, 11);
INSERT INTO public.books_categories VALUES
	(51, 12),
	(52, 13),
	(53, 14),
	(54, 15),
	(55, 16),
	(56, 17),
	(57, 18),
	(58, 19),
	(59, 20),
	(60, 1),
	(61, 2),
	(62, 3),
	(63, 4),
	(64, 5),
	(65, 6),
	(66, 7),
	(67, 8),
	(68, 9),
	(69, 10),
	(70, 11),
	(71, 12),
	(72, 13),
	(73, 14),
	(74, 15),
	(75, 16),
	(76, 17),
	(77, 18),
	(78, 19),
	(79, 20),
	(80, 1),
	(81, 2),
	(82, 3),
	(83, 4),
	(84, 5),
	(85, 6),
	(86, 7),
	(87, 8),
	(88, 9),
	(89, 10),
	(90, 11),
	(91, 12),
	(92, 13),
	(93, 14),
	(94, 15),
	(95, 16),
	(96, 17),
	(97, 18),
	(98, 19),
	(99, 20),
	(100, 1);
INSERT INTO public.books_categories VALUES
	(101, 2),
	(102, 3),
	(103, 4),
	(104, 5),
	(105, 6),
	(106, 7),
	(107, 8),
	(108, 9),
	(109, 10),
	(110, 11),
	(111, 12),
	(112, 13),
	(113, 14),
	(114, 15),
	(115, 16),
	(116, 17),
	(117, 18),
	(118, 19),
	(119, 20),
	(120, 1),
	(121, 2),
	(122, 3),
	(123, 4),
	(124, 5),
	(125, 6),
	(126, 7),
	(127, 8),
	(128, 9),
	(129, 10),
	(130, 11),
	(131, 12),
	(132, 13),
	(133, 14),
	(134, 15),
	(135, 16),
	(136, 17),
	(137, 18),
	(138, 19),
	(139, 20),
	(140, 1),
	(141, 2),
	(142, 3),
	(143, 4),
	(144, 5),
	(145, 6),
	(146, 7),
	(147, 8),
	(148, 9),
	(149, 10),
	(150, 11);
INSERT INTO public.books_categories VALUES
	(151, 12),
	(152, 13),
	(153, 14),
	(154, 15),
	(155, 16),
	(156, 17),
	(157, 18),
	(158, 19),
	(159, 20),
	(160, 1),
	(161, 2),
	(162, 3),
	(163, 4),
	(164, 5),
	(165, 6),
	(166, 7),
	(167, 8),
	(168, 9),
	(169, 10),
	(170, 11),
	(171, 12),
	(172, 13),
	(173, 14),
	(174, 15),
	(175, 16),
	(176, 17),
	(177, 18),
	(178, 19),
	(179, 20),
	(180, 1),
	(181, 2),
	(182, 3),
	(183, 4),
	(184, 5),
	(185, 6),
	(186, 7),
	(187, 8),
	(188, 9),
	(189, 10),
	(190, 11),
	(191, 12),
	(192, 13),
	(193, 14),
	(194, 15),
	(195, 16),
	(196, 17),
	(197, 18),
	(198, 19),
	(199, 20),
	(200, 1);


--
-- Data for Name: categories; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.categories VALUES
	(1, 'Computer Science'),
	(2, 'Software Engineering'),
	(3, 'Mathematics'),
	(4, 'History'),
	(5, 'Armenian Literature'),
	(6, 'Russian Literature'),
	(7, 'Philosophy'),
	(8, 'Physics'),
	(9, 'Fiction'),
	(10, 'Psychology'),
	(11, 'Economics'),
	(12, 'Art'),
	(13, 'Design'),
	(14, 'Architecture'),
	(15, 'Biography'),
	(16, 'Science Fiction'),
	(17, 'Poetry'),
	(18, 'Drama'),
	(19, 'Education'),
	(20, 'Linguistics');


--
-- Data for Name: files; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: permissions; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.permissions VALUES
	(1, 'books.view'),
	(2, 'books.add'),
	(3, 'books.delete'),
	(4, 'users.manage'),
	(5, 'books.delete.own'),
	(6, 'books.edit'),
	(7, 'books.edit.own'),
	(8, 'reviews.moderate'),
	(9, 'reservations.create'),
	(10, 'reservations.manage'),
	(11, 'catalog.manage');


--
-- Data for Name: publishers; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.publishers VALUES
	(1, 'Addison-Wesley'),
	(2, 'O’Reilly Media'),
	(3, 'MIT Press'),
	(4, 'Pearson'),
	(5, 'Manning'),
	(6, 'Sargis Khachents'),
	(7, 'Edit Print'),
	(8, 'Zangak'),
	(9, 'Antares'),
	(10, 'Phaidon'),
	(11, 'AST'),
	(12, 'Eksmo'),
	(13, 'Piter'),
	(14, 'Azbooka'),
	(15, 'Mann, Ivanov and Ferber'),
	(16, 'Oxford University Press'),
	(17, 'Penguin Classics'),
	(18, 'HarperCollins'),
	(19, 'Vintage'),
	(20, 'Springer');


--
-- Data for Name: reservations; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.reservations VALUES
	(1, 4, 1, '2026-04-19 00:00:00+04', '2026-05-03', 'returned', '2026-04-19 00:00:00+04', '2026-05-03 00:00:00+04');


--
-- Data for Name: role_permissions; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.role_permissions VALUES
	(1, 1),
	(2, 1),
	(2, 2),
	(3, 1),
	(3, 2),
	(3, 3),
	(3, 4),
	(2, 5),
	(2, 7),
	(3, 6),
	(3, 8),
	(1, 9),
	(2, 9),
	(3, 10),
	(3, 11);


--
-- Data for Name: roles; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.roles VALUES
	(1, 'student'),
	(2, 'teacher'),
	(3, 'admin');


--
-- Data for Name: teacher_emails; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: users; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.users VALUES
	(1, 'arthurdanielyan.tt319@polytechnic.am', 'Artur Danielyan', '$2y$10$8kIS0tRGBYRGPewUBlq/J.IcK1KPuq.kJFse0B7acwDGZ4PF4CkTC', '2026-04-08 15:55:43.263403+04', 3),
	(4, 'ellahovhannisyan@polytechnic.am', 'Ella Hovhannisyan', '$2y$10$JuROuN7BQWWy5G2IcCuh5./LQAIAkeDNFHwbs16.xGPg3HazRYcZu', '2026-04-08 15:58:52.632006+04', 2);


--
-- Data for Name: users_favourites; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.users_favourites VALUES
	(4, 1, '2026-11-15 20:19:44.334652+04'),
	(4, 103, '2026-11-15 20:19:44.334652+04'),
	(4, 63, '2026-11-15 20:19:44.334652+04');


--
-- Data for Name: waitlist; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Name: authors_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.authors_id_seq', 30, true);


--
-- Name: book_languages_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.book_languages_id_seq', 3, true);


--
-- Name: book_reviews_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.book_reviews_id_seq', 31, true);


--
-- Name: books_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.books_id_seq', 201, true);


--
-- Name: categories_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.categories_id_seq', 25, true);


--
-- Name: notifications_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.notifications_id_seq', 1, false);


--
-- Name: permissions_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.permissions_id_seq', 11, true);


--
-- Name: publishers_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.publishers_id_seq', 20, true);


--
-- Name: refresh_tokens_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.refresh_tokens_id_seq', 15, true);


--
-- Name: reservations_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.reservations_id_seq', 13, true);


--
-- Name: roles_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.roles_id_seq', 3, true);


--
-- Name: teacher_emails_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.teacher_emails_id_seq', 1, true);


--
-- Name: users_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.users_id_seq', 6, true);


--
-- Name: waitlist_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.waitlist_id_seq', 2, true);


--
-- Name: authors authors_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authors
    ADD CONSTRAINT authors_pk PRIMARY KEY (id);


--
-- Name: book_authors book_authors_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.book_authors
    ADD CONSTRAINT book_authors_pkey PRIMARY KEY (book_id, author_id);


--
-- Name: book_languages book_languages_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.book_languages
    ADD CONSTRAINT book_languages_pk PRIMARY KEY (id);


--
-- Name: book_reviews book_reviews_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.book_reviews
    ADD CONSTRAINT book_reviews_pk PRIMARY KEY (id);


--
-- Name: book_reviews book_reviews_user_id_book_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.book_reviews
    ADD CONSTRAINT book_reviews_user_id_book_id_key UNIQUE (user_id, book_id);


--
-- Name: books_categories books_categories_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.books_categories
    ADD CONSTRAINT books_categories_pk PRIMARY KEY (book_id, category_id);


--
-- Name: books books_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.books
    ADD CONSTRAINT books_pk PRIMARY KEY (id);


--
-- Name: categories categories_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.categories
    ADD CONSTRAINT categories_pk PRIMARY KEY (id);


--
-- Name: files files_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.files
    ADD CONSTRAINT files_pkey PRIMARY KEY (id);


--
-- Name: notifications notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);


--
-- Name: password_resets password_resets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_resets
    ADD CONSTRAINT password_resets_pkey PRIMARY KEY (user_id);


--
-- Name: pending_registrations pending_registrations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pending_registrations
    ADD CONSTRAINT pending_registrations_pkey PRIMARY KEY (email);


--
-- Name: permissions permissions_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.permissions
    ADD CONSTRAINT permissions_name_key UNIQUE (name);


--
-- Name: permissions permissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.permissions
    ADD CONSTRAINT permissions_pkey PRIMARY KEY (id);


--
-- Name: publishers publishers_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.publishers
    ADD CONSTRAINT publishers_pk PRIMARY KEY (id);


--
-- Name: refresh_tokens refresh_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);


--
-- Name: refresh_tokens refresh_tokens_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_token_hash_key UNIQUE (token_hash);


--
-- Name: reservations reservations_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT reservations_pk PRIMARY KEY (id);


--
-- Name: role_permissions role_permissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role_permissions
    ADD CONSTRAINT role_permissions_pkey PRIMARY KEY (role_id, permission_id);


--
-- Name: roles roles_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.roles
    ADD CONSTRAINT roles_name_key UNIQUE (name);


--
-- Name: roles roles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.roles
    ADD CONSTRAINT roles_pkey PRIMARY KEY (id);


--
-- Name: teacher_emails teacher_emails_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.teacher_emails
    ADD CONSTRAINT teacher_emails_email_key UNIQUE (email);


--
-- Name: teacher_emails teacher_emails_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.teacher_emails
    ADD CONSTRAINT teacher_emails_pkey PRIMARY KEY (id);


--
-- Name: waitlist unique_waitlist_entry; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.waitlist
    ADD CONSTRAINT unique_waitlist_entry UNIQUE (book_id, user_id);


--
-- Name: users_favourites users_favourites_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users_favourites
    ADD CONSTRAINT users_favourites_pk PRIMARY KEY (user_id, book_id);


--
-- Name: users users_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pk PRIMARY KEY (id);


--
-- Name: users users_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_unique UNIQUE (email);


--
-- Name: waitlist waitlist_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.waitlist
    ADD CONSTRAINT waitlist_pkey PRIMARY KEY (id);


--
-- Name: authors_name_lower_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX authors_name_lower_key ON public.authors USING btree (lower((name)::text));


--
-- Name: book_languages_name_lower_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX book_languages_name_lower_key ON public.book_languages USING btree (lower((name)::text));


--
-- Name: book_reviews_book_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX book_reviews_book_id_idx ON public.book_reviews USING btree (book_id);


--
-- Name: categories_name_lower_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX categories_name_lower_key ON public.categories USING btree (lower((name)::text));


--
-- Name: notifications_unsent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX notifications_unsent_idx ON public.notifications USING btree (id) WHERE (emailed_at IS NULL);


--
-- Name: notifications_user_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX notifications_user_id_created_at_idx ON public.notifications USING btree (user_id, created_at);


--
-- Name: notifications_user_id_dedupe_key_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX notifications_user_id_dedupe_key_key ON public.notifications USING btree (user_id, dedupe_key);


--
-- Name: publishers_name_lower_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX publishers_name_lower_key ON public.publishers USING btree (lower((name)::text));


--
-- Name: refresh_tokens_user_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX refresh_tokens_user_id_idx ON public.refresh_tokens USING btree (user_id);


--
-- Name: reservations_one_active_per_user_book; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX reservations_one_active_per_user_book ON public.reservations USING btree (user_id, book_id) WHERE ((status)::text = ANY ((ARRAY['reserved'::character varying, 'borrowed'::character varying])::text[]));


--
-- Name: waitlist_book_id_added_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX waitlist_book_id_added_at_idx ON public.waitlist USING btree (book_id, added_at, id);


--
-- Name: authors authors_rules_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authors_rules_trigger BEFORE INSERT OR DELETE OR UPDATE ON public.authors FOR EACH ROW EXECUTE FUNCTION public.enforce_book_reference_rules();


--
-- Name: book_authors book_authors_rules_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER book_authors_rules_trigger BEFORE INSERT OR DELETE OR UPDATE ON public.book_authors FOR EACH ROW EXECUTE FUNCTION public.enforce_book_link_rules();


--
-- Name: book_languages book_languages_rules_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER book_languages_rules_trigger BEFORE INSERT OR DELETE OR UPDATE ON public.book_languages FOR EACH ROW EXECUTE FUNCTION public.enforce_catalog_list_rules();


--
-- Name: book_reviews book_reviews_rules_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER book_reviews_rules_trigger BEFORE INSERT OR DELETE OR UPDATE ON public.book_reviews FOR EACH ROW EXECUTE FUNCTION public.enforce_review_rules();


--
-- Name: books book_security_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER book_security_trigger BEFORE INSERT OR DELETE OR UPDATE ON public.books FOR EACH ROW EXECUTE FUNCTION public.enforce_book_rules();


--
-- Name: books books_adjust_copies_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER books_adjust_copies_trigger BEFORE UPDATE OF total_copies ON public.books FOR EACH ROW EXECUTE FUNCTION public.adjust_copies_to_total();


--
-- Name: books_categories books_categories_rules_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER books_categories_rules_trigger BEFORE INSERT OR DELETE OR UPDATE ON public.books_categories FOR EACH ROW EXECUTE FUNCTION public.enforce_book_link_rules();


--
-- Name: books books_release_added_copies_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER books_release_added_copies_trigger AFTER UPDATE OF total_copies ON public.books FOR EACH ROW EXECUTE FUNCTION public.release_added_copies();


--
-- Name: categories categories_rules_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER categories_rules_trigger BEFORE INSERT OR DELETE OR UPDATE ON public.categories FOR EACH ROW EXECUTE FUNCTION public.enforce_catalog_list_rules();


--
-- Name: notifications notifications_rules_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER notifications_rules_trigger BEFORE UPDATE ON public.notifications FOR EACH ROW EXECUTE FUNCTION public.enforce_notification_rules();


--
-- Name: publishers publishers_rules_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER publishers_rules_trigger BEFORE INSERT OR DELETE OR UPDATE ON public.publishers FOR EACH ROW EXECUTE FUNCTION public.enforce_book_reference_rules();


--
-- Name: reservations reservations_inventory_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER reservations_inventory_trigger AFTER INSERT OR UPDATE OF status ON public.reservations FOR EACH ROW EXECUTE FUNCTION public.manage_book_inventory();


--
-- Name: reservations reservations_rules_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER reservations_rules_trigger BEFORE INSERT OR DELETE OR UPDATE ON public.reservations FOR EACH ROW EXECUTE FUNCTION public.enforce_reservation_rules();


--
-- Name: teacher_emails trg_lock_teacher_emails; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_lock_teacher_emails BEFORE INSERT OR DELETE OR UPDATE ON public.teacher_emails FOR EACH ROW EXECUTE FUNCTION public.enforce_teacher_email_management();


--
-- Name: book_reviews trg_update_book_rating; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_update_book_rating AFTER INSERT OR DELETE OR UPDATE ON public.book_reviews FOR EACH ROW EXECUTE FUNCTION public.update_book_average_rating();


--
-- Name: users trg_validate_user_signup; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_validate_user_signup BEFORE INSERT ON public.users FOR EACH ROW EXECUTE FUNCTION public.validate_and_assign_role();


--
-- Name: users user_security_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER user_security_trigger BEFORE DELETE OR UPDATE ON public.users FOR EACH ROW EXECUTE FUNCTION public.enforce_user_management();


--
-- Name: users_favourites users_favourites_owner_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER users_favourites_owner_trigger BEFORE INSERT OR DELETE OR UPDATE ON public.users_favourites FOR EACH ROW EXECUTE FUNCTION public.enforce_own_favourites();


--
-- Name: waitlist waitlist_offer_release_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER waitlist_offer_release_trigger AFTER DELETE ON public.waitlist FOR EACH ROW EXECUTE FUNCTION public.pass_on_declined_offer();


--
-- Name: waitlist waitlist_rules_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER waitlist_rules_trigger BEFORE INSERT OR DELETE OR UPDATE ON public.waitlist FOR EACH ROW EXECUTE FUNCTION public.enforce_waitlist_rules();


--
-- Name: reservations book_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT book_id FOREIGN KEY (book_id) REFERENCES public.books(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: book_reviews book_reviews_book_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.book_reviews
    ADD CONSTRAINT book_reviews_book_id_fkey FOREIGN KEY (book_id) REFERENCES public.books(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: books books_added_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.books
    ADD CONSTRAINT books_added_by_fkey FOREIGN KEY (added_by) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: books books_cover_file_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.books
    ADD CONSTRAINT books_cover_file_id_fkey FOREIGN KEY (cover_file_id) REFERENCES public.files(id) ON DELETE SET NULL;


--
-- Name: books books_pdf_file_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.books
    ADD CONSTRAINT books_pdf_file_id_fkey FOREIGN KEY (pdf_file_id) REFERENCES public.files(id) ON DELETE SET NULL;


--
-- Name: book_authors fk_author; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.book_authors
    ADD CONSTRAINT fk_author FOREIGN KEY (author_id) REFERENCES public.authors(id) ON DELETE CASCADE;


--
-- Name: book_authors fk_book; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.book_authors
    ADD CONSTRAINT fk_book FOREIGN KEY (book_id) REFERENCES public.books(id) ON DELETE CASCADE;


--
-- Name: books_categories fk_book; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.books_categories
    ADD CONSTRAINT fk_book FOREIGN KEY (book_id) REFERENCES public.books(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: books_categories fk_category; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.books_categories
    ADD CONSTRAINT fk_category FOREIGN KEY (category_id) REFERENCES public.categories(id) ON UPDATE CASCADE;


--
-- Name: books language_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.books
    ADD CONSTRAINT language_id FOREIGN KEY (language_id) REFERENCES public.book_languages(id) ON UPDATE CASCADE;


--
-- Name: notifications notifications_book_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_book_id_fkey FOREIGN KEY (book_id) REFERENCES public.books(id) ON DELETE CASCADE;


--
-- Name: notifications notifications_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: password_resets password_resets_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_resets
    ADD CONSTRAINT password_resets_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: books publisher_if; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.books
    ADD CONSTRAINT publisher_if FOREIGN KEY (publisher_id) REFERENCES public.publishers(id) ON UPDATE CASCADE;


--
-- Name: refresh_tokens refresh_tokens_replaced_by_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_replaced_by_id_fkey FOREIGN KEY (replaced_by_id) REFERENCES public.refresh_tokens(id);


--
-- Name: refresh_tokens refresh_tokens_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: role_permissions role_permissions_permission_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role_permissions
    ADD CONSTRAINT role_permissions_permission_id_fkey FOREIGN KEY (permission_id) REFERENCES public.permissions(id) ON DELETE CASCADE;


--
-- Name: role_permissions role_permissions_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role_permissions
    ADD CONSTRAINT role_permissions_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.roles(id) ON DELETE CASCADE;


--
-- Name: book_reviews user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.book_reviews
    ADD CONSTRAINT user_id FOREIGN KEY (user_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: reservations user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT user_id FOREIGN KEY (user_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: users_favourites users_favourites_book_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users_favourites
    ADD CONSTRAINT users_favourites_book_id_fkey FOREIGN KEY (book_id) REFERENCES public.books(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: users_favourites users_favourites_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users_favourites
    ADD CONSTRAINT users_favourites_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: users users_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.roles(id);


--
-- Name: waitlist waitlist_book_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.waitlist
    ADD CONSTRAINT waitlist_book_id_fkey FOREIGN KEY (book_id) REFERENCES public.books(id) ON DELETE CASCADE;


--
-- Name: waitlist waitlist_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.waitlist
    ADD CONSTRAINT waitlist_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'lms_app') THEN
        CREATE ROLE lms_app LOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO lms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO lms_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO lms_app;
REVOKE ALL ON public.flyway_schema_history FROM lms_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO lms_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO lms_app;

