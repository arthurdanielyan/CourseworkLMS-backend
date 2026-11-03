CREATE TABLE public.password_resets (
    user_id integer NOT NULL,
    code_hash text NOT NULL,
    code_expires_at timestamptz NOT NULL,
    failed_attempts smallint NOT NULL DEFAULT 0,
    last_sent_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT password_resets_pkey PRIMARY KEY (user_id),
    CONSTRAINT password_resets_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users (id) ON DELETE CASCADE
);
