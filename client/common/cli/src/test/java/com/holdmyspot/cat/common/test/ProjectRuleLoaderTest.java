package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ProjectRuleLoader;
import com.holdmyspot.cat.common.RuleSource;
import com.holdmyspot.cat.common.SessionRules;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.fakes.test.TestToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Tests {@link ProjectRuleLoader}.
 */
public final class ProjectRuleLoaderTest
{
	/**
	 * Creates a test instance.
	 */
	public ProjectRuleLoaderTest()
	{
	}

	/**
	 * Verifies the loader injects common and selected-harness rule bodies while retaining source-only design goals.
	 */
	@Test
	public void loadsCommonAndSelectedHarnessRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			String pluginCommon = "# Plugin Common\n\n## Design Goals\n\n- Preserve the source rule.\n\n" +
				"## Guidance\n\nplugin common\n";
			write(plugin, "rules/common/common.md", pluginCommon);
			write(plugin, "rules/harnessa/harnessa.md", "# Plugin Harness\n\nplugin harnessa\n");
			write(plugin, "rules/harnessb/harnessb.md", "plugin harnessb");
			write(project, ".cat/rules/common/common.md", "project common");
			write(project, ".cat/rules/harnessa/harnessa.md", "project harnessa");
			write(project, ".cat/rules/harnessb/harnessb.md", "project harnessb");
			write(project, ".cat/rules/harnessa/path.md", "---\npaths: [\"*.java\"]\n---\nproject Java rule");

