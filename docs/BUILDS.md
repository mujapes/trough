# Native build reproduction

`build.native` extends the signed scenario registry with an independently checked build of
the running bundle's immutable `src/native` sources. It implements the construction loop's
build/verification boundary; it does not install a candidate or claim state migration.
The legacy diagnostic `SelfTest.nativeRepro` delegates to this same executor.

The bundle owns sources and the expected output inventory. The scenario owns a unique private
scratch directory. `ScenarioProcess` runs both the native build (180-second deadline) and
interpreter fixing (30 seconds) through `trough-run`; nonzero exit, timeout, truncation or
incomplete descendant cleanup stops verification. A successful build cannot conceal a failed
fixer by leaving already-correct files. Interruption reaps descendants before scratch removal.

The output inventory is bounded to 128 distinct direct paths under `bin`, `lib` and `fixtures`.
Both builds must report exactly the same inventory. Outputs must be regular files, not final
symlinks; missing files and excessive sizes fail. Comparison streams bytes using fixed-size
buffers, with a 32 MiB per-file and 256 MiB combined read budget. SHA-256 identities and sizes
are retained alongside exact byte-equality results. The scenario receipt binds evidence to
the accepted request, signed bundle and runtime, using the existing authentication/journal.
Success logs are omitted to bound combined evidence; failing process diagnostics are retained.

Run `python3 tools/scenario.py build.native --out out/evidence/build.native.json`. Its default
controller wait is 240 seconds. All requests still fit the protocol's five-minute validity
limit. `tools/loop.sh` now includes this scenario. No credentials, network/model calls or
arbitrary shell strings are accepted by the control request.

`tests/scenarios.sh` includes independent reproduction cases: exact multi-buffer comparison,
same-size corruption, truncation, missing/symlink/oversized files, malformed or substituted
inventories, failed build/fix despite identical files, and cancellation of a real child process.
The Pixel passed all 22 output comparisons on bundle
`53c97e3ffb698f7ac79bae2a4f828f206a0d4a8dd6cd0c36373c684c55230f8a`, with build 8515 ms,
fixing 13 ms and full receipt 8777 ms. This is one correctness run, not a performance claim.

Remaining obligations include complete toolchain/build-input identities, durable artifact
publication and build-task integration with the semantic effect owner, admission against
interactive workloads, and seamless candidate activation. This reproduction gate operates
within the existing trusted development bundle; it is not an untrusted-build sandbox.
