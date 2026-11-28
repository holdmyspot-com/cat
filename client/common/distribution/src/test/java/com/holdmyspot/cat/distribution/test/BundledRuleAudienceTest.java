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
	 * <p>
	 * The case copies the bundled rule tree, routes it and parses the routing YAML, which takes about 0.3 s alone
	 * and over a second during a parallel build.
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
	 * Verifies research that needs a repository's source is delegated with a local clone: the delegation rule, which
	 * the workflow index routes to before every delegation, has the parent shallow-clone each named repository into the
	 * session's temporary folder, name its path in the brief, and resume the same agent when its report lists more
	 * repositories; the shared research instructions have the agent search the clone and list the repositories it still
	 * needs; and the research agents of both harnesses stay without a shell, because they read untrusted content.
	 *
	 * @throws IOException if the bundled rules or agent definitions cannot be read, copied or routed
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void researchRepositoryGuidanceIsReachable() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			Path include = plugin.resolve("rules/include/common");

			requireThat(oneLine(Files.readString(include.resolve("rule-routing/workflows.md"))), "workflowIndex").
				contains("`${CAT_PLUGIN_ROOT}/rules/include/common/delegation.md`");
			requireThat(oneLine(Files.readString(include.resolve("delegation.md"))), "delegation").
				contains("Before delegating research that needs a repository's source, shallow-clone each repository " +
					"that the question names").
				contains("`git clone --depth 1 --filter=blob:none --branch <tag>`").
				contains("into the session's temporary folder and name the clone's path in the brief").
				contains("clone them and resume the same agent with a message instead of starting a new one").
				contains("Never build or run code from a clone");

			ClassLoader loader = BundledRuleAudienceTest.class.getClassLoader();
			try (InputStream in = loader.getResourceAsStream("agents/common/research.md"))
			{
				requireThat(in, "research.md").isNotNull();
				requireThat(oneLine(new String(in.readAllBytes(), StandardCharsets.UTF_8)), "research").
					contains("When the parent names a local clone of a repository, answer source questions by " +
						"searching and reading it").
					contains("citing file paths with line numbers and the commit that the clone is at").
					contains("end the report with a list of each repository that you need, the tag or commit, " +
						"and why").
					contains("Never run or build anything from a clone");
			}
			Pattern tools = Pattern.compile("(?m)^tools:\\s*(.*)$");
			for (String tier : List.of("low", "medium", "high"))
			{
				try (InputStream in = loader.getResourceAsStream("agents/claude/research-" + tier + ".md"))
				{
					requireThat(in, "research-" + tier).isNotNull();
					Matcher matcher = tools.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
					requireThat(matcher.find(), "research-" + tier + "HasTools").isTrue();
					requireThat(matcher.group(1), "research-" + tier + "Tools").
						isEqualTo("Read, Grep, Glob, WebSearch, WebFetch");
				}
			}
		}
	}

	/**
	 * Verifies an agent that is about to delegate work can reach the packaged guidance on concurrent work in one working
	 * tree: the workflow area index routes delegation to the delegation rule, whose guidance has the agent give each
	 * concurrent piece of work that builds, tests or edits files its own isolated worktree or run the pieces one after
	 * another, and not treat a failure caused by another agent's concurrent build or edits as a defect of the change.
	 *
	 * @throws IOException        if the bundled rules cannot be copied, routed, or read
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void concurrentDelegatesGetOwnWorkingTrees() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			Path include = plugin.resolve("rules/include/common");

			requireThat(oneLine(Files.readString(include.resolve("rule-routing/workflows.md"))), "workflowIndex").
				contains("Before delegating work to a subagent, or when designing, updating, or executing a " +
					"prompt-driven workflow that delegates work, lazy load " +
					"`${CAT_PLUGIN_ROOT}/rules/include/common/delegation.md`.");

			String rule = oneLine(Files.readString(include.resolve("delegation.md")));
			requireThat(rule, "delegation").
				contains("a delegate that only builds or tests counts as write-capable here").
				contains("When you delegate two or more pieces of work that will build, test, or edit files in the " +
					"same working tree at the same time, give each its own isolated worktree and bring each one's " +
					"commits back through that integration; otherwise run them one after another.").
				contains("A failure that comes from another agent's concurrent build or edits in the same working " +
					"tree").
				contains("is not a defect of the change under test");
		}
	}

	/**
	 * Verifies an agent that reuses code that estimates something it cannot know exactly reaches the packaged guidance
	 * on the direction in which that code errs: the design-quality and prefer-reuse rules route to it, and it says that
	 * the contract states the direction, that a reuser confirms the direction matches, that differing directions need
	 * the caller's own reading or a direction parameter, and that an undecidable check reports its cause.
	 *
	 * @throws IOException        if the bundled rules cannot be copied, routed, or read
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void estimateDirectionGuidanceIsReachable() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			BundledRules.copy(plugin.resolve("rules"));
			Path include = plugin.resolve("rules/include/common");

			String contract = oneLine(Files.readString(include.resolve("contract-design.md")));
			requireThat(contract, "contractDesign").
				contains("its contract states which way it errs").
				contains("Code that grants or limits access errs toward less access").
				contains("code that detects or warns errs toward reporting").
				contains("confirm that its direction matches the new caller's").
				contains("give the caller its own reading or a parameter that picks the direction").
				contains("reports that it could not check, naming the cause");
			requireThat(oneLine(Files.readString(include.resolve("prefer-reuse.md"))), "preferReuse").
				contains("When reusing code that estimates something it cannot know exactly, " +
					"lazy load `./contract-design.md` to check which way it errs.");
			requireThat(oneLine(Files.readString(include.resolve("design-quality.md"))), "designQuality").
				contains("When reusing code that estimates something it cannot know exactly, " +
					"lazy load `./contract-design.md` to check which way it errs.");
		}
	}

	/**
	 * Verifies an agent that splits work into shards or parallel parts reaches the packaged coverage requirement from
	 * every place that tells it to split: the CI shard guidance of the performance-change rule, the concurrent-units
	 * guidance of the monotonic-progress rule, the subtask guidance of the delegation rule, and the workflow area index
	 * and the rule routing that every agent receives at startup. The requirement lives in one rule, which has the
	 * splitter add a check after the shards finish that compares an independent enumeration of the work with what the
	 * shards report and fails, naming the items, for one that no shard executed, that several executed, or that failed,
	 * and which counts an empty or unreadable shard report as failed, not as zero items.
	 *
	 * @throws IOException        if the bundled rules cannot be copied, routed, or read
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void shardCoverageGuidanceIsReachable() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-bundled-rules-project-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			Path include = plugin.resolve("rules/include/common");

			String rule = oneLine(Files.readString(include.resolve("shard-coverage.md")));
			requireThat(rule, "shardCoverage").
				startsWith("# Shard Coverage ").
				contains("add a coverage check that runs after every shard has finished").
				contains("compares the full list of work items with what the shards report").
				contains("fails, naming the items,").
				contains("was executed by no shard").
				contains("was executed by more than one shard").
				contains("failed, including a shard that crashed or timed out without reporting its items").
				contains("independent enumeration of the work").
				contains("not from the shards' own counts").
				contains("An empty or unreadable shard report counts as failed, never as zero items.");

			String lazyLoad = "lazy load `${CAT_PLUGIN_ROOT}/rules/include/common/shard-coverage.md`";
			requireThat(oneLine(Files.readString(include.resolve("performance-changes.md"))), "performanceChanges").
				contains(lazyLoad);
			requireThat(oneLine(Files.readString(include.resolve("monotonic-progress.md"))), "monotonicProgress").
				contains(lazyLoad);
			requireThat(oneLine(Files.readString(include.resolve("delegation.md"))), "delegation").
				contains(lazyLoad);
			String workflowIndex = oneLine(Files.readString(include.resolve("rule-routing/workflows.md")));
			requireThat(workflowIndex, "workflowIndex").contains(lazyLoad).
				doesNotContain("compares the full list of work items");

			ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml());
			for (String harness : List.of("claude", "codex"))
			{
				requireThat(oneLine(loader.loadForMainAgent(plugin, project, harness)), harness + "Main").
					contains("when splitting work into shards or parallel parts").
					contains("include/common/rule-routing/workflows.md`");
				requireThat(oneLine(loader.loadForSubagent(plugin, project, harness, "cat:reviewer")),
					harness + "Subagent").
					contains("when splitting work into shards or parallel parts").
					contains("include/common/rule-routing/workflows.md`");
			}
		}
	}

	/**
	 * Verifies the packaged guidance for cheaper ways to verify and to diagnose is present in the rule that owns each
	 * topic and reachable from the routing that precedes the work: the expensive-run rule has the agent test run-output
	 * consumers locally on downloaded artifacts, run a changed CI definition's static checks locally, and give a
	 * workflow's own planning and reporting logic a mode without the expensive steps; the flaky-test rule has the agent
	 * rerun the smallest scope and read the job's own retry attempts before calling a failure a flake; and the request
	 * execution rule has the agent switch to a cheaper or faster way that it recommends in the same turn.
	 *
	 * @throws IOException        if the bundled rules cannot be copied, routed, or read
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void cheaperWorkGuidanceIsReachable() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			Path include = plugin.resolve("rules/include/common");

			requireThat(oneLine(Files.readString(include.resolve("expensive-verification-runs.md"))), "expensiveRuns").
				contains("only consumes the outputs that earlier runs produced").
				contains("on artifacts downloaded from a recent run").
				contains("needs the runner's environment").
				contains("run locally every static check that the definition has").
				contains("`actionlint`").
				contains("`shellcheck`").
				contains("give it a mode that runs that logic without the expensive steps that it coordinates");
			requireThat(oneLine(Files.readString(include.resolve("flaky-tests.md"))), "flakyTests").
				contains("rerun the smallest scope that can show it").
				contains("only the named tests through a stress or repeat input").
				contains("read the job's log for the retry attempts that it already makes").
				contains("failed every attempt reproduces under that job's conditions");
			requireThat(oneLine(Files.readString(plugin.resolve("rules/common/request-execution.md"))),
				"requestExecution").
				contains("recommend a cheaper or faster way to do the work in progress").
				contains("switch to it in the same turn");

			String testingIndex = oneLine(Files.readString(include.resolve("rule-routing/testing-and-builds.md")));
			requireThat(testingIndex, "testingIndex").
				contains("`${CAT_PLUGIN_ROOT}/rules/include/common/expensive-verification-runs.md`").
				contains("`${CAT_PLUGIN_ROOT}/rules/include/common/flaky-tests.md`");
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
	 * speed or capacity, without the build-workflow rule repeating that ordering, and how to fill the provider's
	 * concurrency limit with independent runs: freely when the runner time costs nothing, and only with the user's
	 * permission when it costs money. The index also routes a change that lands while such a run is in progress to that
	 * rule, which then directs the agent to decide at once whether the run's result still counts instead of keeping the
	 * run only because it has started.
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

			requireThat(oneLine(Files.readString(include.resolve("rule-routing/testing-and-builds.md"))),
				"testingIndex").
				contains("`${CAT_PLUGIN_ROOT}/rules/include/common/expensive-verification-runs.md`").
				contains("when a change lands while such a run that you started is in progress");
			requireThat(Files.readString(include.resolve("rule-routing/failures.md")), "failureIndex").
				contains("`${CAT_PLUGIN_ROOT}/rules/include/common/run-comparisons/failure-baseline.md`");
			String expensiveRuns = oneLine(Files.readString(include.resolve("expensive-verification-runs.md")));
			requireThat(expensiveRuns, "expensiveRuns").startsWith("# Expensive Verification Runs ").
				contains("start that target first or in parallel").
				contains("up to the provider's concurrency limit").
				contains("Fill the limit without asking when the extra runner time is free").
				contains("treat them as costing money and ask").
				contains("decide at once whether the run's result still counts. Being already in progress is not a " +
					"reason to keep it.").
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
	 * external-waits rule, whose guidance ends the wait on the first failed job, sends the agent to the
	 * expensive-verification-runs rule before any new wait, and directs it to fix the failure at once; the testing and
	 * build area index routes the failed-job situation to the expensive-verification-runs rule, whose guidance decides
	 * whether the rest of the run continues, including how to check for an existing cache entry; and neither rule states
	 * the other's decision.
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
				contains("another session's turn ending").
				contains("poll it, sleep").
				contains("lazy load `${CAT_PLUGIN_ROOT}/rules/include/common/external-waits.md`");
			requireThat(oneLine(Files.readString(include.resolve("rule-routing/testing-and-builds.md"))),
				"testingIndex").
				contains("has failed while other jobs of it are queued or running, lazy load " +
					"`${CAT_PLUGIN_ROOT}/rules/include/common/expensive-verification-runs.md`");

			String externalWaits = oneLine(Files.readString(include.resolve("external-waits.md")));
			requireThat(externalWaits, "externalWaits").
				contains("wait for the first failed part as well as for the whole operation").
				contains("per-part status interface").
				contains("before you start any new wait, lazy load " +
					"`${CAT_PLUGIN_ROOT}/rules/include/common/expensive-verification-runs.md`").
				contains("Start a new wait only for the parts that the decision keeps").
				contains("start diagnosing and fixing the failure at once").
				contains("Do not wait on it, through `--watch` or by running it yourself").
				doesNotContain("cancel the run now");

			String expensiveRuns = oneLine(Files.readString(include.resolve("expensive-verification-runs.md")));
			requireThat(expensiveRuns, "expensiveRuns").
				contains("whether the rest of the run has value").
				contains("list the repository's cache entries for it").
				contains("cancel the run now through the hosting service").
				contains("Neither a request to wait for the run to finish").
				doesNotContain("per-part status interface");
		}
	}

	/**
	 * Verifies an agent that writes or changes a build or job step that downloads from one upstream server, or sees a
	 * job fail because such a server was unreachable, can reach the packaged rule for that decision: the testing and
	 * build area index and the CI debugging rule route to the upstream-fallbacks rule, whose guidance adds a fallback
	 * that is tried only when the primary fails, accepts only an equivalent source whose content the same checks
	 * verify, does not leave the agent only waiting for the provider to recover, and counts the fallback as verified
	 * only after a run in the step's real environment with the primary forced to fail, which the agent's final reply
	 * names as outstanding.
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
				contains("only after it has run once in the real environment where the step runs").
				contains("your final reply to the user says that the fallback is unverified and names that run");
		}
	}

	/**
	 * Verifies an agent that is about to install, update or uninstall CAT or a plugin can reach the packaged rule for
	 * that decision: the workflow area index routes to the install-running-work rule, whose guidance has the agent
	 * check for running work that uses the installed copy, wait for it or confirm that it uses its own copied runtime,
	 * and tell the user what an install they requested waits for.
	 * <p>
	 * The test copies the bundled rule tree to a temporary plugin and applies the real artifact router before
	 * reading the workflow index and routed rule. This physical packaging boundary verifies that the references
	 * reach the shipped files rather than only checking the source text.
	 *
	 * @throws IOException        if the bundled rules cannot be copied, routed, or read
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void installRunningWorkRuleIsRouted() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			Path include = plugin.resolve("rules/include/common");

			requireThat(oneLine(Files.readString(include.resolve("rule-routing/workflows.md"))), "workflowIndex").
				contains("Before installing, updating or uninstalling CAT or any plugin, lazy load " +
					"`${CAT_PLUGIN_ROOT}/rules/include/common/install-running-work.md`.");

			String rule = oneLine(Files.readString(include.resolve("install-running-work.md")));
			requireThat(rule, "installRunningWork").startsWith("# Installing Plugins ").
				contains("probe, screen or runner sessions").
				contains("wait for it to finish before you install").
				contains("uses its own copied runtime").
				contains("tell the user what it waits for");
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
				contains("Do not make the change.").contains("shard jobs that run on separate machines").
				contains("concurrent runner limit");
			String caching = Files.readString(plugin.resolve("rules/include/common/build-caching.md")).
				replaceAll("\\s+", " ");
			requireThat(caching, "buildCaching").contains("enable the build tool's own build cache").
				contains("shared or remote cache").contains("incremental compilation").
				contains("rules/include/common/build-verification.md` and").
				contains("rules/include/common/build-workflow.md` require");
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
	 * Verifies an agent that has created a temporary file or directory, clone, worktree, or branch for its own work can
	 * reach the packaged rule on deleting it: the rule routing that every agent of either harness receives at startup
	 * routes to the workflow area index after a merge, when a step or work item finishes, when work is abandoned, and
	 * before a hand-back or final reply; the index and the temporary-resources rule route to the agent-cleanup rule,
	 * whose guidance has the agent delete a merged or abandoned clone or worktree with its branch, build output, and
	 * scratch files, including folders in its own session directory, keep what work still meant to be merged, an
	 * undelivered report, or a running process needs, and never delete what another session or the user owns.
	 *
	 * @throws IOException        if the bundled rules cannot be copied, routed, or read
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void agentCleanupRuleIsRouted() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-bundled-rules-project-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			Path include = plugin.resolve("rules/include/common");
			String route = "lazy load `${CAT_PLUGIN_ROOT}/rules/include/common/temporary-resources/agent-cleanup.md`";

			requireThat(oneLine(Files.readString(include.resolve("rule-routing/workflows.md"))), "workflowIndex").
				contains("after you merge a clone's or worktree's changes, when a step or work item finishes, when " +
					"you abandon work, and before you hand back your result or send your final reply, " + route).
				contains("lazy load `${CAT_PLUGIN_ROOT}/rules/include/common/temporary-resources.md`");
			requireThat(oneLine(Files.readString(include.resolve("temporary-resources.md"))), "temporaryResources").
				contains("that you created through your own tool calls, " + route);

			String rule = oneLine(Files.readString(include.resolve("temporary-resources/agent-cleanup.md")));
			requireThat(rule, "agentCleanup").startsWith("# Cleaning Up Your Working Files ").
				contains("the `.cat/sessions/<session id>/` directory that contains `${CAT_TEMP}`").
				contains("once every change on it that is to be kept is in its base branch").
				contains("`git worktree remove <path>`").
				contains("work that is abandoned: aborted, superseded, or rejected, with no plan to merge it").
				contains("Build output in a clone or worktree that you keep").
				contains("It holds work that is still meant to be merged or reported").
				contains("A report that you have not yet delivered cites it").
				contains("Never delete another session's directory").
				contains("When you cannot tell whether you created an artifact, leave it");

			ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml());
			for (String harness : List.of("claude", "codex"))
			{
				for (String context : List.of(loader.loadForMainAgent(plugin, project, harness),
					loader.loadForSubagent(plugin, project, harness, "cat:reviewer")))
				{
					requireThat(oneLine(context), harness + "Context").
						contains("after merging a clone's or worktree's changes, when a step or work item finishes, " +
							"when abandoning work, and before handing back your result or sending your final reply;").
						contains("include/common/rule-routing/workflows.md`").
						doesNotContain("# Cleaning Up Your Working Files");
				}
			}
		}
	}

	/**
	 * Verifies an agent that writes or keeps a test of a script that the project owns, such as a generated launcher,
	 * a hook command, or shell command text in a rule, can reach the packaged rule for choosing its test language: the
	 * testing and build area index routes test design to the testing rule, whose guidance puts every test that runs
	 * such a script in the project's shell-test framework, forbids an application-language test from running one and
	 * limits it to what application code decides about the script, has the agent move the claims of such a test to a
	 * shell test even when it passes, and has the agent add a shell-test framework or ask the user when the project has
	 * none.
	 *
	 * @throws IOException        if the bundled rules cannot be copied, routed, or read
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void scriptTestLanguageRuleIsRouted() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			Path include = plugin.resolve("rules/include/common");

			requireThat(oneLine(Files.readString(include.resolve("rule-routing/testing-and-builds.md"))),
				"testingIndex").
				contains("When designing, changing, running to verify a change, or reviewing tests, lazy load " +
					"`${CAT_PLUGIN_ROOT}/rules/include/common/testing.md`.");
			String rule = oneLine(Files.readString(include.resolve("testing.md")));
			requireThat(rule, "testing").startsWith("# Testing Focus and Isolation ").
				contains("and for every test that runs a script the project owns: a shell script, generated " +
					"launcher, hook command, packaged command-line entry point, or shell command text in a rule or " +
					"document.").
				contains("An application-language test must not run such a script. It proves only what " +
					"application code decides about the script, such as the text the code generates, the hook " +
					"entries it writes, or the arguments it passes;").
				contains("Do not keep an application-language test that runs one, even when it passes: move each " +
					"claim about what the script does to a shell test").
				contains("When the project has no shell-test framework, add one if your task permits changing the " +
					"project's test dependencies; otherwise ask the user whether to add one.");
		}
	}

	/**
	 * Verifies an agent that writes or reviews a test that runs the same assertions over inputs of distinct categories
	 * can reach the packaged rule for grouping those inputs: the testing and build area index routes test design to the
	 * testing rule, whose guidance puts each category in its own named collection under a comment that says what the
	 * category represents and why its inputs get the expected outcome, and runs the shared check once over the union.
	 *
	 * @throws IOException        if the bundled rules cannot be copied, routed, or read
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void groupedTestInputsRuleIsRouted() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			Path include = plugin.resolve("rules/include/common");

			requireThat(oneLine(Files.readString(include.resolve("rule-routing/testing-and-builds.md"))),
				"testingIndex").
				contains("When designing, changing, running to verify a change, or reviewing tests, lazy load " +
					"`${CAT_PLUGIN_ROOT}/rules/include/common/testing.md`.");
			String rule = oneLine(Files.readString(include.resolve("testing.md")));
			requireThat(rule, "testing").
				contains("When one test runs the same assertions over many inputs that fall into distinct " +
					"categories, put each category's inputs in its own named collection").
				contains("says what the category represents and why its inputs get the expected outcome").
				contains("run the shared check once over the union of the collections").
				contains("whether the inputs are accepted or rejected");
		}
	}

	/**
	 * Verifies packaged path routes render installed rule paths and the current project's matching boundary.
	 * <p>
	 * The case routes a rule and loads it through the YAML-backed loader, which takes about 0.25 s alone when it
	 * runs first in its JVM and over a second during a parallel build.
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
	 * Verifies an agent of either harness, main or subagent, can reach the two credential-hygiene rules from the rule
	 * routing that it receives at startup, without receiving either rule itself: the rule on printing another process's
	 * command line has the agent replace the password of every URL in it with {@code ***} and prefer a listing of
	 * process IDs and command names, and the rule on credential files has the agent keep a copy of one where no session,
	 * results folder, trial project or commit can reach it and prefer a helper that reads the credential at runtime.
	 *
	 * @throws IOException        if the bundled rules cannot be copied, routed, or read
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void credentialHygieneRulesAreRouted() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-bundled-rules-project-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			Path include = plugin.resolve("rules/include/common");

			String commandLines = oneLine(Files.readString(include.resolve("process-command-lines.md")));
			requireThat(commandLines, "commandLines").startsWith("# Process Command Lines ").
				contains("`***@`").contains("`ps -o pid,comm`").contains("`/proc/<pid>/cmdline`");
			String credentialCopies = oneLine(Files.readString(include.resolve("credential-copies.md")));
			requireThat(credentialCopies, "credentialCopies").startsWith("# Credential Copies ").
				contains("`auth.json`").contains("`.credentials.json`").contains("reads the credential at runtime").
					contains("`env | cut -d= -f1`").contains("recommend rotating it");

			ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml());
			for (String harness : List.of("claude", "codex"))
			{
				for (String context : List.of(loader.loadForMainAgent(plugin, project, harness),
					loader.loadForSubagent(plugin, project, harness, "cat:reviewer")))
				{
					requireThat(context, harness + "Context").contains("include/common/process-command-lines.md`").
						contains("include/common/credential-copies.md`").
							contains("printing an environment value").
						doesNotContain("# Process Command Lines").doesNotContain("# Credential Copies");
				}
			}
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
