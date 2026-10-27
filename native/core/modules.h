#ifndef TROUGH_MODULES_H
#define TROUGH_MODULES_H
#include "resources.h"

/* A module domain owns two world cells, containing resource identities for code and state.
 * It exclusively owns retirement of these resources. Immutable shared subresources are
 * separate leases held by the owning resource's adapter until actual destruction.
 * Active module/job/change structs are linear owner objects: never copy/reinitialise them.
 * All entry points run on the same world/resource owner; workers receive leased descriptors.
 * The coordinator does not dlopen, migrate, execute destructors or issue external effects.
 */
enum { TR_CODE = 1, TR_STATE = 2 };
typedef struct {
    tr_world *world;
    tr_resources *resources;
    tr_id code_cell, state_cell, code_lease, state_lease;
    unsigned live;
} tr_module;
typedef struct {
    tr_module *module;
    tr_transaction transaction;
    tr_id code_lease, state_lease;
    unsigned live;
} tr_job;
typedef struct {
    tr_job source;
    tr_id code_lease, state_lease;
    unsigned staged;
} tr_change;

/* Existing cells must contain the two resources' canonical identities. Failure changes none
 * of the world/module ownership. Do not call init again on a live module. */
tr_status tr_module_init(tr_module *, tr_world *, tr_resources *, tr_id code_cell,
                         tr_id state_cell, tr_id code_resource, tr_id state_resource);
/* Close prevents new jobs, retires current resources and releases host leases; old jobs
 * remain pinned until finish. This is not an immediate dlclose or device wait. */
tr_status tr_module_close(tr_module *);
tr_status tr_job_begin(tr_module *, tr_job *, tr_read *, size_t, tr_write *, size_t);
/* Cancel ONLY revokes publication. Call finish AFTER work/fences actually finish. */
void tr_job_cancel(tr_job *);
tr_status tr_job_finish(tr_job *);

tr_status tr_change_begin(tr_module *, tr_change *, tr_read *, size_t, tr_write *, size_t);
/* Source inputs are leased before migration starts. Caller builds/validates candidate state
 * privately, then stages code+state. Same-state code replacement and same-code state publication are both allowed;
 * an unchanged code+state pair is rejected.
 * Caller retains ownership of unstaged/rejected candidate resources and must retire them.
 */
tr_status tr_change_stage(tr_change *, tr_id new_code, tr_id new_state);
tr_status tr_change_activate(tr_change *, tr_receipt *);
/* Releases candidate/source holds, does not silently destroy caller-owned candidates.
 * Abort only after migration has stopped using its source leases. */
tr_status tr_change_abort(tr_change *);
#endif
