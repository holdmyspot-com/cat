# Maven

## Design Goals

- Keep Maven configuration centralized, reproducible, and consistent with project conventions.
- Run complete Maven verification with quiet routine output, actionable failure diagnostics, and an explicit
  evidence-backed reason for any normal-output exception.
- Verify the current working tree rather than a cached artifact whose recorded inputs do not prove it includes the
  change under review.

## Guidance

- Declare dependency versions only in the root POM's `<dependencyManagement>` section.
- Declare plugin versions and plugin dependency versions only in the root POM's `<pluginManagement>` section.

Use Maven quiet mode (`-q`) for routine builds, tests, packages, and verification. Add it while constructing the
command, before running Maven. It preserves failures and exit status while omitting routine reactor, cache, transfer,
and plugin messages. Do not skip modules, tests, linters, or other quality gates merely to reduce output. If a quiet
command fails without enough information to choose the next action, rerun the same proven scope without `-q` only to
obtain the missing diagnostic detail. Use normal output when the user requests it or when a named successful Maven
output is itself required evidence; state that output and why the command exit status or a produced artifact cannot
prove it before invoking Maven. A build artifact, installer result, later tool result, or general confirmation that a
build succeeded is not such an exception.

Use a build cache for a quality gate only when its recorded inputs are verified to include every source, resource,
configuration, generated input, tool, and dependency that can affect the result. When validating uncommitted or newly
created working-tree inputs, add `-Dmaven.build.cache.enabled=false`: a cache hit does not prove a dependent module
compiled the current files. This disables only result reuse; it does not skip a module, test, linter, or other gate.
