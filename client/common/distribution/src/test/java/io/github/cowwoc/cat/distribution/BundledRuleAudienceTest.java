package io.github.cowwoc.cat.distribution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.that;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import io.github.cowwoc.cat.common.ProjectRuleLoader;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.distribution.bundle.RuleArtifactRouter;
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
			copyBundledRules(plugin.resolve("rules"));
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
	 * Verifies Codex agents receive the read-file rule at startup with its script command naming the
	 * installed plugin's {@code client/bin/read-file}, while Claude agents, which do not ship that script, do not receive
	 * the rule.
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
			copyBundledRules(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml());
			String command = "tools.exec_command({cmd: \"" + plugin.toAbsolutePath().normalize() +
				"/client/bin/read-file <path>\"})";

			for (String codex : List.of(loader.loadForMainAgent(plugin, project, "codex"),
				loader.loadForSubagent(plugin, project, "codex", "cat:reviewer")))
			{
				requireThat(codex, "codex").contains("# Reading Files").contains(command).
					doesNotContain(ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE + "/client/bin/read-file");
			}
			requireThat(loader.loadForMainAgent(plugin, project, "claude"), "claude").
				doesNotContain("# Reading Files");
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

	/**
	 * Copies every bundled rule tree on the test class path into one plugin rule directory.
	 *
	 * @param target the plugin's {@code rules} directory
	 * @throws IOException if a rule tree cannot be read or copied
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	private static void copyBundledRules(Path target) throws IOException, URISyntaxException
	{
		assert that(target, "target").isNotNull().elseThrow();
		Enumeration<URL> roots = BundledRuleAudienceTest.class.getClassLoader().getResources("rules");
		requireThat(roots.hasMoreElements(), "hasBundledRules").isTrue();
		while (roots.hasMoreElements())
		{
			URI root = roots.nextElement().toURI();
			if (!root.getScheme().equals("jar"))
			{
				copyTree(Path.of(root), target);
				continue;
			}
			String location = root.getSchemeSpecificPart();
			Path jar = Path.of(new URI(location.substring(0, location.indexOf("!/"))));
			try (FileSystem contents = FileSystems.newFileSystem(jar))
			{
				copyTree(contents.getPath("/rules"), target);
			}
		}
	}

	/**
	 * Copies the regular files below one directory, preserving their relative paths.
	 *
	 * @param source the directory to copy from
	 * @param target the directory to copy into
	 * @throws IOException if a file cannot be read or written
	 */
	private static void copyTree(Path source, Path target) throws IOException
	{
		assert that(source, "source").isNotNull().elseThrow();
		assert that(target, "target").isNotNull().elseThrow();
		try (Stream<Path> files = Files.walk(source))
		{
			for (Path file : files.filter(Files::isRegularFile).toList())
			{
				Path destination = target.resolve(source.relativize(file).toString());
				Files.createDirectories(destination.getParent());
				Files.copy(file, destination);
			}
		}
	}
}
