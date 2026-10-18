-- Core security: who a transaction acts for, permission-based book rules, self-service on one's own account,
-- hashed seed passwords and the non-superuser application role (ADR 0001).

-- The user the current transaction acts for. A missing user is a backend bug, raised as L0001 so the backend can
-- tell it apart from an access denial (42501).
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

CREATE OR REPLACE FUNCTION public.has_permission(required_perm text) RETURNS boolean
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

-- Editing books gets the same any/own pair of permissions as deleting them.
INSERT INTO public.permissions (name) VALUES ('books.edit'), ('books.edit.own');

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r
JOIN public.permissions p ON (r.name, p.name) IN (('admin', 'books.edit'), ('teacher', 'books.edit.own'));

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
            -- A Teacher's book is their own, and its copies and loan length are the library's to set later.
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
        -- The inventory and rating triggers keep these two columns up to date on behalf of whoever reserved or
        -- reviewed, who usually owns nothing; a nested update that touches nothing else is theirs to make.
        IF pg_trigger_depth() > 1
            AND (to_jsonb(NEW) - 'copies_available' - 'rating') = (to_jsonb(OLD) - 'copies_available' - 'rating') THEN
            RETURN NEW;
        END IF;
        IF NOT (public.has_permission('books.edit.own') AND OLD.added_by = curr_user_id) THEN
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

-- A BEFORE DELETE trigger must return OLD: returning NEW, which is NULL there, silently skipped every delete.
CREATE OR REPLACE FUNCTION public.enforce_teacher_email_management() RETURNS trigger
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

CREATE OR REPLACE FUNCTION public.enforce_user_management() RETURNS trigger
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

CREATE OR REPLACE FUNCTION public.validate_and_assign_role() RETURNS trigger
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

-- The seed users' passwords were stored in plain text; these are BCrypt hashes of the same values. The update runs as
-- the seed admin so it passes the users trigger.
SELECT set_config('app.current_user_id', '1', true);

UPDATE public.users SET password_hash = '$2y$10$8kIS0tRGBYRGPewUBlq/J.IcK1KPuq.kJFse0B7acwDGZ4PF4CkTC' WHERE id = 1;
UPDATE public.users SET password_hash = '$2y$10$GqD.aGqLeJYyoBYu5ziRAeHkHQqkg3lA8QwGBRnW/Zy1nh0crjOCK' WHERE id = 2;
UPDATE public.users SET password_hash = '$2y$10$yChjix8JQu3fdezWk0vhhOloTVAB5TxKv6gIWEAxC0PgN61t1Ofn6' WHERE id = 3;
UPDATE public.users SET password_hash = '$2y$10$JuROuN7BQWWy5G2IcCuh5./LQAIAkeDNFHwbs16.xGPg3HazRYcZu' WHERE id = 4;

-- The application connects as lms_app, which owns nothing and so cannot switch triggers off. Its password is set
-- outside migrations, once per database server.
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
