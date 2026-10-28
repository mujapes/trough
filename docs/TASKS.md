# Atomic task acceptance

2026-10-28. `native/store/task.{h,c}` joins [document capture](DOCUMENTS.md),
[stream ordering](STREAMS.md) and the effect journal into one stage-2 publication.

A task's durable semantic identity is the same 16-byte key used by its stream entry and
prepared effect. Capture/options live authoritatively in the effect request; its lifecycle
state and outcome live in the journal. The stream records order and historical content/
provenance references. There is no separate task status field to drift from effect recovery.
An Ask entry has kind `0x54540101`; its target is the captured document revision.

`tt_ask` validates a new capsule, then commits the exact input read guards, prepared effect,
stream entry, stable key and advanced stream head in a single SQLite transaction. Acceptance
does not clear the draft or send a provider request. Failure leaves none of these outputs
installed. An acknowledged request therefore always has both recoverable work and a stream
position, including after process death between acceptance and delivery of the receipt.

Retry uses the same intent, timestamp and capsule. It compares the whole original capsule
and entry, returns the original entry/commit, and preserves advanced/terminal/unknown effect
state. Current draft/policy revisions are not retroactively substituted into a retry.
Changing capture options, timestamp, stream or content under that identity conflicts. A
standalone effect or stream entry created separately cannot be attached after the fact.
This prevents a partial old operation from being mistaken for an atomic task acceptance.

`tt_load` checks the entry and effect identities, decodes the authoritative capture, and
checks agreement with the stream's content/provenance references. Captured options borrow
the returned task's request buffer. On insufficient outcome capacity only metadata is valid;
the caller can resize using the required outcome length. A returned task cannot be shallow
copied and discarded without rebasing its borrowed options pointer.

The effect owner remains responsible for current capabilities/revocations, authorisation,
dispatch, provider idempotency and reconciliation. These storage APIs cannot grant authority
or silently reissue an unknown effect. The eventual task output/chunk and artifact owners,
provider integration, UI projection and action registration remain construction work.

The reusable `td_capture_validate` returns exact read guards for larger publications.
`tl_append_prepared` accepts those guards and one matching prepared effect. Its retry path
requires both entry and effect to exist and match, rather than creating whichever is missing.
All these calls execute on the same storage owner; no broad app-wide revision is introduced.

`tests/core_tasks.sh` / `core.tasks` check matching acceptance commits, immutable captures,
advanced/unknown/terminal retries, source and draft changes, late guard invalidation, stale
heads, full-store rollback, split-identity rejection and ten SIGKILL cycles. Recovery checks
both directions: every entry has its effect, and the outbox contains no orphan accepted task.
This is durable task acceptance evidence, not permission, provider or live UI evidence.
