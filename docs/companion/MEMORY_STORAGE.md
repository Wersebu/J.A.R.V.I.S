# Memory storage reconciliation

## What this checkout uses
Source/config audit, not a claim about a running Linux deployment:
- jarvis-memory depends on SQLite JDBC 3.46.1.0. SQLiteMemoryInitializer owns schema initialization; SQLiteConversationMemoryService dual-writes bounded working context and durable conversation repositories.
- application.yml selects jarvis.memory.database-path=./data/jarvis-memory.db. Existing user context is part of repository scoping.
- Semantic SQL, legacy retrieval/writes and background memory extraction default OFF. Keeping tables does not mean legacy semantic retrieval is enabled.
- Knowledge files default to knowledge.root=./knowledge (JARVIS_KNOWLEDGE_ROOT override). The rebuildable index defaults to ./data/knowledge-index.db (JARVIS_KNOWLEDGE_INDEX_DB override).
- The checked-in legacy memory embedding configuration names nomic-embed-text. The infrastructure vision describes multilingual-e5-base and PostgreSQL/pgvector; this checkout does not contain an active PostgreSQL/pgvector persistence adapter or JDBC dependency. Their deployment, dimensions, tenant schema and endpoint have not been verified. Do not mix embeddings from different models in the same vector space.

## Backward-compatible proposal
1. Inventory nonproduction and production storage read-only: resolved configuration paths, schema versions, row counts by tenant/type, embedding model/dimension, knowledge ownership and deletion behavior. Do not copy private content into status files.
2. Keep SQLite/knowledge files authoritative. Add an opt-in PostgreSQL adapter behind existing store/retrieval interfaces, with a transactional SQLite outbox for ordered, idempotent shadow writes. Preserve UUIDs, tenant IDs, sources, timestamps, confidence, categories and expiration. Explicitly encode deletion/merge tombstones; retry projection by event ID without repeating user actions.
3. Provision a NEW namespaced PostgreSQL schema only with migration approval; pgvector rows include model/dimension/version and tenant key. Tenant filters precede vector ranking. Backfill in bounded audited batches, reconcile counts/checksums and deletion/expiration, and compare shadow search quality/latency without serving it to users.
4. Feature-flag PostgreSQL reads per tenant after parity checks. Keep the outbox and SQLite writes during rollback window; never silently fall back to another user's or another model's vectors.
5. Cut over authority only after explicit review; preserve export/rollback and user-controlled deletion across both stores. No destructive migration or production storage change was performed.

Open inputs: actual deployed PostgreSQL connection metadata (through secure config, not SHARED secrets), pgvector extension/version, embedding dimension/model revision, intended ownership of knowledge files, and retention/deletion policy. Runtime verification is pending access to the designated nonproduction infrastructure.
