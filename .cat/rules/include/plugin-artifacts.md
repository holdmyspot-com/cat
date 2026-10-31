# Plugin Artifacts

## Design Goals

- Keep published plugin artifacts installable: the target harness accepts their packaged layout and every artifact path
	they reference resolves within the published artifact.
- Make every installed plugin, helper launcher, and workflow that uses a shipped artifact run the verified current
	source, rebuilding or reinstalling only when a packaged artifact changed.

## Guidance

Before publishing a changed plugin artifact, verify its documented installation procedure against the target harness.
Confirm that the harness accepts the packaged layout and that every path referenced by installed code, skills, hooks,
and launchers resolves to a packaged file. Do not claim that an artifact is "safe to install" without naming a specific
install-time property, its acceptance evidence, and the check that proves it; use a bounded property such as
installable layout or resolved artifact references instead.

A project rule under `.cat/rules/**` may inform development of plugin artifacts, but it is not runtime guidance for an
installed plugin. Updating a project rule therefore does not require rebuilding the distribution or reinstalling a
plugin. Rebuild and reinstall only when a packaged plugin artifact changes.

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
