# Background campaign implementation report

## Design coverage

| Design point | Result |
| --- | --- |
| §3.7, D142 — Background run | `start`, `run`, `revise` and `choose` accept a trailing `--background`. Admission and completion have no slice deadline; campaign limits still apply. A background command ends at a decision, stated pause, outcome, failure or cancellation, without returning `running`. Background choices continue the campaign, including automatic test revisions. |
| §3.7 — Driver lock | The driver retains the operating-system campaign lock through its final response write. Read-only status shows its process and command start; a competing run reports `busy`; conflicting mutations name `status` and `cancel`. |
| §3.7 — Cancellation | A token-addressed stop request stops new trials, lets in-flight work drain through cancellation grace, then persists a resumable cancellation. The caller waits at most grace plus 30 seconds and otherwise reports that the request stands. |
| §3.7 — Exit notice | The Codex adapter invokes the validated host executable with `queue --thread <own thread> --message <plain note>` after state, delivery and response persistence, lock release and cleanup. A bounded queue failure is stored and reported by status without replacing the outcome. The other adapter relies on its host's notification. |
| §3.7 — Sandbox exception; D133 | The Codex installer reports and manages an absolute-path launcher allow rule, including uninstall and recognition of older CAT rules. The launch boundary checks the protected host identity and policy, command paths and inherited inputs; a failed outside-sandbox probe refuses background mode with the reason and foreground fallback. Contract-author tools remain inside their sandbox. |
| §3.8 — Interrupts | Background execution does not consult the host-interrupt watcher. Its stop signal is the addressed cancellation mailbox. Foreground interruption behavior remains available. |
| §4.7 — Delivery | Measured candidate delivery checks the live target against the fork before journaling or writing, including candidates that change only supporting files. Refusal carries both hashes, asks for direction, and leaves delivery pending. Keeping the incumbent's test files remains allowed. |
| §6.1 — Pilots | Background start, revision and pilot retry finish all remaining pilot trials in the same command, retaining decided trials. |
| §8 — Skill | Both wrappers and the shared body choose the shell tool's own background mode, prohibit shell `&` and `nohup`, offer status/cancel and call status on the exit notice. Mode-selection wording names no harness and adds no turn-start polling. |
| G6 — Tests | Scripted requirement tests exercise admission past the foreground limit, interrupt immunity, driver reporting, competing mutations, bounded cancellation, complete pilots, background run/resume, queue arguments/failure and delivery-before-notice ordering. |

## Decisions and limits

- Grace applies only after an explicit background cancellation; it is not an elapsed-run limit.
- Foreground `choose` keeps its existing behavior. Background `choose` advances the chosen continuation until the next decision or outcome.
- Driver identity includes the process start and a per-command token, so an old stop request cannot stop a later driver or a reused process identifier.
- The final background response is stored under the campaign lock. Successful automatic cleanup retains a small response archive outside the removed campaign directory, so the notice's `status <campaign>` still works and can report queue failure.
- The existing write-ahead order, campaign allowance, evidence and foreground fallback are retained. A cancel racing with completion preserves the completed outcome.
- No campaign, nested harness session or model was started for validation. The requested live G6 idle-wake recordings were not made because the brief expressly prohibits those operations; new coverage uses CAT's real driver/core with scripted session ports and an injected queue launcher.

