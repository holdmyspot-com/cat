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
	 * Verifies a subagent receives CAT's default rule routing and request-execution rule in both harnesses, so it can
	 * lazy load engineering rules such as design quality, TDD and testing and decide which gaps to escalate, while the
	 * rules about interacting with the user stay with the main agent. Neither agent receives the design-quality rule
	 * itself at startup.
	 * <p>
	 * Copying and routing the complete bundled rule trees, then loading all four audience/harness combinations, can take
	 * over one second. A smaller synthetic tree would miss regressions in shipped audience metadata and dependencies.
	 */
	@Test
	public void subagentGetsRoutingNotUserRules() throws IOException, URISyntaxException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-bundled-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-bundled-rules-project-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml());

			for (String harness : List.of("claude", "codex"))
			{
				String subagent = loader.loadForSubagent(plugin, project, harness, "cat:reviewer");
				requireThat(subagent, harness + "Subagent").contains("# Default Rule Routing").
					contains("include/common/design-quality.md`").doesNotContain("# Design Quality").
					contains("lazy load `").contains("# Request Execution").
					contains("A subagent does not load this rule").
					doesNotContain("# User Request Scheduling").doesNotContain("# File Links");

				String main = loader.loadForMainAgent(plugin, project, harness);
				requireThat(main, harness + "Main").contains("# Default Rule Routing").
					contains("# Request Execution").contains("# User Request Scheduling").contains("# File Links").
					doesNotContain("# Design Quality");
			}
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
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
	 * Verifies packaged path routes render installed rule paths and the current project's matching boundary.
	 */
	@Test
	public void packagedPathRulesMatchOnlyInsideProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
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
