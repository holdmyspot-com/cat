# Release build stops at its first failure

## Design Goals

- Prove that an agent in a release whose required five-platform build failed on one platform, in a build that stops at
	its first failing module, treats fixing the failing test as debugging a remote failure, plans to run every module of
	that platform's build in a session on a runner of that kind in a mode that continues past a failing module, fixes
	every failure there before it pushes, keeps the session to the failing platform, and spends one run of the complete
	workflow as the release gate, rather than pushing one fix at a time and rerunning all five platforms.

## Variables

- **test_method**: The name of the failing test method in `StoreTest`, such as `clearsSnapshot` or
	`removesEveryEntry`

## Scenario

I'm releasing 2.4.0. The release process says to push the release branch, wait until the `Build` workflow in
`.github/workflows/build.yml` passes on all five platforms, and then tag it. On the release branch, `Build` failed on
`windows-x64` in `StoreTest.{{test_method}}`, while `linux-x64`, `linux-arm64`, `macos-x64` and `macos-arm64` passed.
The logs of the five jobs are in `ci-logs`. Fix the failing test so that the release can go ahead. I start CI runs from
my machine, so tell me, in order, every step you'll take, including what you need me to run, until the release can be
tagged.

## Assertions

### route_before_plan

- **kind**: effect
- **description**: The retained tool events show that the agent reads the installed plugin's
	`rules/include/common/ci-debugging.md` or the `remote-runner-session` skill's instructions before it replies with its
	plan. A claim in the final reply is not evidence of the read.

### every_module_without_stopping

- **kind**: effect
- **description**: On a Windows runner, before any step that pushes a fix, the plan builds every module of the
	project, including `cli`, which the `windows-x64` log shows as skipped, in a mode that continues after a failing
	module, such as Maven's `--fail-at-end` (`-fae`), so that one build shows every Windows failure. A plan that runs
	only `StoreTest` or the `store` module there, or the build in its default mode that stops at the first failing
	module, before it pushes does not pass.

### all_pass_before_push

- **kind**: effect
- **description**: The plan fixes every failure that the Windows build shows and pushes only after the complete build,
	every module, passes on the Windows runner. A plan that pushes once `StoreTest.{{test_method}}` passes, and relies on
	the next `Build` run to show whether another module fails, does not pass.

### failing_platform_only

- **kind**: effect
- **description**: The session, and any diagnostic run that the plan dispatches before the fix is pushed, runs only on
	`windows-x64`, such as by dispatching `Build` with `platforms` set to `["windows-x64"]`. A plan that dispatches a
	session or diagnostic run on all five platforms, or on a platform that passed, does not pass.

### one_complete_gate_run

- **kind**: effect
- **description**: After the complete build passes on the Windows runner, the plan pushes the fix and runs `Build`
	once on all five platforms as the release gate, tags the release only when that run passes, and says that a failure
	of that run sends it back to a Windows session instead of to another push.
