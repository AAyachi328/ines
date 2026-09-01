-- =============================================================================
-- Reseau video prive de lycee — schema PostgreSQL V0
-- Multi-tenant via organization_id + Row-Level Security (RLS).
-- Convention : timestamps sur toutes les tables, soft delete (deleted_at)
-- sur les entites de contenu, audit_log en append-only.
-- =============================================================================

create extension if not exists "pgcrypto"; -- gen_random_uuid()

-- -----------------------------------------------------------------------------
-- ENUMS
-- -----------------------------------------------------------------------------

create type membership_role as enum ('STUDENT', 'STAFF', 'MODERATOR', 'ADMIN');

create type group_kind as enum (
  'ESTABLISHMENT', 'CLASS', 'CLUB', 'ASSOCIATION', 'SUBJECT', 'PROJECT', 'EVENT'
);

create type video_status as enum ('UPLOADING', 'PROCESSING', 'READY', 'FAILED');

create type report_status as enum ('OPEN', 'IN_REVIEW', 'RESOLVED', 'DISMISSED');

create type report_reason as enum (
  'INAPPROPRIATE_CONTENT', 'HARASSMENT', 'PRIVACY_VIOLATION', 'SPAM', 'OTHER'
);

create type moderation_action_type as enum (
  'HIDE_POST', 'DELETE_POST', 'DELETE_COMMENT', 'SUSPEND_USER',
  'REINSTATE_USER', 'DISMISS_REPORT', 'ROLE_CHANGE'
);

create type notification_type as enum (
  'NEW_COMMENT', 'NEW_REACTION', 'CONTENT_PINNED', 'REPORT_RESOLVED',
  'ACCOUNT_SUSPENDED', 'MODERATION_NOTICE'
);

-- -----------------------------------------------------------------------------
-- ORGANIZATION (le lycee) — racine du tenant
-- -----------------------------------------------------------------------------

create table organization (
  id               uuid primary key default gen_random_uuid(),
  name             text not null,
  slug             text not null unique,
  retention_days   integer not null default 365, -- politique de retention configurable
  is_active        boolean not null default true,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);

-- -----------------------------------------------------------------------------
-- USER — identite globale (peut techniquement exister sans membership active,
-- mais V0 = un user = une organisation via sa membership unique active)
-- -----------------------------------------------------------------------------

create table app_user (
  id               uuid primary key default gen_random_uuid(),
  email            citext not null unique,
  password_hash    text,              -- null si uniquement magic link / SSO
  display_name     text not null,
  avatar_url       text,
  is_active        boolean not null default true,
  last_login_at    timestamptz,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  deleted_at       timestamptz
);

create table membership (
  id               uuid primary key default gen_random_uuid(),
  organization_id  uuid not null references organization(id),
  user_id          uuid not null references app_user(id),
  role             membership_role not null default 'STUDENT',
  is_suspended     boolean not null default false,
  suspended_reason text,
  suspended_at     timestamptz,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  unique (organization_id, user_id) -- V0 : un user = un membership par org
);

create index idx_membership_org on membership(organization_id);
create index idx_membership_user on membership(user_id);

-- -----------------------------------------------------------------------------
-- INVITE_CODE — creation de compte controlee par l'etablissement
-- -----------------------------------------------------------------------------

create table invite_code (
  id                  uuid primary key default gen_random_uuid(),
  organization_id     uuid not null references organization(id),
  code                text not null unique,
  role                membership_role not null default 'STUDENT',
  default_group_id    uuid, -- fk ajoutee apres creation de group (voir plus bas)
  max_uses            integer not null default 1,
  used_count          integer not null default 0,
  expires_at          timestamptz not null,
  created_by_user_id  uuid not null references app_user(id),
  created_at          timestamptz not null default now(),
  revoked_at          timestamptz
);

create index idx_invite_code_org on invite_code(organization_id);

-- -----------------------------------------------------------------------------
-- GROUP / GROUP_MEMBERSHIP
-- -----------------------------------------------------------------------------

create table app_group (
  id               uuid primary key default gen_random_uuid(),
  organization_id  uuid not null references organization(id),
  name             text not null,
  kind             group_kind not null,
  description      text,
  is_active        boolean not null default true,
  created_by_user_id uuid not null references app_user(id),
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  deleted_at       timestamptz
);

create index idx_group_org on app_group(organization_id);

alter table invite_code
  add constraint fk_invite_code_group foreign key (default_group_id) references app_group(id);

create table group_membership (
  id               uuid primary key default gen_random_uuid(),
  organization_id  uuid not null references organization(id),
  group_id         uuid not null references app_group(id),
  user_id          uuid not null references app_user(id),
  is_manager       boolean not null default false, -- responsable du groupe (STAFF/ADMIN typiquement)
  created_at       timestamptz not null default now(),
  unique (group_id, user_id)
);

create index idx_group_membership_org on group_membership(organization_id);
create index idx_group_membership_user on group_membership(user_id);
create index idx_group_membership_group on group_membership(group_id);

-- -----------------------------------------------------------------------------
-- VIDEO — metadonnees, jamais d'URL publique permanente stockee
-- -----------------------------------------------------------------------------

create table video (
  id                uuid primary key default gen_random_uuid(),
  organization_id   uuid not null references organization(id),
  uploaded_by_user_id uuid not null references app_user(id),
  provider          text not null default 'cloudflare_stream',
  provider_id       text not null,        -- Stream UID (pas une URL)
  status            video_status not null default 'UPLOADING',
  duration_seconds  numeric(6,2),
  thumbnail_provider_id text,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  deleted_at        timestamptz,
  unique (provider, provider_id)
);

