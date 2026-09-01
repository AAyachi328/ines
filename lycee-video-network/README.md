# Réseau vidéo privé du lycée — V0

Réseau social vidéo interne, fermé et sécurisé pour un établissement scolaire
(feed vertical de vidéos courtes, inspiré TikTok/Reels/Shorts dans son UX,
mais avec modération institutionnelle, isolation multi-tenant et
privacy-by-design).

Ce dossier ne contient **pas encore de code applicatif**. Il documente la
phase de conception demandée avant implémentation :

1. [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) — architecture V0,
   multi-tenant, RBAC, auth, pipeline vidéo, API, frontend, dashboard admin,
   sécurité, structure du repo, plan d'implémentation, backlog post-MVP.
2. [`docs/schema.sql`](./docs/schema.sql) — schéma PostgreSQL initial
   (DDL) avec Row-Level Security multi-tenant.

## Principes non négociables (rappel)

- Un utilisateur du lycée A ne doit **jamais** pouvoir accéder aux données
  du lycée B (isolation multi-tenant côté DB, pas seulement côté UI).
- RBAC réel côté backend (STUDENT / STAFF / MODERATOR / ADMIN), jamais un
  simple masquage d'UI.
- Aucune vidéo accessible par URL publique permanente.
- Aucune donnée d'élève mineur exploitée hors du périmètre pédagogique.
- V0 minimal : pas de messagerie privée, pas de live, pas de stories, pas
  de recommandation ML, pas de gamification addictive.

## Prochaine étape

Une fois cette architecture validée (toi + éventuellement le DPO de
l'établissement), l'implémentation suit le plan par phases de la section
13 de `ARCHITECTURE.md`, en commençant par : squelette Next.js + schéma DB
+ auth/RBAC + isolation multi-tenant.
