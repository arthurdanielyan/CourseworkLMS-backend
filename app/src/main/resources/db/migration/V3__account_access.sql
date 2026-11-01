ALTER TABLE public.users ALTER COLUMN email TYPE character varying(255);

CREATE TABLE public.pending_registrations (
    email character varying(255) NOT NULL,
    username character varying(100) NOT NULL,
    password_hash text NOT NULL,
    code_hash text NOT NULL,
    code_expires_at timestamp with time zone NOT NULL,
    failed_attempts smallint DEFAULT 0 NOT NULL,
    last_sent_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pending_registrations_pkey PRIMARY KEY (email)
);

CREATE TABLE public.refresh_tokens (
    id bigserial NOT NULL,
    user_id integer NOT NULL,
    token_hash text NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    revoked_at timestamp with time zone,
    replaced_by_id bigint,
    CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id),
    CONSTRAINT refresh_tokens_token_hash_key UNIQUE (token_hash),
    CONSTRAINT refresh_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE,
    CONSTRAINT refresh_tokens_replaced_by_id_fkey FOREIGN KEY (replaced_by_id) REFERENCES public.refresh_tokens(id)
);

CREATE INDEX refresh_tokens_user_id_idx ON public.refresh_tokens (user_id);