create index idx_video_org on video(organization_id);

-- -----------------------------------------------------------------------------
-- POST — publication associee a une video et a un groupe
-- -----------------------------------------------------------------------------

create table post (
  id               uuid primary key default gen_random_uuid(),
  organization_id  uuid not null references organization(id),
  group_id         uuid not null references app_group(id),
  video_id         uuid not null references video(id),
  author_user_id   uuid not null references app_user(id),
  description      text,
  is_pinned        boolean not null default false,
  published_at     timestamptz, -- null tant qu'en file de moderation (si activee)
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  deleted_at       timestamptz
);

create index idx_post_org on post(organization_id);
create index idx_post_group on post(group_id);
create index idx_post_feed on post(organization_id, group_id, published_at desc)
  where deleted_at is null;

-- -----------------------------------------------------------------------------
-- REACTION / COMMENT
-- -----------------------------------------------------------------------------

create table reaction (
  id               uuid primary key default gen_random_uuid(),
  organization_id  uuid not null references organization(id),
  post_id          uuid not null references post(id),
  user_id          uuid not null references app_user(id),
  kind             text not null default 'like', -- extensible sans migration (check ci-dessous)
  created_at       timestamptz not null default now(),
  unique (post_id, user_id, kind),
  check (kind in ('like'))
);

create index idx_reaction_org on reaction(organization_id);
create index idx_reaction_post on reaction(post_id);

create table comment (
  id               uuid primary key default gen_random_uuid(),
  organization_id  uuid not null references organization(id),
  post_id          uuid not null references post(id),
  author_user_id   uuid not null references app_user(id),
  body             text not null,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  deleted_at       timestamptz
);

create index idx_comment_org on comment(organization_id);
create index idx_comment_post on comment(post_id);

-- -----------------------------------------------------------------------------
-- REPORT / MODERATION_ACTION
-- -----------------------------------------------------------------------------

create table report (
  id                uuid primary key default gen_random_uuid(),
  organization_id   uuid not null references organization(id),
  post_id           uuid references post(id),
  comment_id        uuid references comment(id),
  reported_user_id  uuid references app_user(id), -- signalement direct d'un profil
  reporter_user_id  uuid not null references app_user(id),
  reason            report_reason not null,
  details           text,
  status            report_status not null default 'OPEN',
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  check (
    (post_id is not null)::int + (comment_id is not null)::int + (reported_user_id is not null)::int = 1
  )
);

create index idx_report_org on report(organization_id);
create index idx_report_status on report(organization_id, status);

create table moderation_action (
  id                  uuid primary key default gen_random_uuid(),
  organization_id     uuid not null references organization(id),
  report_id           uuid references report(id),
  moderator_user_id   uuid not null references app_user(id),
  action_type         moderation_action_type not null,
  target_post_id      uuid references post(id),
  target_comment_id   uuid references comment(id),
  target_user_id      uuid references app_user(id),
  reason              text,
  created_at          timestamptz not null default now()
);

create index idx_moderation_action_org on moderation_action(organization_id);

-- -----------------------------------------------------------------------------
-- NOTIFICATION (in-app uniquement en V0, pas de push)
-- -----------------------------------------------------------------------------

create table notification (
  id               uuid primary key default gen_random_uuid(),
  organization_id  uuid not null references organization(id),
  user_id          uuid not null references app_user(id),
  type             notification_type not null,
  payload          jsonb not null default '{}',
  read_at          timestamptz,
  created_at       timestamptz not null default now()
);

create index idx_notification_org on notification(organization_id);
create index idx_notification_user on notification(user_id, read_at);

-- -----------------------------------------------------------------------------
-- AUDIT_LOG — append-only, jamais d'UPDATE/DELETE applicatif
-- -----------------------------------------------------------------------------

create table audit_log (
  id               uuid primary key default gen_random_uuid(),
  organization_id  uuid not null references organization(id),
  actor_user_id    uuid references app_user(id), -- null si action systeme
  action           text not null,                -- ex: 'post.delete', 'user.role_change'
  target_type      text,
  target_id        uuid,
  metadata         jsonb not null default '{}',
  created_at       timestamptz not null default now()
);

create index idx_audit_log_org on audit_log(organization_id, created_at desc);

revoke update, delete on audit_log from public;

-- =============================================================================
-- ROW-LEVEL SECURITY — isolation multi-tenant
-- La variable de session app.current_org_id est positionnee par le backend
-- a partir de la session utilisateur authentifiee, jamais depuis un
-- parametre de requete HTTP.
-- =============================================================================

do $$
declare
  t text;
begin
  for t in select unnest(array[
    'membership', 'invite_code', 'app_group', 'group_membership',
    'video', 'post', 'reaction', 'comment', 'report',
    'moderation_action', 'notification', 'audit_log'
  ])
  loop
    execute format('alter table %I enable row level security;', t);
    execute format(
      'create policy tenant_isolation on %I
         using (organization_id = current_setting(''app.current_org_id'', true)::uuid)
         with check (organization_id = current_setting(''app.current_org_id'', true)::uuid);',
      t
    );
  end loop;
end $$;

-- organization elle-meme : un user ne voit que sa propre organisation
alter table organization enable row level security;
create policy self_org_only on organization
  using (id = current_setting('app.current_org_id', true)::uuid);

-- app_user n'a pas d'organization_id direct (identite globale) : l'acces
-- est filtre au niveau applicatif via membership, pas par RLS sur cette table.
