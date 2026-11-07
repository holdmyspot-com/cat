# Windows-only failure fixed in a session

## Design Goals

- Prove that an agent asked to fix a test that fails only on the Windows CI runner first checks whether the failure
	reproduces on the local machine, then plans to reproduce and fix it in an interactive session on a Windows runner
	before it pushes any fix attempt, and spends one hosted run on confirming the fix, rather than pushing guesses and
	rerunning CI.

## Variables

- **test_method**: The name of the failing test method in `IndexCacheTest`, such as `removesCacheDirectory` or
	`clearsAllEntries`

## Scenario

The `test` job for `windows-2025` in `.github/workflows/ci.yml` fails in `IndexCacheTest.{{test_method}}`, while the
`ubuntu-24.04` job passes. The logs of both jobs are in `ci-logs`. Fix the failing test. I start CI runs from my
machine, so tell me, in order, every step you'll take, including what you need me to run, until you can tell me that
it's fixed.

## Assertions

### route_before_plan

- **kind**: effect
- **description**: The retained tool events show that the agent reads the installed plugin's
	`rules/include/common/ci-debugging.md` or the `remote-runner-session` skill's instructions before it replies with its
	plan. A claim in the final reply is not evidence of the read.

### local_reproduction_first

- **kind**: effect
- **description**: The plan runs `IndexCacheTest` on the local machine before any step that pushes a commit or starts a
	CI run, and treats a passing local run as not reproducing the Windows failure. A plan that starts with a push or a
	CI run does not pass.

### session_before_fix_push

- **kind**: effect
- **description**: The plan has the agent open an interactive session on a Windows runner, by dispatching
	`.github/workflows/ci.yml` with the user's public key as `remote_session_public_key`, reproduce the failure of
	`IndexCacheTest.{{test_method}}` there, and try the fix there until the test passes, all before it pushes a commit
	that holds a fix attempt. A plan that pushes a fix and reads the next CI result to learn whether it works does not
	pass.

### one_confirming_run

- **kind**: effect
- **description**: After the test passes in the session, the plan pushes the fix and runs the complete CI workflow once
	to confirm it on both operating systems, and says that a failure of that run sends it back to the session instead of
	to another push.
