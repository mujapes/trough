# The autonomous construction and evolution loop

User direction, 2026-10-27. Part of [DESIGN.md](../DESIGN.md), alongside
[hot replacement](HOT_RELOAD.md) and the [construction dependencies](CONSTRUCTION.md).

## One loop, changing its owner

During construction the existing VM environment remains the user's working home. Trough and
the existing app stay open in Android split view, allowing the builder to exercise the real
application while the user continues working. The user does not operate tests. Build the
control and observation capabilities needed to make this practical, rather than assuming
foreground visibility already supplies automation or complete diagnostic access.

The finished application owns this same loop: source, tools, scenario execution, reference
oracles, evidence, candidate builds, state migration, activation and recovery. The VM initially
hosts orchestration because it is available, not because localhost services, remote browser
state or shared-folder polling are the product's internal architecture.

```text
intent + architectural invariants + independent references
                         |
                  scenario specification
                         |
                candidate implementation
                         |
              build + semantic verification
                         |
          real-device execution + observation
                         |
          compare correctness + continuity + cost
                   /                 \
           counterexample          accepted evidence
                  |                     |
          refine design/code     activate + expand coverage
                   \                 /
                 retained source/evidence history
```

No step normally requires user taps, pasted diagnostics, screenshots or a feature-by-feature
approval cycle. Ask for genuinely missing goals/authority or an irreducible platform action,
not routine implementation decisions. Do not conceal a real blocker or fabricate device proof
just to avoid asking. Existing permission boundaries still apply.

## The control contract is part of the product

Actions use the same typed registry and capability validation as ordinary interaction and
agent automation. Each scenario has an identity, seed, deadline, initial state, allowed
effects, target module/build and expected observations. Action targets carry relevant document,
scene and resource generations. Cancellation and timeouts revoke stale actions.

A development transport may request a scenario or retrieve a report. It cannot turn arbitrary
web content into a privileged command source. During the VM phase, authenticated/signed control
and app-private validation follow the existing bundle trust boundary; shared storage alone is
not authentication. Requests and results need IDs, deduplication, terminal statuses and recovery
from interrupted delivery. The exact controller is still to be implemented; current bundle
publication and boot reports are useful mechanisms, not a complete remote interaction harness.

After self-hosting, the same scenario executor and observation contracts operate locally.
Transport removal must not alter scenario meaning or leave the only oracle in the VM.

## The observation contract is equally important

A test must observe what its claim requires:

- semantic state and revision receipts for consistency, persistence and action outcomes;
- actual input/IME/focus behaviour for interaction;
- composed output and presentation timing for visual continuity, including media surfaces;
- audio continuity and clock behaviour for playback;
- process, resource and fence lifetimes for replacement/recovery;
- real device timing, memory, thermal conditions and power readings for optimisation.

Internal correctness alone cannot establish that the right content reached the screen.
A screenshot alone cannot establish durability or absence of duplicated effects. Correlate
observations using scenario/build/revision IDs without recording private content unnecessarily.
Split-view viewport changes, resize, focus transitions and later standalone operation are all
explicit scenarios. Record VM contention and display mode with performance results so a
construction arrangement is not mistaken for a standalone production baseline.

## Plan, implementation and evidence evolve together

For every end-state contract maintain:

| Item | Required record |
| --- | --- |
| Meaning | invariant and externally observable behaviour |
| Ownership | authoritative state, lifetime and trust boundary |
| Implementation | module/schema/operator and relevant build identity |
| Reference | independent semantics, standard, golden or differential implementation |
| Cases | ordinary, concurrent, adversarial, failure and migration scenarios |
| Evidence | result, environment, seed, counters/traces and reproduction instructions |
| Remaining work | uncovered semantics, unsupported edit classes and unverified assumptions |

Start from the full intended contract, not from the easiest demonstration. Implement its real
component; reproduce failures; refine the component and affected design; expand cases and
composition coverage. Component tests are internal instruments, not temporary products.
The completion criterion remains the integrated end-state experience.

The reference must remain independent of the candidate. Do not "fix" a failing test by
silently weakening its assertion, accepting its own output as the new golden or replacing the
expected standard with current behaviour. Legitimate specification changes retain the reason,
previous expectation and new contract. Keep reference cases and held-out scenarios versioned
separately from candidate code, with evaluator provenance in each result.

Expanding scope means filling out authorised product behaviour and widening verification,
including interactions between features. Novel user goals, new consequential capabilities or
external commitments are not automatically authorised by an improvement loop. Source-content
instructions and model-produced plans are inputs to validate, not new authority.

## Failure is useful evidence, not an invitation to patch around the architecture

On failure, retain a bounded reproduction with exact build/input identity, compare the violated
contract, and fix its owning layer. If a contract itself is wrong, revise all affected consumers
and their proofs together. Avoid local bypasses, duplicate stores, secondary lifecycle owners
and test-only implementations that the real product never uses.

Candidate builds and scenarios have resource/time budgets, terminate cleanly and release their
resources. Repeated failure should produce a smaller counterexample and better diagnosis, not
an endless hot loop of rebuilds. Unrelated proven work need not be retested without cause;
invalidation determines required checks, while integration and held-out suites guard against
incorrect dependency declarations.

## Seamless evolution is a system capability

Continuity cases run while the application is actually doing work: typing/composition,
scrolling, playback, inference, tool execution, storage and subsequent builds. Test replacement
of the renderer, schemas, agent policies, resource managers and the replacement coordinator,
not only harmless text/theme changes. Include low-memory and thermal contention during overlap.

Define success in preserved meaning and bounded user-visible disruption, not simply "new code
loaded". Measure input acknowledgement, audio underruns, scene freshness, data preservation,
effect outcomes and retirement of old resources. Keep an explicit matrix of edit classes,
working handoff strategies, coverage and demonstrated platform constraints. The intended
surface of seamless change grows; unsupported ordinary edits remain work to solve.

Future self-improving behaviour can use this infrastructure. The initial requirement is a
reliable, reproducible and inspectable construction loop, with no claim that passing its own
chosen tests proves unrestricted self-improvement.

## Scope and ownership at completion

Trough includes the browser/document engine, native-speed filesystem/workspace facilities,
UI/media/input, model and tool execution, hot replacement and this loop under one architecture.
A filesystem port may supply implementation machinery; the app still owns coherent identity,
paths/capabilities, atomic writes, recovery, observation and source/build access. This does not
require inventing a new kernel filesystem or changing the phone's hardware.

The final independence check runs after removing VM control from the scenario: edit source,
build, validate, activate, observe and recover entirely within Trough. The VM is retained for
the user's work until the app is substantially complete, not removed early to make a migration
milestone look finished. Post-completion features enter the same owned loop and contracts.
