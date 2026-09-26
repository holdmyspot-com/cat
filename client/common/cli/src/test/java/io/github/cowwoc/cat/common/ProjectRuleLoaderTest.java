package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
	 * Identifies packaged direct and included content, excluding routes and user or project rules.
	 *
	 * @throws IOException if test-owned rules cannot be loaded
	 */
	@Test
	public void identifiesDeliveredPluginRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-delivery-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-delivery-"));
			write(plugin, "rules/common/direct.md", "Direct <!-- cat:include ../include/common/body.md -->");
			write(plugin, "rules/include/common/body.md", "Included content");
			write(plugin, "rules/common/lazy.md", "---\npaths: [\"*.java\"]\n---\nLazy content");
			write(plugin, "rules/common/.cat-lazy-rule-dependencies.md", "Generated dependency routes");
			write(plugin, "rules/common/.cat-path-scoped-rules.md", "Generated path routes");
			write(project, ".cat/rules/common/project.md", "Project content");

			ProjectRuleLoader.LoadedRules loaded = new ProjectRuleLoader(scope.yaml()).
				loadForMainAgentWithDelivery(plugin, null, project, "codex");

			requireThat(loaded.context(), "context").contains("Direct Included content").
				contains("Load `rules/common/lazy.md`").contains("Project content").
				contains("Generated dependency routes").contains("Generated path routes").
				doesNotContain("Lazy content");
			requireThat(loaded.deliveredPluginRuleIds(), "deliveredRules").isEqualTo(
				List.of("common/direct.md", "include/common/body.md"));
		}
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
				contains("Load `.cat/rules/harnessa/path.md` only when an accessed file matches [*.java].").
				doesNotContain("## Design Goals").doesNotContain("### CAT rule:").
				doesNotContain("### CAT path-scoped rule:").doesNotContain("plugin harnessb").
				doesNotContain("project harnessb").doesNotContain("project Java rule");
			requireThat(context, "context").contains("plugin common\n\n---\n# Plugin Harness");
			requireThat(Files.readString(plugin.resolve("rules/common/common.md")), "sourceRule").
			contains("## Design Goals");
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
	 * Verifies direct bundled rules provide installed paths for their lazy included-rule references after source-location
	 * information has been removed from injected context.
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

			requireThat(context, "context").contains("`" + lazyRule + "`").
				doesNotContain("`../include/java/javadoc.md`");
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
