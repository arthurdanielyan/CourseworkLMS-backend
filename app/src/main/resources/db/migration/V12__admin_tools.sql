SELECT set_config(
    'app.current_user_id',
    (SELECT u.id::text FROM public.users u JOIN public.roles r ON r.id = u.role_id WHERE r.name = 'admin' ORDER BY u.id LIMIT 1),
    true
);

INSERT INTO public.permissions (name) VALUES ('catalog.manage');

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r
JOIN public.permissions p ON (r.name, p.name) = ('admin', 'catalog.manage');

CREATE UNIQUE INDEX categories_name_lower_key ON public.categories (lower(name));

CREATE UNIQUE INDEX book_languages_name_lower_key ON public.book_languages (lower(name));

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

CREATE TRIGGER categories_rules_trigger BEFORE INSERT OR UPDATE OR DELETE ON public.categories
    FOR EACH ROW EXECUTE FUNCTION public.enforce_catalog_list_rules();

CREATE TRIGGER book_languages_rules_trigger BEFORE INSERT OR UPDATE OR DELETE ON public.book_languages
    FOR EACH ROW EXECUTE FUNCTION public.enforce_catalog_list_rules();

ALTER TABLE public.reservations
    DROP CONSTRAINT user_id,
    ADD CONSTRAINT user_id FOREIGN KEY (user_id) REFERENCES public.users (id) ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE public.book_reviews
    ALTER COLUMN user_id DROP NOT NULL,
    DROP CONSTRAINT user_id,
    ADD CONSTRAINT user_id FOREIGN KEY (user_id) REFERENCES public.users (id) ON UPDATE CASCADE ON DELETE SET NULL;
