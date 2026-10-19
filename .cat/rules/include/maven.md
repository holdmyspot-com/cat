# Maven

## Design Goals

- Keep Maven configuration centralized, reproducible, and consistent with project conventions.
- Run complete Maven verification with quiet routine output, actionable failure diagnostics, and an explicit
	evidence-backed reason for any normal-output exception.
- Verify the current working tree while reusing results for unchanged inputs; repeat completed operations only when
	the required evidence calls for fresh execution or a known cache limitation invalidates reuse.

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

Keep the Maven build cache extension enabled for routine builds, focused checks, and complete quality gates. It is
enabled by default; omit the enabling property. Uncommitted changes or newly created files alone are not a reason to
disable caching. Reuse previously passed operations when their determining inputs are unchanged.

Disable result reuse only for a concrete reason: the required evidence needs an operation to run again despite an
earlier pass, or an observed cache limitation omits a determining input or supplies a stale result. Examples include
repeating a test to investigate nondeterminism or validating a changed external dependency that the cache does not
track. State the reason before invoking Maven, then use `-Dmaven.build.cache.enabled=false` for that invocation only.
Keep the complete required scope and every quality gate enabled; bypassing result reuse does not authorize skipping
modules, tests, or linters. Resume the enabled-cache default after the operation that needed fresh execution.
