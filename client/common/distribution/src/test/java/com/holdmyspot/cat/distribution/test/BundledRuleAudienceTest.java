package com.holdmyspot.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
	 * reachable from the always-loaded routing through the interface-design area index.
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

			String context = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "claude");

			requireThat(context, "context").
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
}
