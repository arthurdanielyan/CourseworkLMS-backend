DELETE FROM public.notifications;

ALTER TABLE public.notifications
    ALTER COLUMN user_id SET NOT NULL,
    ALTER COLUMN title TYPE text,
    ALTER COLUMN type SET NOT NULL,
    ALTER COLUMN type TYPE varchar(30),
    ALTER COLUMN is_read SET NOT NULL,
    ALTER COLUMN created_at SET NOT NULL,
    ADD COLUMN book_id integer,
    ADD COLUMN dedupe_key varchar(100) NOT NULL,
    ADD COLUMN emailed_at timestamptz,
    ADD CONSTRAINT notifications_book_id_fkey FOREIGN KEY (book_id) REFERENCES public.books (id) ON DELETE CASCADE,
    ADD CONSTRAINT notifications_type_check CHECK (
        type IN ('waitlist_offer', 'pickup_reminder', 'reservation_expired', 'return_reminder', 'overdue')
    );

CREATE UNIQUE INDEX notifications_user_id_dedupe_key_key ON public.notifications (user_id, dedupe_key);

CREATE INDEX notifications_user_id_created_at_idx ON public.notifications (user_id, created_at);

CREATE INDEX notifications_unsent_idx ON public.notifications (id) WHERE emailed_at IS NULL;

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

CREATE TRIGGER notifications_rules_trigger BEFORE UPDATE ON public.notifications
    FOR EACH ROW EXECUTE FUNCTION public.enforce_notification_rules();
