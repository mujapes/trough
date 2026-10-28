# Durable workspace roots

2026-10-28. `native/store/workspace.{h,c}` gives the stage-2 records a recoverable owner.
One stable workspace key names an immutable root containing the stable keys of its draft
and chronological stream. The trusted workspace owner selects three distinct, nonzero keys.
Keys identify records; they are not capabilities. User content cannot select privileged roots.

`tw_create` publishes root, initial document, empty stream and all key associations in one
store transaction. No acknowledged workspace can be missing one of its children. A full
store or colliding key leaves none of the three records or new key associations installed.
The root's canonical 64-byte little-endian `TWSP` v1 envelope contains all three keys and a
reserved-zero field; schema is `0x54570101`. Root identity/ownership is immutable at revision 1.

`tw_load` needs only the root key to resolve current document metadata and stream head. It
validates root shape/version, child types and existence. A missing or incompatible child is
a recovery error; it never creates a blank draft to hide lost ownership. `tw_create` with an
existing root and the same child keys returns that current state, ignoring its initial seed.
Thus retrying creation after a lost receipt cannot reset subsequent edits. Different child
keys under an existing root conflict. Deleted keys remain reserved in the store.

The root is independent of Java views, module statics and provider sessions. Its draft and
stream retain separate revision domains, so an edit does not rewrite a second workspace or
the root. Current text is loaded through `td_load`; stream history through `tl_page`; a task
through `tt_load`. The stream and task APIs preserve the original request while later typing
advances the workspace's current draft.

Store-owner serialization governs these reads/writes. The root is a durable ownership
contract, not a new app shell or an Android UI. Workspace discovery/preferences, saved
navigation/source sets, kept artifact catalog, text/range indexes, native presentation and
live schema migration remain construction work. Existing bootstrap/loader behaviour remains.

`tests/core_workspace.sh` / `core.workspace` verify atomic three-record commits, a literal
wire fixture, independent workspaces, restart and idempotent creation after edits, key
collisions, missing-child rejection and full-store rollback. Its combined SIGKILL path
creates a workspace, accepts an Ask, edits the draft, kills the process, then reconstructs
both the latest composing draft and the original captured Ask using only the root key.
These are durable ownership semantics, not actual IME, renderer or product completion evidence.