			String context;
			try (ToolScope scope = new TestToolScope())
			{
				context = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "harnessa");
			}

			requireThat(context, "context").contains("plugin common").contains("plugin harnessa").
				contains("project common").contains("project harnessa").
				contains("Load `" + project.resolve(".cat/rules/harnessa/path.md") +
					"` only when you read or edit a file inside the project `${CAT_PROJECT_ROOT}" +
					"` whose path relative to it matches [*.java].").
				doesNotContain("## Design Goals").doesNotContain("### CAT rule:").
				doesNotContain("### CAT path-scoped rule:").doesNotContain("plugin harnessb").
				doesNotContain("project harnessb").doesNotContain("project Java rule");
			requireThat(context, "context").contains("plugin common\n\n---\n# Plugin Harness");
			requireThat(Files.readString(plugin.resolve("rules/common/common.md")), "sourceRule").
			contains("## Design Goals");
		}
	}

	/**
	 * Verifies bundled path triggers name the installed rule and restrict matching to project-relative paths.
	 */
	@Test
	public void restrictsBundledPathRulesToProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/path.md", "---\npaths: [\"**/rules/**/*.md\"]\n---\nBundled rule body");

			String context = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "harnessa");

			requireThat(context, "context").isEqualTo("Load `" + plugin.resolve("rules/common/path.md") +
				"` only when you read or edit a file inside the project `${CAT_PROJECT_ROOT}" +
				"` whose path relative to it matches [**/rules/**/*.md].");
		}
	}

	/**
	 * Verifies explicit installed rules use the loader's body rendering and absolute lazy-load paths.
	 */
	@Test
	public void rendersInstalledRuleForRole() throws IOException
	{
		// Requirement: R-E20
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			write(plugin, "rules/include/common/role.md", "---\npaths: [\"*.md\"]\n---\n# Role\n\n" +
				"<!-- cat:include ../shared/details.md -->\n\n" +
				"Root: <!-- cat:plugin-root -->. Lazy load `${CAT_PLUGIN_ROOT}/rules/include/common/lazy.md`.");
			write(plugin, "rules/include/shared/details.md", "Complete included guidance. Lazy load `../common/lazy.md`.");
			write(plugin, "rules/include/common/lazy.md", "Lazy guidance");

			String context = new ProjectRuleLoader(scope.yaml()).loadRule(plugin, Path.of("include/common/role.md"));

			requireThat(context, "context").isEqualTo("# Role\n\nComplete included guidance. Lazy load `" +
				plugin.resolve("rules/include/common/lazy.md") + "`.\n\nRoot: " + plugin + ". Lazy load `" +
				plugin.resolve("rules/include/common/lazy.md") + "`.");
		}
	}

	/**
	 * Verifies an explicitly requested installed rule cannot be silently omitted when its file is missing.
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*rules[/\\\\]include[/\\\\]common[/\\\\]missing\\.md.*")
	public void rejectsMissingInstalledRule() throws IOException
	{
		// Requirement: R-E20
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			new ProjectRuleLoader(scope.yaml()).loadRule(plugin, Path.of("include/common/missing.md"));
		}
	}

	/**
	 * Verifies recipient frontmatter selects rules independently for a parent and named harness subagent.
	 */
	@Test
	public void filtersRulesByAgentAudience() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/all.md", "all recipients");
			write(plugin, "rules/common/main.md", "---\nagents: [\"main\"]\n---\nmain only");
			write(plugin, "rules/common/subagents.md", "---\nagents: [\"subagents\"]\n---\nall subagents");
			write(plugin, "rules/common/reviewer.md", "---\nagents: [\"cat:reviewer\"]\n---\nreviewer only");

			ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml());
			String main = loader.loadForMainAgent(plugin, project, "claude");
			String reviewer = loader.loadForSubagent(plugin, project, "claude", "cat:reviewer");
			String explorer = loader.loadForSubagent(plugin, project, "claude", "Explore");

			requireThat(main, "main").contains("all recipients").contains("main only").
				doesNotContain("all subagents").doesNotContain("reviewer only");
			requireThat(reviewer, "reviewer").contains("all recipients").contains("all subagents").
				contains("reviewer only").doesNotContain("main only");
			requireThat(explorer, "explorer").contains("all recipients").contains("all subagents").
				doesNotContain("main only").doesNotContain("reviewer only");
		}
	}

	/**
	 * Verifies direct lazy-rule routes precede other common guidance.
	 */
	@Test
	public void loadsRuleRoutingFirst() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/write-clearly.md", "# Write clearly\n\nWrite-clearly guidance\n");
			write(plugin, "rules/common/rule-routing.md", "# Routes\n\nRoute guidance\n");

			String context;
			try (ToolScope scope = new TestToolScope())
			{
				context = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "harnessa");
			}

			requireThat(context.indexOf("# Routes"), "routeIndex").isLessThan(context.indexOf("# Write clearly"));
		}
	}

	/**
	 * Verifies a user's or project's routing index that still has its former name, {@code default-rules.md}, keeps
	 * preceding the other rules of its directory, so existing rule trees need no change.
	 */
	@Test
	public void loadsFormerRoutingIndexFirst() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path userRules = cleanup.add(Files.createTempDirectory("cat-user-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(userRules, "common/build.md", "# User build\n\nUser build guidance\n");
			write(userRules, "common/default-rules.md", "# User routes\n\nUser route guidance\n");
			write(project, ".cat/rules/common/build.md", "# Project build\n\nProject build guidance\n");
			write(project, ".cat/rules/common/default-rules.md", "# Project routes\n\nProject route guidance\n");

			String context = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, userRules, project, "harnessa");

			requireThat(context.indexOf("# User routes"), "userRouteIndex").
				isLessThan(context.indexOf("# User build"));
			requireThat(context.indexOf("# Project routes"), "projectRouteIndex").
				isLessThan(context.indexOf("# Project build"));
		}
	}

	/**
	 * Verifies the harness CAT environment, which defines the {@code CAT_} names, precedes every other rule so each
	 * name is defined before a rule refers to it.
	 */
	@Test
	public void loadsCatEnvironmentFirst() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/rule-routing.md", "# Routes\n\nRoute guidance\n");
			write(plugin, "rules/harnessa/cat-env.md", "# CAT environment\n\nPlugin directory guidance\n");
			write(project, ".cat/rules/common/project.md", "# Project\n\nProject guidance\n");

			String context;
			try (ToolScope scope = new TestToolScope())
			{
				context = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "harnessa");
			}

			requireThat(context, "context").startsWith("# CAT environment").contains("# Routes").
				contains("# Project");
		}
	}

	/**
	 * Verifies the harness rule on reading a file whole follows the CAT environment directly and precedes every other
	 * rule, including a harness rule whose filename sorts before it.
	 */
	@Test
	public void loadsReadFileRuleAfterEnvironment() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/rule-routing.md", "# Routes\n\nRoute guidance\n");
			write(plugin, "rules/harnessa/cat-env.md", "# CAT environment\n\nPlugin directory guidance\n");
			write(plugin, "rules/harnessa/a-rendering.md", "# Rendering\n\nRendering guidance\n");
			write(plugin, "rules/harnessa/read-file.md", "# Reading files\n\nRead guidance\n");
			write(project, ".cat/rules/common/project.md", "# Project\n\nProject guidance\n");

			String context;
			try (ToolScope scope = new TestToolScope())
			{
				context = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "harnessa");
			}

			requireThat(context, "context").startsWith("# CAT environment");
			int readFile = context.indexOf("# Reading files");
			requireThat(readFile, "readFile").isLessThan(context.indexOf("# Routes")).
				isLessThan(context.indexOf("# Rendering")).isLessThan(context.indexOf("# Project"));
		}
	}

	/**
	 * Verifies a session that keeps its rules in a file receives the harness rule on reading a file whole apart from
	 * the other rules, with the plugin root stated in it, while the other rules keep their order without it.
	 */
	@Test
	public void separatesReadingGuidanceFromSessionRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/rule-routing.md", "# Routes\n\nRoute guidance\n");
			write(plugin, "rules/harnessa/cat-env.md", "# CAT environment\n\nPlugin directory guidance\n");
			write(plugin, "rules/harnessa/read-file.md", "# Reading files\n\nRun `" +
				ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE + "/bin/run-part`\n");
			write(project, ".cat/rules/common/project.md", "# Project\n\nProject guidance\n");

			SessionRules session;
			try (ToolScope scope = new TestToolScope())
			{
				session = new ProjectRuleLoader(scope.yaml()).loadSessionForMainAgent(plugin, null, project, "harnessa");
			}

			requireThat(session.readingGuidance(), "readingGuidance").startsWith("# Reading files").
				contains("Run `" + plugin.resolve("bin/run-part") + "`").doesNotContain("# CAT environment").
				doesNotContain(ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE);
			requireThat(session.rules(), "rules").startsWith("# CAT environment").doesNotContain("# Reading files");
			requireThat(session.rules().indexOf("# Routes"), "routes").
				isLessThan(session.rules().indexOf("# Project"));
		}
	}

	/**
	 * Verifies that a bundled rule's shell-specific directive becomes a path below the plugin root, quoted so that its
	 * shell passes the path unchanged when the plugin root holds a {@code '}: a POSIX shell closes the quotes around an
	 * escaped {@code '}, and PowerShell doubles it.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void quotesPluginPathsForShells() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-o'brien-plugin-")).toAbsolutePath().normalize();
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/harnessa/read-file.md", "posix: " + ProjectRuleLoader.POSIX_PLUGIN_PATH_DIRECTIVE +
				"/bin/run-part\npowershell: " + ProjectRuleLoader.POWERSHELL_PLUGIN_PATH_DIRECTIVE +
				"/bin/run-part.cmd\n");

			String guidance = new ProjectRuleLoader(scope.yaml()).
				loadSessionForMainAgent(plugin, null, project, "harnessa").readingGuidance();

			String posix = plugin.resolve("bin").resolve("run-part").toString();
			String powerShell = plugin.resolve("bin").resolve("run-part.cmd").toString();
			requireThat(guidance, "guidance").
				contains("posix: '" + posix.replace("'", "'\"'\"'") + "'\n").
				endsWith("powershell: '" + powerShell.replace("'", "''") + "'");
		}
	}

	/**
	 * Verifies that on Windows, a rule's include of {@code {platform}} names the harness's Windows fragment, and that
	 * the fragments themselves are not loaded as rules.
	 */
	@Test
	public void includesWindowsFragmentsOnWindows() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			writeHarnessRules(plugin);

			SessionRules session = new ProjectRuleLoader(scope.yaml(), Platform.WINDOWS_X64).
				loadSessionForMainAgent(plugin, null, project, "harnessa");

			requireThat(session.readingGuidance(), "readingGuidance").
				isEqualTo("# Reading files\n\nRun run-part.cmd\n\nRead every part");
			requireThat(session.rules(), "rules").startsWith("# CAT environment\n\nRemove-Item\n\nShared guidance").
				doesNotContain("rm -rf").doesNotContain("Run run-part");
		}
	}

	/**
	 * Verifies that on other platforms, a rule's include of {@code {platform}} names the harness's POSIX fragment.
	 */
	@Test
	public void includesPosixFragmentsOnLinux() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			writeHarnessRules(plugin);

			SessionRules session = new ProjectRuleLoader(scope.yaml(), Platform.LINUX_X64).
				loadSessionForMainAgent(plugin, null, project, "harnessa");

			requireThat(session.readingGuidance(), "readingGuidance").
				isEqualTo("# Reading files\n\nRun run-part\n\nRead every part");
			requireThat(session.rules(), "rules").startsWith("# CAT environment\n\nrm -rf\n\nShared guidance").
				doesNotContain("Remove-Item").doesNotContain("Run run-part");
		}
	}

	/**
	 * Verifies that the files a rule gives its reader include the fragment for the current platform only.
	 */
	@Test
	public void listsCurrentPlatformFragments() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-")).toAbsolutePath().normalize();
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			writeHarnessRules(plugin);

			List<RuleSource> sources = new ProjectRuleLoader(scope.yaml(), Platform.WINDOWS_X64).
				sourcesForMainAgent(plugin, null, project, "harnessa");

			Path environment = plugin.resolve("rules/harnessa/cat-env.md");
			requireThat(sources, "sources").contains(new RuleSource(
				plugin.resolve("rules/harnessa/platform/windows/cat-env/temp.md"), RuleSource.Kind.INCLUDED,
				Optional.of(environment))).doesNotContain(new RuleSource(
				plugin.resolve("rules/harnessa/platform/posix/cat-env/temp.md"), RuleSource.Kind.INCLUDED,
				Optional.of(environment)));
		}
	}

	/**
	 * Writes harness rules whose platform differences are fragments that each rule includes, and a common rule.
	 *
	 * @param plugin the plugin directory
	 * @throws IOException if a file cannot be written
	 */
	private static void writeHarnessRules(Path plugin) throws IOException
	{
		write(plugin, "rules/common/rule-routing.md", "# Routes\n\nRoute guidance\n");
		write(plugin, "rules/harnessa/cat-env.md", "# CAT environment\n\n" +
			"<!-- cat:include platform/{platform}/cat-env/temp.md -->\n\nShared guidance\n");
		write(plugin, "rules/harnessa/read-file.md", "# Reading files\n\n" +
			"<!-- cat:include platform/{platform}/read-file/run-part.md -->\n\nRead every part\n");
		write(plugin, "rules/harnessa/platform/posix/cat-env/temp.md", "rm -rf\n");
		write(plugin, "rules/harnessa/platform/posix/read-file/run-part.md", "Run run-part\n");
		write(plugin, "rules/harnessa/platform/windows/cat-env/temp.md", "Remove-Item\n");
		write(plugin, "rules/harnessa/platform/windows/read-file/run-part.md", "Run run-part.cmd\n");
	}

	/**
	 * Verifies that, without the harness rule on reading a file whole, a session's reading guidance is empty and its
	 * rules are every rule that the primary agent receives inline.
	 */
	@Test
	public void keepsEveryRuleWithoutReadingGuidance() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/harnessa/cat-env.md", "# CAT environment\n\nPlugin directory guidance\n");
			ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml());

			SessionRules session = loader.loadSessionForMainAgent(plugin, null, project, "harnessa");

			requireThat(session.readingGuidance(), "readingGuidance").isEmpty();
			requireThat(session.rules(), "rules").isEqualTo(loader.loadForMainAgent(plugin, project, "harnessa"),
				"inline");
		}
	}

	/**
	 * Verifies declared prerequisite rules are injected first and YAML metadata remains out of the context.
	 */
	@Test
	public void ordersDependenciesWithoutMetadata() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/a-dependent.md", "---\ndepends-on: [\"../harnessa/b-intermediate.md\"]\n" +
				"---\n# Dependent\n\nDependent guidance\n");
			write(plugin, "rules/harnessa/b-intermediate.md", "---\ndepends-on: [\"./z-prerequisite.md\"]\n" +
				"---\n# Intermediate\n\nIntermediate guidance\n");
			write(plugin, "rules/harnessa/z-prerequisite.md", "# Prerequisite\n\nPrerequisite guidance\n");

			String context;
			try (ToolScope scope = new TestToolScope())
			{
				context = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "harnessa");
			}

			requireThat(context, "context").isEqualTo("# Prerequisite\n\nPrerequisite guidance\n\n---\n# Intermediate\n" +
				"\nIntermediate guidance\n\n---\n# Dependent\n\nDependent guidance").doesNotContain("depends-on").
				doesNotContain("---\n---");
		}
	}

	/**
	 * Verifies a rule and every declared prerequisite reach the same subagent audience.
	 */
	@Test
	public void loadsDependenciesForSubagents() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/backward-design.md", "---\ndepends-on: [\"./intermediate.md\"]\n---\n" +
				"# Backward Design\n\nDependent guidance\n");
			write(plugin, "rules/common/intermediate.md", "# Intermediate Goals\n\nPrerequisite guidance\n");

			String context = new ProjectRuleLoader(scope.yaml()).loadForSubagent(plugin, project, "harnessa", "worker");

			requireThat(context, "context").isEqualTo("# Intermediate Goals\n\nPrerequisite guidance\n\n---\n" +
				"# Backward Design\n\nDependent guidance");
		}
	}

	/**
	 * Verifies direct bundled rules name their lazy included rules below the plugin-root variable, which the injected
	 * skill environment defines once, instead of repeating the installed directory in every reference.
	 */
	@Test
	public void resolvesBundledLazyRuleReferences() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path lazyRule = plugin.resolve("rules/include/java/javadoc.md");
			write(plugin, "rules/common/rule-routing.md", "Before editing Java, lazy load `../include/java/javadoc.md`.\n");
			write(plugin, "rules/include/java/javadoc.md", "Javadoc guidance\n");

			String context;
			try (ToolScope scope = new TestToolScope())
			{
				context = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "harnessa");
			}

			requireThat(context, "context").contains("`${CAT_PLUGIN_ROOT}/rules/include/java/javadoc.md`").
				doesNotContain(lazyRule.toString()).doesNotContain("`../include/java/javadoc.md`");
		}
	}

	/**
	 * Verifies a direct rule expands source-only Markdown from its rule root without exposing the assembly directive or
	 * source-only design goals to the recipient.
	 */
	@Test
	public void expandsDirectRuleIncludes() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/direct.md", "# Direct\n\n<!-- cat:include ../include/common/body.md -->\n\n" +
				"Direct guidance\n");
			write(plugin, "rules/include/common/body.md", "## Design Goals\n\n- Source only.\n\n## Guidance\n\n" +
				"Shared guidance\n");

			String context;
			try (ToolScope scope = new TestToolScope())
			{
				context = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "harnessa");
			}

			requireThat(context, "context").contains("# Direct").contains("Shared guidance").
				contains("Direct guidance").doesNotContain("cat:include").doesNotContain("## Design Goals");
		}
	}

	/**
	 * Verifies a project rule, which no build step processes, reaches the session without its design goals or the
	 * comments that remind its developers why a goal was added.
	 */
	@Test
	public void stripsSourceOnlyProjectContent() throws IOException
	{
		// Requirement: R-I10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(project, ".cat/rules/common/common.md", "# Project\n\n## Design Goals\n\n" +
				"<!-- Why the goal exists. -->\n- Source only.\n\n## Guidance\n\n<!-- A developer note. -->\n" +
				"Project guidance\n");

			String context;
			try (ToolScope scope = new TestToolScope())
			{
				context = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "harnessa");
			}

			requireThat(context, "context").contains("Project guidance").doesNotContain("## Design Goals").
				doesNotContain("Source only").doesNotContain("<!--");
		}
	}

	/**
	 * Verifies a direct rule that mentions the include directive within a line keeps that text, because only a line
	 * that consists of the directive includes a file.
	 */
	@Test
	public void keepsIncludeDirectiveMentionedInText() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			String mention = "Use `<!-- cat:include path -->` only to assemble source-only Markdown.";
			write(plugin, "rules/common/direct.md", "# Direct\n\n" + mention + "\n");

			String context;
			try (ToolScope scope = new TestToolScope())
			{
				context = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "harnessa");
			}

			requireThat(context, "context").contains(mention);
		}
	}

	/**
	 * Verifies a bundled rule states the plugin root in place of the plugin-root directive, including in source-only
	 * Markdown that it includes.
	 */
	@Test
	public void statesPluginRootInBundledRules() throws IOException
	{
		// Requirement: R-I5
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/direct.md", "direct: " + ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE + "\n" +
				"<!-- cat:include ../include/common/body.md -->\n");
			write(plugin, "rules/include/common/body.md", "included: " + ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE + "\n");

			String context = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin.resolve("rules/.."), project,
				"harnessa");

			requireThat(context, "context").contains("direct: " + plugin + "\n").contains("included: " + plugin).
				doesNotContain(ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE);
		}
	}

	/**
	 * Verifies that a bundled rule states the project directory that the caller supplied wherever it writes the
	 * project-root directive, while project and user rules receive the directive unchanged.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void statesProjectRootInBundledRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path userRules = cleanup.add(Files.createTempDirectory("cat-user-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-")).toAbsolutePath().normalize();
			write(plugin, "rules/harnessa/cat-env.md", "project: " + ProjectRuleLoader.PROJECT_ROOT_DIRECTIVE +
				"/.cat/sessions\n");
			write(userRules, "common/user.md", "user: " + ProjectRuleLoader.PROJECT_ROOT_DIRECTIVE);
			write(project, ".cat/rules/common/project.md", "own: " + ProjectRuleLoader.PROJECT_ROOT_DIRECTIVE);
			Files.createDirectories(project.resolve("module"));

			String context = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, userRules,
				project.resolve("module/.."), "harnessa");

			requireThat(context, "context").contains("project: " + project + "/.cat/sessions\n").
				contains("user: " + ProjectRuleLoader.PROJECT_ROOT_DIRECTIVE).
				contains("own: " + ProjectRuleLoader.PROJECT_ROOT_DIRECTIVE);
		}
	}

	/**
	 * Verifies that a bundled rule states the user rule directory that the session loads wherever it writes the
	 * user-rules directive, and that a session without a user rule directory receives no paragraph that names it.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void statesUserRulesInBundledRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path userRules = cleanup.add(Files.createTempDirectory("cat-user-rules-")).toAbsolutePath().normalize();
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/harnessa/cat-env.md", "# Environment\n\nBefore.\n\n`CAT_USER_RULES` is `" +
				ProjectRuleLoader.USER_RULES_DIRECTIVE + "`, the user rule directory.\nUse it in place of " +
				"`${CAT_USER_RULES}`.\n\nAfter.\n");
			Files.createDirectories(userRules.resolve("module"));
			ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml());

			String withUserRules = loader.loadForMainAgent(plugin, userRules.resolve("module/.."), project,
				"harnessa");
			String withoutUserRules = loader.loadForMainAgent(plugin, null, project, "harnessa");
			String missingUserRules = loader.loadForMainAgent(plugin, userRules.resolve("missing"), project,
				"harnessa");

			requireThat(withUserRules, "withUserRules").
				contains("Before.\n\n`CAT_USER_RULES` is `" + userRules + "`, the user rule directory.\n").
				contains("\n\nAfter.");
			for (String context : List.of(withoutUserRules, missingUserRules))
			{
				requireThat(context, "context").contains("Before.\n\nAfter.").doesNotContain("CAT_USER_RULES").
					doesNotContain(ProjectRuleLoader.USER_RULES_DIRECTIVE);
			}
		}
	}

	/**
	 * Verifies project and user rules receive the plugin-root directive unchanged, because the plugin root is not
	 * theirs to state.
	 */
	@Test
	public void keepsPluginRootDirectiveOutsidePlugin() throws IOException
	{
		// Requirement: R-I5
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path userRules = cleanup.add(Files.createTempDirectory("cat-user-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(userRules, "common/user.md", "user: " + ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE);
			write(project, ".cat/rules/common/project.md", "project: " + ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE);

			String context = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, userRules, project, "harnessa");

			requireThat(context, "context").contains("user: " + ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE).
				contains("project: " + ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE).doesNotContain(plugin.toString());
		}
	}

	/**
	 * Verifies the loader rejects a rule whose prerequisite is not selected for the current context.
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*dependency is not available.*")
	public void rejectsUnavailableDependency() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/dependent.md", "---\ndepends-on: [\"./missing.md\"]\n---\n" +
				"Dependent guidance\n");

			try (ToolScope scope = new TestToolScope())
			{
				new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "harnessa");
			}
		}
	}

	/**
	 * Verifies direct rules cannot use lazy-loaded path-scoped rules as prerequisites.
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*dependency is not available.*")
	public void rejectsPathScopedDependency() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/dependent.md", "---\ndepends-on: [\"./scoped.md\"]\n---\n" +
				"Dependent guidance\n");
			write(plugin, "rules/common/scoped.md", "---\npaths: [\"**/*.java\"]\n---\n" +
				"Scoped guidance\n");

			try (ToolScope scope = new TestToolScope())
			{
				new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "harnessa");
			}
		}
	}

	/**
	 * Verifies lazy-loaded path-scoped rules cannot declare prerequisites.
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*Path-scoped rule cannot declare " +
		"depends-on.*")
	public void rejectsScopedRuleDependency() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/direct.md", "Direct guidance\n");
			write(plugin, "rules/common/scoped.md", "---\npaths: [\"**/*.java\"]\ndepends-on: [\"./direct.md\"]\n" +
				"---\nScoped guidance\n");

			try (ToolScope scope = new TestToolScope())
			{
				new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "harnessa");
			}
		}
	}

	/**
	 * Verifies the loader rejects a frontmatter key that rules do not define, such as a misspelled dependency key,
	 * instead of ignoring the guidance it declares.
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*Unknown frontmatter key depends_on.*supports agents, depends-on and paths.*")
	public void rejectsUnknownFrontmatterKey() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/direct.md", "Direct guidance\n");
			write(plugin, "rules/common/dependent.md", "---\ndepends_on: [\"./direct.md\"]\n---\n" +
				"Dependent guidance\n");

			try (ToolScope scope = new TestToolScope())
			{
				new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "harnessa");
			}
		}
	}

	/**
	 * Verifies the loader rejects a circular dependency before it renders any rule context.
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*Cyclic rule dependency.*")
	public void rejectsCyclicDependency() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/first.md", "---\ndepends-on: [\"./second.md\"]\n---\n" +
				"First guidance\n");
			write(plugin, "rules/common/second.md", "---\ndepends-on: [\"./first.md\"]\n---\n" +
				"Second guidance\n");

			try (ToolScope scope = new TestToolScope())
			{
				new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "harnessa");
			}
		}
	}

	/**
	 * Verifies a dependency cannot leave the rule tree that contains its declaring rule.
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*leaves its source root.*")
	public void rejectsOutsideRootDependency() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/dependent.md", "---\ndepends-on: [\"../../.cat/rules/common/terms.md\"]\n" +
				"---\nDependent guidance\n");

			try (ToolScope scope = new TestToolScope())
			{
				new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "harnessa");
			}
		}
	}

	/**
	 * Verifies user-owned rules are injected between bundled and repository rules without requiring a repository change.
	 */
	@Test
	public void loadsUserRulesOutsideTheProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path userRules = cleanup.add(Files.createTempDirectory("cat-user-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path userLazyRule = userRules.resolve("include/common/terms.md");
			write(plugin, "rules/common/rule.md", "bundled rule");
			write(userRules, "common/rule-routing.md", "user rule: lazy load `../include/common/terms.md`");
			write(userRules, "include/common/terms.md", "user terms");
			write(project, ".cat/rules/common/rule.md", "project rule");

			String context = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, userRules, project, "harnessa");

			requireThat(context, "context").contains("bundled rule").contains("user rule").contains("project rule").
				contains("`" + userLazyRule + "`").doesNotContain("user terms");
			requireThat(context.indexOf("bundled rule"), "bundledIndex").isLessThan(context.indexOf("user rule"));
			requireThat(context.indexOf("user rule"), "userIndex").isLessThan(context.indexOf("project rule"));
		}
	}

	/**
	 * Verifies that the sources of a primary agent's rules are, once each: every rule that the loader injects or routes
	 * to, then every file that a listed file includes or refers to, and every prerequisite of a listed included rule.
	 * Rules for other harnesses and other recipients, files that no listed file names, and references that leave the
	 * rule roots or name no file are left out.
	 *
	 * @throws IOException if the rules cannot be written or read
	 */
	@Test
	public void listsSourcesForMainAgent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path userRules = cleanup.add(Files.createTempDirectory("cat-user-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/default.md", "<!-- cat:include ../include/common/body.md -->\n\n" +
				"Load `${CAT_PLUGIN_ROOT}/rules/include/common/lazy.md`.");
			write(plugin, "rules/include/common/body.md", "Before drafting, load `write.md`.");
			write(plugin, "rules/include/common/write.md", "Write clearly. See `lazy.md`.");
			write(plugin, "rules/include/common/lazy.md", "Lazy guidance");
			write(plugin, "rules/include/common/unused.md", "Never named");
			write(plugin, "rules/common/subagent.md", "---\nagents: [subagents]\n---\nSubagent rule");
			write(plugin, "rules/harnessa/path.md", "---\npaths: [\"*.java\"]\n---\nJava rule");
			write(plugin, "rules/harnessb/other.md", "Other harness");
			write(userRules, "common/personal.md", "Load `../include/common/detail.md`.");
			write(userRules, "include/common/detail.md", "---\ndepends-on: [prerequisite.md]\n---\nDetail");
			write(userRules, "include/common/prerequisite.md", "Prerequisite");
			write(project, ".cat/rules/common/project.md", "Project rule. See `missing.md` and `" +
				plugin.resolve("rules/include/common/unused.md") + "`.");

			List<RuleSource> sources = new ProjectRuleLoader(scope.yaml()).sourcesForMainAgent(plugin, userRules,
				project, "harnessa");

			Path defaultRule = plugin.resolve("rules/common/default.md");
			Path body = plugin.resolve("rules/include/common/body.md");
			Path personal = userRules.resolve("common/personal.md");
			Path detail = userRules.resolve("include/common/detail.md");
			requireThat(sources, "sources").isEqualTo(List.of(
				new RuleSource(defaultRule, RuleSource.Kind.INJECTED, Optional.empty()),
				new RuleSource(plugin.resolve("rules/harnessa/path.md"), RuleSource.Kind.ROUTED, Optional.empty()),
				new RuleSource(personal, RuleSource.Kind.INJECTED, Optional.empty()),
				new RuleSource(project.resolve(".cat/rules/common/project.md"), RuleSource.Kind.INJECTED,
					Optional.empty()),
				new RuleSource(body, RuleSource.Kind.INCLUDED, Optional.of(defaultRule)),
				new RuleSource(plugin.resolve("rules/include/common/lazy.md"), RuleSource.Kind.REFERENCED,
					Optional.of(defaultRule)),
				new RuleSource(detail, RuleSource.Kind.REFERENCED, Optional.of(personal)),
				new RuleSource(plugin.resolve("rules/include/common/write.md"), RuleSource.Kind.REFERENCED,
					Optional.of(body)),
				new RuleSource(userRules.resolve("include/common/prerequisite.md"), RuleSource.Kind.PREREQUISITE,
					Optional.of(detail))));
		}
	}

	/**
	 * Verifies that a project rule's reference through the project directory lists the project rule that it names,
	 * and that such a reference to a project file outside the project's rules is left out.
	 *
	 * @throws IOException if the rules cannot be written or read
	 */
	@Test
	public void listsProjectRuleNamedThroughProjectRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-")).toAbsolutePath().normalize();
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-")).toAbsolutePath().normalize();
			write(project, ".cat/rules/common/routing.md", "Before building, load " +
				"`${CAT_PROJECT_ROOT}/.cat/rules/include/build.md`. See `${CAT_PROJECT_ROOT}/docs/guide.md`.");
			write(project, ".cat/rules/include/build.md", "Build guidance");
			write(project, "docs/guide.md", "Guide");

			List<RuleSource> sources = new ProjectRuleLoader(scope.yaml()).sourcesForMainAgent(plugin, null,
				project, "harnessa");

			Path routing = project.resolve(".cat/rules/common/routing.md");
			requireThat(sources, "sources").isEqualTo(List.of(
				new RuleSource(routing, RuleSource.Kind.INJECTED, Optional.empty()),
				new RuleSource(project.resolve(".cat/rules/include/build.md"), RuleSource.Kind.REFERENCED,
					Optional.of(routing))));
		}
	}

	/**
	 * Verifies that a user rule's reference through the user rule directory lists the user rule that it names.
	 *
	 * @throws IOException if the rules cannot be written or read
	 */
	@Test
	public void listsUserRuleNamedThroughUserRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-")).toAbsolutePath().normalize();
			Path userRules = cleanup.add(Files.createTempDirectory("cat-user-rules-")).toAbsolutePath().normalize();
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-")).toAbsolutePath().normalize();
			write(userRules, "common/personal.md", "Before reviewing, load `${CAT_USER_RULES}/include/x.md`.");
			write(userRules, "include/x.md", "Review guidance");

			List<RuleSource> sources = new ProjectRuleLoader(scope.yaml()).sourcesForMainAgent(plugin, userRules,
				project, "harnessa");

			Path personal = userRules.resolve("common/personal.md");
			requireThat(sources, "sources").isEqualTo(List.of(
				new RuleSource(personal, RuleSource.Kind.INJECTED, Optional.empty()),
				new RuleSource(userRules.resolve("include/x.md"), RuleSource.Kind.REFERENCED, Optional.of(personal))));
		}
	}

	/**
	 * Writes a UTF-8 rule below a root directory.
	 *
	 * @param root the rule root
	 * @param relativePath the relative rule path
	 * @param content the rule content
	 * @throws IOException if the rule cannot be written
	 */
	private static void write(Path root, String relativePath, String content) throws IOException
	{
		requireThat(root, "root").isNotNull();
		requireThat(relativePath, "relativePath").isNotBlank();
		requireThat(content, "content").isNotNull();

		Path file = root.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}
}
