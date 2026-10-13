## Build System

## Design Goals

- Make local builds and tests reproducible with the repository's supported Maven entry points.
- Keep CAT's supported build, test, serialization, and validation technologies consistent across maintained code and
  tests.
- Complete a population of isolated build snapshots efficiently without letting one snapshot's result alter another
  snapshot's execution.

## Guidance

- **Build Tool:** Maven with Maven Wrapper (`client/mvnw`)
- **Test Framework:** TestNG (not JUnit)
- **JSON Library:** Jackson 3.x with `JsonMapper`
- **Validation Library:** requirements.java 13.2+

### Running the Build

**MANDATORY:** From the repository root, use `client/mvnw -q -f client/pom.xml verify`
(not `mvn test`) to run the full build including compiler and linters against the current working tree:

```bash
client/mvnw -q -f client/pom.xml verify
```

Treat linter errors (Checkstyle, PMD) the same as compiler errors — both must be fixed before any commit.

**Do NOT skip linters:**
- ❌ `client/mvnw -f client/pom.xml test -Dcheckstyle.skip=true`
- ❌ `client/mvnw -f client/pom.xml verify -Dpmd.skip=true`
- ✅ `client/mvnw -q -f client/pom.xml verify`

When deciding whether verification must repeat an operation instead of reusing a cached result, lazy load
`./maven.md` for the cache policy.

**Fix ALL errors before rerunning the build.** If quiet output does not identify the needed next action, rerun the same
command without `-q` to collect the missing diagnostics. Then apply all fixes across all files without intermediate
recompilation. Run the build again only once all fixes have been applied.

### Verifying independent builds

When a workflow verifies two or more immutable snapshots, give each build isolated working, log, and temporary state
with a cleanup owner; set `TMPDIR`, `TMP`, and `TEMP` to that snapshot's own temporary child before starting the build
so native tools do not inherit `/tmp`. If the selected build's tests support a JVM temporary-directory override, also
set `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<snapshot-temporary-child>`; otherwise preserve their supported JVM contract.
Then schedule builds in parallel without fixing a batch size up front. Before concurrent admission, run one equivalent
build alone and measure the peak aggregate resident physical memory of its complete process tree, including Maven,
forked test JVMs, and native children. Until that terminal calibration exists, admit only that one build. Before
allocating each new build worker, atomically acquire a reservation from a host-wide shared ledger that includes every
active scheduler and nested instance. Admit the worker only when the current `MemAvailable`, less every held reservation
and the candidate reservation, remains greater than 8 GiB. Reserve the calibrated whole-tree peak plus 25% for each
build; do not substitute a parent Maven process's RSS for that peak. Release its reservation only after its terminal
result is recorded. Before the same allocation, measure current host CPU utilization; when it is 95% or higher, do not
allocate another worker until a fresh measurement is lower or an allocated worker reaches a terminal result. Also do
not exceed the available CPU count. Do not wait for an earlier snapshot's result merely because acceptance is
chronological; wait for every build's terminal result and accept the workflow only when every required snapshot passes.
Keep builds sequential only when one build consumes another's output or any of them reads or writes shared mutable
state.
