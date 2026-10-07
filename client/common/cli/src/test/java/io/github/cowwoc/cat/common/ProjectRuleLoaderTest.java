package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
			try (ToolScope scope = new ToolScope())
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
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
	public void loadsDefaultRoutesFirst() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/write-clearly.md", "# Write clearly\n\nWrite-clearly guidance\n");
			write(plugin, "rules/common/default-rules.md", "# Routes\n\nRoute guidance\n");

			String context;
			try (ToolScope scope = new ToolScope())
			{
				context = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "harnessa");
			}

			requireThat(context.indexOf("# Routes"), "routeIndex").isLessThan(context.indexOf("# Write clearly"));
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
			write(plugin, "rules/common/default-rules.md", "# Routes\n\nRoute guidance\n");
			write(plugin, "rules/harnessa/cat-env.md", "# CAT environment\n\nPlugin directory guidance\n");
			write(project, ".cat/rules/common/project.md", "# Project\n\nProject guidance\n");

			String context;
			try (ToolScope scope = new ToolScope())
			{
				context = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "harnessa");
			}

			requireThat(context, "context").startsWith("# CAT environment").contains("# Routes").
				contains("# Project");
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
			try (ToolScope scope = new ToolScope())
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
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
			write(plugin, "rules/common/default-rules.md", "Before editing Java, lazy load `../include/java/javadoc.md`.\n");
			write(plugin, "rules/include/java/javadoc.md", "Javadoc guidance\n");

			String context;
			try (ToolScope scope = new ToolScope())
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
			try (ToolScope scope = new ToolScope())
			{
				context = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, project, "harnessa");
			}

			requireThat(context, "context").contains("# Direct").contains("Shared guidance").
				contains("Direct guidance").doesNotContain("cat:include").doesNotContain("## Design Goals");
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
			try (ToolScope scope = new ToolScope())
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
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
	 * Verifies project and user rules receive the plugin-root directive unchanged, because the plugin root is not
	 * theirs to state.
	 */
	@Test
	public void keepsPluginRootDirectiveOutsidePlugin() throws IOException
	{
		// Requirement: R-I5
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
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

			try (ToolScope scope = new ToolScope())
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

			try (ToolScope scope = new ToolScope())
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

			try (ToolScope scope = new ToolScope())
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

			try (ToolScope scope = new ToolScope())
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

			try (ToolScope scope = new ToolScope())
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

			try (ToolScope scope = new ToolScope())
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path userRules = cleanup.add(Files.createTempDirectory("cat-user-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path userLazyRule = userRules.resolve("include/common/terms.md");
			write(plugin, "rules/common/rule.md", "bundled rule");
			write(userRules, "common/default-rules.md", "user rule: lazy load `../include/common/terms.md`");
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