The OpenAI Docs skill was used to verify prefix matching. [Official OpenAI documentation on rules](https://learn.chatgpt.com/docs/agent-configuration/rules) defines `pattern` as an exact command prefix and shows matching commands with trailing arguments. Therefore `pattern=["<absolute prompt-optimizer path>"]` matches commands with arguments; no wildcard or additional argument pattern is needed.

## Files changed

- `background-run-report.md`
- `client/claude/skills/src/main/resources/skills/prompt-optimizer/SKILL.md`
- `client/codex/cli/src/main/java/com/holdmyspot/cat/codex/optimizer/CodexExitNotice.java`
- `client/codex/cli/src/main/java/com/holdmyspot/cat/codex/optimizer/CodexOptimizerBoundary.java`
- `client/codex/cli/src/main/java/com/holdmyspot/cat/codex/optimizer/CodexOptimizerCommandScope.java`
- `client/codex/cli/src/main/java/com/holdmyspot/cat/codex/optimizer/CodexPromptOptimizer.java`
- `client/codex/cli/src/test/java/com/holdmyspot/cat/codex/optimizer/test/CodexExitNoticeTest.java`
- `client/codex/cli/src/test/java/com/holdmyspot/cat/codex/optimizer/test/CodexOptimizerBoundaryTest.java`
- `client/codex/installer/src/main/java/com/holdmyspot/cat/codex/installer/InstallerRules.java`
- `client/codex/installer/src/test/java/com/holdmyspot/cat/codex/installer/test/IntegrationTest.java`
- `client/codex/skills/src/main/resources/skills/prompt-optimizer/SKILL.md`
- `client/common/cli/src/main/java/com/holdmyspot/cat/common/optimizer/cli/AbstractOptimizerCommandScope.java`
- `client/common/cli/src/main/java/com/holdmyspot/cat/common/optimizer/cli/Command.java`
- `client/common/cli/src/main/java/com/holdmyspot/cat/common/optimizer/cli/CommandResult.java`
- `client/common/cli/src/main/java/com/holdmyspot/cat/common/optimizer/cli/Commands.java`
- `client/common/cli/src/main/java/com/holdmyspot/cat/common/optimizer/cli/Core.java`
- `client/common/cli/src/main/java/com/holdmyspot/cat/common/optimizer/cli/DeliveryJson.java`
- `client/common/cli/src/main/java/com/holdmyspot/cat/common/optimizer/cli/OptimizerCore.java`
- `client/common/cli/src/main/java/com/holdmyspot/cat/common/optimizer/delivery/Delivery.java`
- `client/common/cli/src/main/java/com/holdmyspot/cat/common/optimizer/execution/BackgroundRun.java`
- `client/common/cli/src/main/java/com/holdmyspot/cat/common/optimizer/execution/Driver.java`
- `client/common/cli/src/main/java/com/holdmyspot/cat/common/optimizer/execution/GuardPilot.java`
- `client/common/cli/src/main/java/com/holdmyspot/cat/common/optimizer/execution/SliceReports.java`
- `client/common/cli/src/main/java/com/holdmyspot/cat/common/optimizer/execution/SliceSettings.java`
- `client/common/cli/src/main/java/com/holdmyspot/cat/common/optimizer/inventory/DeliveryConflict.java`
- `client/common/cli/src/test/java/com/holdmyspot/cat/common/optimizer/cli/test/CommandsTest.java`
- `client/common/cli/src/test/java/com/holdmyspot/cat/common/optimizer/cli/test/OptimizerCoreTest.java`
- `client/common/cli/src/test/java/com/holdmyspot/cat/common/optimizer/cli/test/TestOptimizerCommandScope.java`
- `client/common/cli/src/test/java/com/holdmyspot/cat/common/optimizer/delivery/test/DeliveryTest.java`
- `client/common/cli/src/test/java/com/holdmyspot/cat/common/optimizer/execution/test/DriverTest.java`
- `client/common/cli/src/test/java/com/holdmyspot/cat/common/optimizer/execution/test/GuardPilotTest.java`
- `client/common/distribution/src/test/java/com/holdmyspot/cat/distribution/bundle/test/InstallerActivationTest.java`
- `client/common/skills/src/main/resources/skills/prompt-optimizer/first-use.md`

## Verification

The required root-level offline `clean verify` finished **BUILD SUCCESS**, in **3 minutes 16 seconds**.
Surefire reported **5,624 tests: 5,618 passed, 0 failures, 0 errors, 6 existing platform skips**.
The changed background tests have no platform skip. Checkstyle, PMD, compilation, unit/integration tests and
packaged-distribution checks passed. `git diff --check` also passed.

```sh
mkdir -p .cat-tmp
T=$(mktemp -d -p "$PWD/.cat-tmp")
TMPDIR="$T" TMP="$T" TEMP="$T" MAVEN_USER_HOME=/home/gili/cat/home/.m2 \
  client/mvnw -o -f client/pom.xml \
  -Dmaven.repo.local=/home/gili/cat/home/.m2/repository \
  "-DargLine=-Djava.io.tmpdir=$T" clean verify
```

The generated `.cat-tmp` directory was removed after recording the results. No build or watcher remains running.

| Module | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `client/claude/cli` | 593 | 0 | 0 | 0 |
| `client/claude/installer` | 114 | 0 | 0 | 0 |
| `client/claude/layout` | 9 | 0 | 0 | 0 |
| `client/codex/cli` | 688 | 0 | 0 | 1 |
| `client/codex/installer` | 182 | 0 | 0 | 0 |
| `client/codex/layout` | 18 | 0 | 0 | 0 |
| `client/common/cli` | 3398 | 0 | 0 | 0 |
| `client/common/distribution` | 136 | 0 | 0 | 0 |
| `client/common/installer` | 480 | 0 | 0 | 0 |
| `client/common/lifecycle` | 6 | 0 | 0 | 5 |

### Every test invocation taking at least one second

Times are the final clean gate's Surefire testcase times, not suite totals. Parameter indices identify separate
invocations.

| Module | Test | Seconds |
| --- | --- | ---: |
| `client/claude/cli` | `com.holdmyspot.cat.claude.runner.test.ClaudeConnectionSettingsTest.refusesDenialsTooManyToSandbox` | 1.487 |
| `client/claude/cli` | `com.holdmyspot.cat.claude.runner.test.ClaudeSessionCommandLimitsTest.childDeniesRequestedRunRootsAsOnePath` | 1.452 |
| `client/codex/cli` | `com.holdmyspot.cat.codex.runner.test.CodexSessionCommandLimitsTest.childDeniesRequestedRunRootsAsOnePath` | 1.093 |
| `client/common/cli` | `com.holdmyspot.cat.common.optimizer.cli.test.OptimizerCoreTest.backgroundRunsUntilDecision(boolean)[0]` | 13.681 |
| `client/common/cli` | `com.holdmyspot.cat.common.optimizer.cli.test.OptimizerCoreTest.backgroundRunsUntilDecision(boolean)[1]` | 11.854 |
| `client/common/cli` | `com.holdmyspot.cat.common.optimizer.cli.test.OptimizerCoreTest.proposalListsHookWhoseOutputDiffers` | 1.462 |
| `client/common/cli` | `com.holdmyspot.cat.common.optimizer.cli.test.OptimizerCoreTest.proposalListsTheFilesThatTrialsReceive` | 1.449 |
| `client/common/cli` | `com.holdmyspot.cat.common.optimizer.cli.test.OptimizerCoreTest.proposalNamesUnapprovedHooks` | 1.407 |
| `client/common/cli` | `com.holdmyspot.cat.common.runner.test.HarnessCapacityTest.holdBlocksAnotherJvmUntilExpiry` | 1.764 |
| `client/common/distribution` | `com.holdmyspot.cat.distribution.bundle.test.InstallerActivationTest.installUpdateStatusUninstall(java.lang.String)[0]` | 1.900 |
| `client/common/distribution` | `com.holdmyspot.cat.distribution.bundle.test.InstallerActivationTest.installUpdateStatusUninstall(java.lang.String)[1]` | 2.575 |
| `client/common/distribution` | `com.holdmyspot.cat.distribution.bundle.test.PluginLaunchersTest.wrapperTrainsAndPassesStartupCache` | 5.118 |
| `client/common/distribution` | `com.holdmyspot.cat.distribution.test.BundledRuleAudienceTest.codexAgentsGetReadFileRule` | 1.010 |
| `client/common/installer` | `com.holdmyspot.cat.common.installer.engine.test.ProcessElevationTest.reportsIdWhileProcessesStart` | 2.164 |

### Existing platform skips

These six pre-existing PowerShell/Windows cases were skipped on the Linux gate host; none belongs to the new
background tests.

| Test | Reason |
| --- | --- |
| `com.holdmyspot.cat.codex.test.CodexRunPartTest.powerShellSearchOutputIsLimited` | A POSIX shell runs the plugin's rg override, which Bats tests |
| `com.holdmyspot.cat.lifecycle.test.CatPs1Test.rejectsPluginWithWrongChecksum` | cat.ps1 installs CAT only on Windows |
| `com.holdmyspot.cat.lifecycle.test.CatPs1Test.refusesRetiredSwitches` | cat.ps1 installs CAT only on Windows |
| `com.holdmyspot.cat.lifecycle.test.CatPs1Test.passesInstallStatusThrough` | cat.ps1 installs CAT only on Windows |
| `com.holdmyspot.cat.lifecycle.test.CatPs1Test.detectsHarnessOnlyWhenUnambiguous` | cat.ps1 installs CAT only on Windows |
| `com.holdmyspot.cat.lifecycle.test.CatPs1Test.runsInstallFromExtractedPlugin` | cat.ps1 installs CAT only on Windows |
