---
paths:
  - "client/**"
---
# Plugin Artifacts

## Design Goals

- Keep published plugin artifacts complete and installable: the target harness accepts their packaged layout and every
  artifact path they reference resolves within the published artifact.
- Keep each plugin-facing skill description, hook response, launcher result, and command output valid for the
  documented metadata, schema, permitted values, and event semantics of its target harness.
- Keep repository development rules out of published plugin artifacts so an installed plugin contains only guidance that
  applies to its end-user environment.

## Guidance

When a file ships in a harness artifact, reference only paths that also ship in that artifact. Do not reference
repository-only `.cat/rules`, source files, tests, or documentation from installed plugin code, skills, hooks, or
launchers.

When CAT lifecycle hooks have one execution contract across harnesses, ship the hook once in the common plugin resource
layer. Each harness manifest must invoke that artifact-relative hook through its native plugin-root placeholder. The
hook derives CAT's installation directory from its own location, not a harness-specific environment variable. Keep
harness-specific commands, configuration, paths, environment variables, and argument adaptation at the manifest or
entry boundary; pass those values to the shared hook rather than making it name a harness or its layout.

Before publishing a changed plugin artifact, verify its documented installation procedure against the target harness.
Confirm that the harness accepts the packaged layout and that every path referenced by installed code, skills, hooks,
and launchers resolves to a packaged file. Do not claim that an artifact is "safe to install" without naming a specific
install-time property, its acceptance evidence, and the check that proves it; use a bounded property such as
installable layout or resolved artifact references instead.

Treat `.cat/rules/**` as repository development guidance. Distribution assembly must not copy, include, route, or
validate it as a published plugin artifact. Package only the shared plugin-rule sources and the applicable
`client/<harness>/rules/**` sources; a project rule may inform development of those artifacts, but it is not runtime
guidance for an installed plugin. Updating a project rule therefore does not require rebuilding the distribution or
reinstalling a plugin. Rebuild and reinstall only when a packaged plugin artifact changes.

Treat an installed plugin cache as generated output, never as a rule-source editing location. Change a packaged rule in
its `client/**/rules/**` source, run the project's complete documented build successfully, then install or refresh the
plugin through its documented lifecycle so that it populates the cache from the verified artifact. Do not copy, patch,
or validate a cache entry as evidence of a source change; a source file, partial build, or manually changed cache does
not establish that the installed plugin contains the change.

When developing CAT and a task requires a CAT helper CLI, do not treat its absence from `PATH` as evidence that it is
unavailable. Build the distribution, then run the launcher from
`client/common/distribution/target/jlink/<harness>/bin/<command>`. For an installed plugin, the selected harness wrapper
must resolve that harness's native plugin-root value and supply the launcher path. Before reporting a helper unavailable,
inspect the distribution's declared launchers and the harness-specific installed location. When a workflow consumes both
a shipped prompt and a helper launcher, record one artifact revision that supplies both. After changing either source,
rebuild or reinstall before invoking the workflow. Do not treat a cached prompt, a similarly named installed plugin, or
a successful launcher invocation as evidence that both contracts are current.

When a changed shipped command, hook, prompt, or configuration must be used immediately, run its selected build plan's
declared command rather than a similar partial build. Then use the generated artifact at its actual launcher or harness
boundary to observe the changed surface before relying on it. A current source file, compiled class, successful build
exit, or existing generated directory can still leave the launcher using an older copied or packaged artifact. Record
the artifact path and the observed changed result with the workflow evidence. This applies only when work will use the
generated artifact; editing or reviewing source alone does not require it.

When changing a plugin-facing skill description or a hook, launcher, or command's harness-visible result, identify the
target harness and its applicable documented contract. Validate the description's required metadata and routing form;
validate a response or result's required schema, permitted values, and event-specific meaning. Do not reuse a contract
from another harness or infer one from a successful local invocation.

For a harness-specific hook, launcher, or command, use only exit codes whose meaning is assigned by that harness's
reference documentation. Do not invent a conventional status such as `1` or reuse a status documented for a different
event. When the reference defines a structured response for the required outcome, return that response instead. If the
reference defines no applicable status or response, report the limitation rather than assigning an exit-code meaning.
