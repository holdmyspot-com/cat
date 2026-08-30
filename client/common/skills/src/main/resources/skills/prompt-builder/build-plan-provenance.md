# Build-Plan Provenance

## Design Goals

- Reuse a prior result only after confirming that every input that determines it is unchanged or has been revalidated.
- Keep build outputs out of unchanged-input comparisons, so a build's own work neither duplicates source-change
  detection nor invalidates a receipt for an unrelated output difference.
- Keep an execution comparison stable when equivalent builds produce different generated optimization or packaging
  bytes, while still verifying that the selected build produced a usable runtime artifact.

## Guidance

When creating or changing a CAT evaluation build plan, first identify the project-controlled artifacts the build reads
to determine its runtime artifact: source, resources, build configuration, wrappers, and generated inputs that the
build consumes. Declare every such artifact in `inputs`. Then identify every artifact the command writes, including
generated directories, reports, and `runner_artifact`; do not declare any of them in `inputs`.

An input fingerprint establishes that the build began and ended with the same determining artifacts. It cannot do that
when it includes its own output: unchanged source and configuration already preserve that evidence, while a report or
generated file can differ for a reason unrelated to the source change being tested. When one directory contains both
inputs and outputs, declare the specific input subdirectories or files instead of their common parent.

Apply the same separation to every identity used to compare executions, not only a build plan's `inputs` list. First
fingerprint the project-controlled source, configuration, tool version, and declared inputs that determine what the
build can produce. Separately verify that the build produced the required runtime artifact and that the selected runner
can use it. Do not substitute a hash of generated output bytes for the determining-input fingerprint: two unchanged
inputs can produce different generated startup, layout, timestamp, or packaging bytes, which would falsely split one
comparison series even though the runner behavior under test has not changed. Include an output-derived value only when
the required behavior actually depends on that output variation and the declared inputs cannot establish it; state that
dependency and test equivalent inputs with differing output before making it an identity boundary.

When one build packages both the native runner and prompt files under evaluation, declare a second, narrower
`execution_inputs` set for the project-controlled inputs that can change the runner, its native configuration, or the
fixed execution environment. Keep the complete `inputs` set for deciding whether the package must be rebuilt; it may
include the tested prompt and other delivered material. Do not put the tested prompt or another separately fingerprinted
model-facing route into `execution_inputs`, because changing that prompt is the comparison being measured, not a changed
environment. Conversely, include any source, configuration, or fixed instruction whose change can alter runner or
validator behavior even if the build also has unrelated outputs.

When the runtime inputs still match but a model-facing input changed, declare a `prompt_command` that replaces the
installed prompt bundle: every packaged rule, skill, and agent instruction that the plan declares as a prompt input.
That command must stage, validate, and replace those files without rebuilding or modifying the native runtime. The
evaluator records both the complete-input and installed-prompt-bundle hashes, verifies the selected runner before and
after the replacement, and records the unchanged runtime inputs as the execution identity. If the plan has no
`prompt_command`, or the prior runtime receipt cannot be verified, it must run the complete build instead. Test one
prompt-only change that runs the bundle command and changes its installed hash without changing the runtime identity,
and one runner-input change that runs the complete build and starts a new comparison series.

Before accepting a plan, compare its declared `inputs` with its declared `runner_artifact`. The evaluator rejects an
input that is, or contains, that known output. For other directories the build contract says it writes, keep them out of
`inputs` when authoring the plan; command text alone cannot reliably reveal every path an arbitrary build tool writes.
Do not rely on a reviewer to notice an overlap that the plan can state explicitly.

When a source change must be exercised through a generated launcher or other packaged artifact, run the selected
plan's declared build command exactly before invoking that artifact. Do not replace it with a shorter command that
merely compiles or packages part of the project: it can leave an older JAR, image, installer, or copied resource at the
path the launcher uses. Then exercise the changed public artifact surface—for example, the changed command, option,
hook result, or installed file—and retain that observable result with the build receipt. A changed source file, a
compiled class, a successful build exit, or an artifact directory timestamp does not establish that the launched
artifact contains the change. This check is unnecessary when no generated artifact is about to be used; ordinary source
inspection does not need a package rebuild.
