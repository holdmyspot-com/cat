package com.holdmyspot.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ProjectRuleLoader;
import com.holdmyspot.cat.common.ShellQuoting;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.fakes.test.TestToolScope;
import com.holdmyspot.cat.distribution.bundle.RuleArtifactRouter;
import org.testng.annotations.Test;

/**
 * Tests which bundled rules reach each agent audience.
 */
public final class BundledRuleAudienceTest
{
	/** Creates a test instance. */
	public BundledRuleAudienceTest()
	{
	}

	/**
	 * Verifies a subagent receives CAT's rule routing and request-execution rule in both harnesses, so it can
	 * lazy load engineering rules such as design quality, TDD and testing and decide which gaps to escalate, while the
	 * rules about interacting with the user stay with the main agent. Neither agent receives the design-quality rule
	 * itself at startup, nor the decision-area indexes that route to rules such as build workflow and failure
	 * regression promotion; both receive the routes to those indexes.
	 * <p>
	 * Copying and routing the complete bundled rule trees, then loading all four audience/harness combinations, can take
	 * over one second. A smaller synthetic tree would miss regressions in shipped audience metadata and dependencies.
	 */
	@Test
	public void subagentGetsRoutingNotUserRules() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-bundled-rules-project-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml());

			for (String harness : List.of("claude", "codex"))
			{
				String subagent = loader.loadForSubagent(plugin, project, harness, "cat:reviewer");
				requireThat(subagent, harness + "Subagent").contains("# CAT Rule Routing").
					contains("include/common/design-quality.md`").doesNotContain("# Design Quality").
					contains("include/common/rule-routing/testing-and-builds.md`").
					doesNotContain("# Testing and Build Rule Routing").
					contains("include/common/rule-routing/failures.md`").
					doesNotContain("# Failure and Workaround Rule Routing").
					contains("lazy load `").contains("# Request Execution").
					doesNotContain("# User Request Scheduling").doesNotContain("# File Links");

				String main = loader.loadForMainAgent(plugin, project, harness);
				requireThat(main, harness + "Main").contains("# CAT Rule Routing").
					contains("# Request Execution").contains("# User Request Scheduling").contains("# File Links").
					contains("include/common/rule-routing/testing-and-builds.md`").
					doesNotContain("# Testing and Build Rule Routing").doesNotContain("# Build Workflow Development").
					doesNotContain("# Remote Runner Testing").doesNotContain("# Design Quality");
			}
			requireThat(Files.readString(plugin.resolve("rules/include/common/rule-routing/failures.md")),
				"failureIndex").contains("A subagent does not load this rule");
		}
	}

	/**
	 * Verifies a subagent of either harness receives the request-execution and goal guidance that governs how work is
	 * done, checked and reported, but not the guidance on talking with the user: reusing earlier answers, answering
	 * about content shown in the conversation and continuing an active goal. The main agent receives all of it.
	 *
	 * @throws IOException        if the bundled rules cannot be copied, routed, or loaded
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void subagentGetsWorkGuidanceNotUserGuidance() throws IOException, URISyntaxException
	{
		List<String> workGuidance = List.of(
			"Resolve implementation questions yourself using the best long-term approach",
			"Before escalating a choice during authorized implementation",
			"When translating a request into a tool or workflow invocation, supply an optional limit",
			"When a request states or implies a factual claim that the requested action depends on, check the claim " +
				"before acting on it.",
			"For every workflow, name the terminal goal and the acceptance evidence that proves it.",
			"Record every required terminal gate and its required evidence before execution.",
			"Match evidence to the claim each terminal gate makes.",
			"When new work arises, such as a user instruction",
			"Do not mark a goal blocked because the current executor reaches a turn, token, context, polling, tool-wait, " +
				"or estimated duration limit.",
			"Before deciding whether a goal is blocked, reporting that work is blocked, or classifying a missing input as " +
				"external",
			"Before an irreversible action that represents completion");
		List<String> userGuidance = List.of(
			"Before re-answering a question, repeating an investigation, or redoing an action whose subject appeared " +
				"earlier in the conversation",
			"A context reset, compaction, handoff, or missing local recollection does not show that the earlier result " +
				"is absent or invalid.",
			"When the requester asks about content already in the conversation, such as a report, result, diff, or " +
				"sequence of events, answer from that content first",
			"With an active goal, do not stop because of turn length, context, task size, or a dirty worktree.");
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-bundled-rules-project-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml());

			for (String harness : List.of("claude", "codex"))
			{
				String subagent = oneLine(loader.loadForSubagent(plugin, project, harness, "cat:reviewer"));
				String main = oneLine(loader.loadForMainAgent(plugin, project, harness));
				for (String guidance : workGuidance)
				{
					requireThat(subagent, harness + "Subagent").contains(guidance);
					requireThat(main, harness + "Main").contains(guidance);
				}
				for (String guidance : userGuidance)
				{
					requireThat(subagent, harness + "Subagent").doesNotContain(guidance);
					requireThat(main, harness + "Main").contains(guidance);
				}
			}
		}
	}

	/**
	 * Verifies the guidance on which questions to ask the user reaches the main agent of either harness and a Codex
	 * subagent exactly once each, but not a Claude subagent. A Codex agent receives it before its rules on requesting
	 * user input, which apply it.
	 *
	 * @throws IOException        if the bundled rules cannot be copied, routed, or loaded
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void questionGuidanceReachesAskingAgents() throws IOException, URISyntaxException
	{
		String questionGuidance = "and apply its product-policy and material architecture/design classification; that " +
			"rule decides which decisions the user must settle.";
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-bundled-rules-project-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml());

			String codexSubagent = oneLine(loader.loadForSubagent(plugin, project, "codex", "cat:reviewer"));
			String codexMain = oneLine(loader.loadForMainAgent(plugin, project, "codex"));
			for (String codex : List.of(codexSubagent, codexMain))
			{
				requireThat(occurrences(codex, questionGuidance), "codexQuestionGuidance").isEqualTo(1);
				requireThat(codex.indexOf(questionGuidance), "codexQuestionGuidanceIndex").
					isLessThan(codex.indexOf("# Requesting User Input"));
			}
			String claudeMain = oneLine(loader.loadForMainAgent(plugin, project, "claude"));
			requireThat(occurrences(claudeMain, questionGuidance), "claudeMainQuestionGuidance").isEqualTo(1);
			String claudeSubagent = oneLine(loader.loadForSubagent(plugin, project, "claude", "cat:reviewer"));
			requireThat(claudeSubagent, "claudeSubagent").doesNotContain(questionGuidance);
		}
	}

	/**
	 * Verifies Codex agents receive the read-file rule at startup, stating the installed plugin's directory, which
	 * holds the {@code run-part} and {@code read-part} commands it names, while Claude agents, which do not ship those
	 * commands, do not receive the rule. On Windows, the rule names the batch files that PowerShell runs.
	 *
	 * @throws IOException if the bundled rules cannot be copied, routed, or loaded
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void codexAgentsGetReadFileRule() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-bundled-rules-project-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			String pluginRoot = "the CAT plugin's root directory, `" + plugin.toAbsolutePath().normalize() + "`.";

			for (Platform platform : List.of(Platform.LINUX_X64, Platform.WINDOWS_X64))
			{
				ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml(), platform);
				String runPart;
				Path bin = plugin.toAbsolutePath().normalize().resolve("bin");
				if (platform.isWindows())
					runPart = "& " + ShellQuoting.powerShell(bin.resolve("run-part.cmd").toString());
				else
					runPart = ShellQuoting.posix(bin.resolve("run-part").toString()) + " <<'EOF'";
				for (String codex : List.of(loader.loadSessionForMainAgent(plugin, null, project, "codex").
					readingGuidance(), loader.loadForSubagent(plugin, project, "codex", "cat:reviewer")))
				{
					requireThat(codex, platform + "Codex").contains("# Reading Command Output").contains(pluginRoot).
						contains(runPart).doesNotContain(ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE);
				}
				requireThat(loader.loadForMainAgent(plugin, project, "claude"), platform + "Claude").
					doesNotContain("# Reading Command Output");
			}
		}
	}

	/**
	 * Verifies Codex agents receive the rule on commands that are still running at startup, both in a session's rules
	 * and in a subagent's, on POSIX and Windows, while Claude agents, which the rule's Codex tool results do not
	 * describe, do not receive it.
	 *
	 * @throws IOException if the bundled rules cannot be copied, routed, or loaded
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void codexAgentsGetRunningCommandRule() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-bundled-rules-project-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());

			for (Platform platform : List.of(Platform.LINUX_X64, Platform.WINDOWS_X64))
			{
				ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml(), platform);
				for (String codex : List.of(loader.loadSessionForMainAgent(plugin, null, project, "codex").rules(),
					loader.loadForSubagent(plugin, project, "codex", "cat:reviewer")))
				{
					requireThat(codex, platform + "Codex").contains("# Commands Still Running");
				}
				requireThat(loader.loadForMainAgent(plugin, project, "claude"), platform + "Claude").
					doesNotContain("# Commands Still Running");
			}
		}
	}

	/**
	 * Verifies a session receives the bundled Java and Bash routes only through instructions to load their routing
	 * files when it reads or edits a matching file, so a session that never touches such a file does not carry them,
	 * while the route for choosing a Java API, which arises while designing before any Java file exists, stays
	 * reachable from the always-loaded routing through the interface-design area index. The main agent of either harness
	 * receives that routing.
	 *
	 * @throws IOException if the bundled rules cannot be copied, routed, or loaded
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void languageRoutesLoadWithMatchingFiles() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-bundled-rules-project-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			Path include = plugin.toAbsolutePath().normalize().resolve("rules/include/common");
			Path interfaces = include.resolve("rule-routing/interfaces.md");

			ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml());
			String context = loader.loadForMainAgent(plugin, project, "claude");
			String codexContext = loader.loadForMainAgent(plugin, project, "codex");

			requireThat(codexContext, "codexContext").contains("# CAT Rule Routing").
				contains("when selecting a platform or available project API").
				contains("include/common/rule-routing/interfaces.md`");
			requireThat(context, "context").contains("# CAT Rule Routing").
				contains("Load `" + include.resolve("java.md") + "` only when you read or edit a file").
				contains("Load `" + include.resolve("bash.md") + "` only when you read or edit a file").
				contains("when selecting a platform or available project API").
				contains("include/common/rule-routing/interfaces.md`").
				doesNotContain("When selecting a Java platform or available project API, lazy load").
				doesNotContain("When handling Java exceptions").doesNotContain("Before writing or editing Javadoc").
				doesNotContain("PipedInputStream").doesNotContain("When writing or reviewing Java control flow").
				doesNotContain("When defining Java POSIX permission values").
				doesNotContain("When documenting Bash functions");
			requireThat(Files.readString(interfaces), "interfaceIndex").
				contains("When selecting a Java platform or available project API, lazy load " +
					"`${CAT_PLUGIN_ROOT}/rules/include/java/standard-apis.md`.");
			requireThat(plugin.resolve("rules/include/java/standard-apis.md"), "standardApis").isRegularFile();
		}
	}

	/**
	 * Verifies the bundled delegation rule routes an agent choosing which implementer agent runs a delegated task to the
	 * shipped companion that maps tasks onto CAT's implementer tiers, that every tier the companion names ships in both
	 * harnesses' plugins, and that the companion names neither harness's models, so its text stays harness-neutral.
	 *
	 * @throws IOException if the bundled rules or agent definitions cannot be read, copied or routed
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void delegationRoutesToImplementerAgents() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			Path companion = plugin.resolve("rules/include/common/delegation/implementer-agents.md");

			String delegation = Files.readString(plugin.resolve("rules/include/common/delegation.md")).
				replaceAll("\\s+", " ");
			requireThat(delegation, "delegation").
				contains("When choosing which of CAT's implementer agents runs a delegated task, lazy load " +
					"`${CAT_PLUGIN_ROOT}/rules/include/common/delegation/implementer-agents.md`.");
			requireThat(companion, "companion").isRegularFile();
			String guidance = Files.readString(companion);
			requireThat(guidance, "guidance").startsWith("# Delegation: Implementer Agents\n");

			ClassLoader loader = BundledRuleAudienceTest.class.getClassLoader();
			Pattern model = Pattern.compile("(?m)^model\\s*[:=]\\s*\"?([^\"\\s]+)\"?\\s*$");
			for (String tier : List.of("low", "medium", "high", "xhigh"))
			{
				requireThat(guidance, "guidance").contains("`optimize-" + tier + "`");
				for (String definition : List.of("agents/claude/optimize-" + tier + ".md",
					"agents/codex/optimize-" + tier + ".toml"))
				{
					try (InputStream in = loader.getResourceAsStream(definition))
					{
						requireThat(in, definition).isNotNull();
						Matcher matcher = model.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
						requireThat(matcher.find(), definition + "HasModel").isTrue();
						requireThat(guidance, "guidance").doesNotContain(matcher.group(1));
					}
				}
			}
		}
	}

	/**
	 * Verifies an agent that is about to rerun a failed operation that is expensive to run in full, or to count a
	 * conclusion that compares separate runs, can reach the packaged rule for that decision: the failure area index
	 * routes to the diagnostic-run rule, and the rule routing that every agent of either harness receives at startup
	 * routes to the run-comparison rule, so a comparison that is neither test nor build evidence reaches it too. The
	 * testing and build area index does not repeat that route.
	 *
	 * @throws IOException        if the bundled rules cannot be copied, routed, or read
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void expensiveRunRulesAreRouted() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-bundled-rules-project-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			Path include = plugin.resolve("rules/include/common");

			requireThat(Files.readString(include.resolve("rule-routing/failures.md")), "failureIndex").
				contains("`${CAT_PLUGIN_ROOT}/rules/include/common/diagnostic-runs.md`");
			requireThat(Files.readString(include.resolve("diagnostic-runs.md")), "diagnosticRuns").
				startsWith("# Diagnostic Runs\n");
			requireThat(Files.readString(include.resolve("run-comparisons.md")), "runComparisons").
				startsWith("# Run Comparisons\n");
			requireThat(Files.readString(include.resolve("rule-routing/testing-and-builds.md")), "testingIndex").
				doesNotContain("run-comparisons.md");
			ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml());
			for (String harness : List.of("claude", "codex"))
			{
				requireThat(loader.loadForMainAgent(plugin, project, harness), harness + "Main").
					contains("include/common/run-comparisons.md`");
				requireThat(loader.loadForSubagent(plugin, project, harness, "cat:reviewer"), harness + "Subagent").
					contains("include/common/run-comparisons.md`");
			}
		}
	}

	/**
	 * Verifies an agent that is about to dispatch an expensive or shared-capacity verification run, or to act on a
	 * failure that a run of changed code shows, can reach the packaged rules for those decisions: the testing and build
	 * area index routes to the expensive-verification-runs rule, the failure area index routes to the failure-baseline
	 * rule, and the expensive-verification-runs rule itself states how to order a run that spans targets of differing
	 * speed or capacity, without the build-workflow rule repeating that ordering.
	 *
	 * @throws IOException        if the bundled rules cannot be copied, routed, or read
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void verificationRunRulesAreRouted() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			Path include = plugin.resolve("rules/include/common");

			requireThat(Files.readString(include.resolve("rule-routing/testing-and-builds.md")), "testingIndex").
				contains("`${CAT_PLUGIN_ROOT}/rules/include/common/expensive-verification-runs.md`");
			requireThat(Files.readString(include.resolve("rule-routing/failures.md")), "failureIndex").
				contains("`${CAT_PLUGIN_ROOT}/rules/include/common/run-comparisons/failure-baseline.md`");
			String expensiveRuns = oneLine(Files.readString(include.resolve("expensive-verification-runs.md")));
			requireThat(expensiveRuns, "expensiveRuns").startsWith("# Expensive Verification Runs ").
				contains("start that target first or in parallel").
				doesNotContain("build-workflow.md");
			String buildWorkflow = oneLine(Files.readString(include.resolve("build-workflow.md")));
			requireThat(buildWorkflow, "buildWorkflow").doesNotContain("start that target first or in parallel");
			requireThat(Files.exists(include.resolve("run-comparisons/failure-baseline.md")),
				"failureBaselineExists").isTrue();
		}
	}

	/**
	 * Verifies an agent that waits for a hosted workflow run with several jobs, or sees one of its jobs fail while
	 * others still run, can reach the packaged rules for those decisions: the workflow area index routes to the
	 * external-waits rule, whose guidance ends the wait on the first failed job and directs the agent to fix it at once;
	 * the testing and build area index routes the failed-job situation to the expensive-verification-runs rule, whose
	 * guidance decides whether the rest of the run continues, including how to check for an existing cache entry; and
	 * neither rule states the other's decision.
	 *
	 * @throws IOException        if the bundled rules cannot be copied, routed, or read
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void hostedRunMonitoringRulesAreRouted() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			Path include = plugin.resolve("rules/include/common");

			requireThat(oneLine(Files.readString(include.resolve("rule-routing/workflows.md"))), "workflowIndex").
				contains("lazy load `${CAT_PLUGIN_ROOT}/rules/include/common/external-waits.md`");
			requireThat(oneLine(Files.readString(include.resolve("rule-routing/testing-and-builds.md"))),
				"testingIndex").
				contains("has failed while other jobs of it are queued or running, lazy load " +
					"`${CAT_PLUGIN_ROOT}/rules/include/common/expensive-verification-runs.md`");

			String externalWaits = oneLine(Files.readString(include.resolve("external-waits.md")));
			requireThat(externalWaits, "externalWaits").
				contains("wait for the first failed part as well as for the whole operation").
				contains("per-part status interface").
				contains("start diagnosing and fixing it at once, while the other parts run").
				doesNotContain("cancel the run now");

			String expensiveRuns = oneLine(Files.readString(include.resolve("expensive-verification-runs.md")));
			requireThat(expensiveRuns, "expensiveRuns").
				contains("whether the rest of the run has value").
				contains("list the repository's cache entries for it").
				contains("cancel the run now through the hosting service").
				doesNotContain("per-part status interface");
		}
	}

	/**
	 * Verifies an agent that writes or changes a build or job step that downloads from one upstream server, or sees a
	 * job fail because such a server was unreachable, can reach the packaged rule for that decision: the testing and
	 * build area index and the CI debugging rule route to the upstream-fallbacks rule, whose guidance adds a fallback
	 * that is tried only when the primary fails, accepts only an equivalent source whose content the same checks
	 * verify, does not leave the agent only waiting for the provider to recover, and counts the fallback as verified
	 * only after a run in the step's real environment with the primary forced to fail.
	 *
	 * @throws IOException        if the bundled rules cannot be copied, routed, or read
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void upstreamFallbackRuleIsRouted() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			Path include = plugin.resolve("rules/include/common");

			requireThat(oneLine(Files.readString(include.resolve("rule-routing/testing-and-builds.md"))),
				"testingIndex").
				contains("After you read the log of a job that failed on another machine, and before you choose a fix, " +
					"lazy load `${CAT_PLUGIN_ROOT}/rules/include/common/upstream-fallbacks.md` when the log shows a " +
					"download from an upstream server that failed because the server could not be reached").
				contains("from one upstream server, lazy load " +
					"`${CAT_PLUGIN_ROOT}/rules/include/common/upstream-fallbacks.md`.");

			requireThat(oneLine(Files.readString(include.resolve("ci-debugging.md"))), "ciDebugging").
				contains("When that log shows a download from an upstream server that failed because the server could " +
					"not be reached, lazy load `${CAT_PLUGIN_ROOT}/rules/include/common/upstream-fallbacks.md` before " +
					"you choose a fix.");

			String fallbacks = oneLine(Files.readString(include.resolve("upstream-fallbacks.md")));
			requireThat(fallbacks, "fallbacks").startsWith("# Upstream Download Fallbacks ").
				contains("The fallback runs only when the primary fails").
				contains("Never disable or relax a check to make a fallback work").
				contains("instead of only waiting for the provider to recover").
				contains("only after it has run once in the real environment where the step runs");
		}
	}

	/**
	 * Verifies an agent that is about to apply a change meant to improve the performance of a program or build can
	 * reach the packaged rule for that decision: the rule routing that every agent of either harness receives at
	 * startup routes to the performance-change rule, whose guidance tells the reader to measure the change before and
	 * after on the same machine and input, to proceed on a meaningful improvement, to ask the user on a small one, and
	 * to leave the change unmade on a degradation.
	 *
	 * @throws IOException        if the bundled rules cannot be copied, routed, or read
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void performanceChangeRuleIsRouted() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-bundled-rules-project-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());

			String rule = Files.readString(plugin.resolve("rules/include/common/performance-changes.md")).
				replaceAll("\\s+", " ");
			requireThat(rule, "performanceChanges").startsWith("# Performance Changes ").
				contains("same machine, the same input").contains("Ask the user whether to proceed").
				contains("Do not make the change.");
			ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml());
			for (String harness : List.of("claude", "codex"))
			{
				requireThat(loader.loadForMainAgent(plugin, project, harness), harness + "Main").
					contains("include/common/performance-changes.md`").doesNotContain("# Performance Changes");
				requireThat(loader.loadForSubagent(plugin, project, harness, "cat:reviewer"), harness + "Subagent").
					contains("include/common/performance-changes.md`").doesNotContain("# Performance Changes");
			}
		}
	}

	/**
	 * Verifies packaged path routes render installed rule paths and the current project's matching boundary.
	 */
	@Test
	public void packagedPathRulesMatchOnlyInsideProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-bundled-rules-project-"));
			Path direct = Files.createDirectories(plugin.resolve("rules/common")).resolve("path.md");
			Files.writeString(direct, "---\npaths: [\"**/rules/**/*.md\"]\n---\nBundled rule body");

			RuleArtifactRouter.route(plugin, scope.yaml());
			String context = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "codex");

			requireThat(context, "context").contains("Load `" + plugin.resolve("rules/include/common/path.md") +
				"` only when you read or edit a file inside the project `${CAT_PROJECT_ROOT}" +
				"` whose path relative to it matches [**/rules/**/*.md].").doesNotContain("Bundled rule body");
			requireThat(Files.readString(plugin.resolve("rules/include/common/path.md")), "lazyRule").
				isEqualTo("Bundled rule body");
		}
	}

	/**
	 * Joins the lines of rendered rules, so that a sentence matches wherever the rule wraps it.
	 *
	 * @param rules the rendered rules
	 * @return the rules with every run of whitespace replaced by one space
	 */
	private static String oneLine(String rules)
	{
		return rules.replaceAll("\\s+", " ");
	}

	/**
	 * Counts the occurrences of a text in rendered rules.
	 *
	 * @param rules the rendered rules
	 * @param text  the text to count
	 * @return the number of non-overlapping occurrences
	 */
	private static int occurrences(String rules, String text)
	{
		int count = 0;
		int start = rules.indexOf(text);
		while (start >= 0)
		{
			++count;
			start = rules.indexOf(text, start + text.length());
		}
		return count;
	}
}
