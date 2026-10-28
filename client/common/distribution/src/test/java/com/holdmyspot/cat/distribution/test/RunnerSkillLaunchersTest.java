package com.holdmyspot.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.distribution.bundle.JlinkImageBuilder;
import com.holdmyspot.cat.distribution.secrets.DistributionMainAccess;
import com.holdmyspot.cat.distribution.secrets.DistributionMainSecrets;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests the launchers and skills through which each plugin runs either harness: the {@code claude-runner} and
 * {@code codex-runner} skills and launchers. Each plugin finds the other harness's artifact with a launcher of its own
 * harness and runs the runner launcher in that artifact's {@code bin/}, so neither plugin holds the other harness's
 * classes.
 */
public final class RunnerSkillLaunchersTest
{
	private static final String CLAUDE_MODULE = "com.holdmyspot.cat.claude.cli";
	private static final String CODEX_MODULE = "com.holdmyspot.cat.codex.cli";
	private static final String COMMON_MODULE = "com.holdmyspot.cat.common.cli";
	/**
	 * A launcher that a skill names in its plugin's {@code bin/}, whose first group is the launcher's name.
	 */
	private static final Pattern BIN_LAUNCHER = Pattern.compile(
		"(?<![A-Za-z0-9_.>/-])bin/([A-Za-z0-9][A-Za-z0-9._-]*)");

	/** Creates a test instance. */
	public RunnerSkillLaunchersTest()
	{
	}

	/**
	 * Verifies that the Claude plugin's {@code bin/} holds {@code codex-artifact}, which finds the Codex artifact, and
	 * {@code claude-runner}, which the Codex plugin's skill runs.
	 */
	@Test
	public void claudePluginHoldsRunnerLaunchers()
	{
		// Requirement: R-I9
		DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
		requireThat(access.claudeLaunchers(), "claudeLaunchers").
			contains(new JlinkImageBuilder.Launcher("codex-artifact", CLAUDE_MODULE,
				"com.holdmyspot.cat.claude.runner.CodexArtifact")).
			contains(new JlinkImageBuilder.Launcher("claude-runner", CLAUDE_MODULE,
				"com.holdmyspot.cat.claude.runner.ClaudeRunnerCli")).
			contains(new JlinkImageBuilder.Launcher("request-session", "com.holdmyspot.cat.common.cli",
				"com.holdmyspot.cat.common.runner.SessionRequestCli"));
		requireThat(access.claudeAgentLaunchers(), "claudeAgentLaunchers").contains("codex-artifact").
			contains("claude-runner").contains("request-session");
	}

	/**
	 * Verifies that the Codex plugin's {@code bin/} holds {@code claude-artifact}, which finds the Claude artifact, and
	 * {@code codex-runner}, which the Claude plugin's skill runs.
	 */
	@Test
	public void codexPluginHoldsRunnerLaunchers()
	{
		// Requirement: R-I9
		DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
		requireThat(access.codexLaunchers(), "codexLaunchers").
			contains(new JlinkImageBuilder.Launcher("claude-artifact", CODEX_MODULE,
				"com.holdmyspot.cat.codex.runner.ClaudeArtifact")).
			contains(new JlinkImageBuilder.Launcher("codex-runner", CODEX_MODULE,
				"com.holdmyspot.cat.codex.runner.CodexRunnerCli")).
			contains(new JlinkImageBuilder.Launcher("request-session", "com.holdmyspot.cat.common.cli",
				"com.holdmyspot.cat.common.runner.SessionRequestCli"));
		requireThat(access.codexAgentLaunchers(), "codexAgentLaunchers").contains("claude-artifact").
			contains("codex-runner").contains("request-session");
	}

	/**
	 * Verifies that each plugin's {@code bin/} holds both runner launchers, so that a session of either harness, and a
	 * session that it requests, can run either harness through the same command.
	 */
	@Test
	public void bothPluginsHoldBothRunners()
	{
		// Requirement: R-I9
		DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
		requireThat(access.claudeLaunchers(), "claudeLaunchers").
			contains(new JlinkImageBuilder.Launcher("codex-runner", CLAUDE_MODULE,
				"com.holdmyspot.cat.claude.runner.CodexRunnerRelay"));
		requireThat(access.codexLaunchers(), "codexLaunchers").
			contains(new JlinkImageBuilder.Launcher("claude-runner", CODEX_MODULE,
				"com.holdmyspot.cat.codex.runner.ClaudeRunnerRelay"));
		requireThat(access.claudeAgentLaunchers(), "claudeAgentLaunchers").contains("claude-runner").
			contains("codex-runner");
		requireThat(access.codexAgentLaunchers(), "codexAgentLaunchers").contains("claude-runner").
			contains("codex-runner");
	}

	/**
	 * Supplies each plugin's harness with the launchers that the plugin holds in {@code bin/} and the launchers of
	 * its image.
	 *
	 * @return the harness's name, its plugin's launchers' names in {@code bin/}, and its image's launchers
	 */
	@DataProvider
	public Object[][] plugins()
	{
		DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
		return new Object[][]{{"claude", access.claudeAgentLaunchers(), access.claudeLaunchers()},
			{"codex", access.codexAgentLaunchers(), access.codexLaunchers()}};
	}

	/**
	 * Verifies that following any skill that a plugin holds can run every launcher that it names: each
	 * {@code bin/<launcher>} named in the skill's manifest or in its shared body is in that plugin's {@code bin/}.
	 *
	 * @param harness        the harness whose plugin to check
	 * @param agentLaunchers the launchers in the plugin's {@code bin/}
	 * @param imageLaunchers the launchers of the plugin's image
	 * @throws IOException        if a skill cannot be read
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test(dataProvider = "plugins")
	public void pluginHoldsEveryLauncherItsSkillsName(String harness, Set<String> agentLaunchers,
		List<JlinkImageBuilder.Launcher> imageLaunchers) throws IOException, URISyntaxException
	{
		Set<String> imageNames = new TreeSet<>();
		for (JlinkImageBuilder.Launcher launcher : imageLaunchers)
			imageNames.add(launcher.name());
		List<String> missing = new ArrayList<>();
		List<String> skills = new ArrayList<>();
		forEachSkillTree(harness, harnessSkills ->
		{
			try (Stream<Path> entries = Files.list(harnessSkills))
			{
				// A directory's name in a JAR file system ends with "/"
				for (Path skill : entries.filter(Files::isDirectory).toList())
					skills.add(skill.getFileName().toString().replace("/", ""));
			}
		});
		requireThat(skills, "skills").isNotEmpty();
		for (String skill : skills)
		{
			Set<String> named = new TreeSet<>();
			forEachSkillTree(harness, tree -> collectLaunchers(tree.resolve(skill), named));
			forEachSkillTree("common", tree -> collectLaunchers(tree.resolve(skill), named));
			for (String launcher : named)
			{
				if (!agentLaunchers.contains(launcher) || !imageNames.contains(launcher))
					missing.add(skill + " names bin/" + launcher);
			}
		}
		requireThat(missing, "missing").withContext(harness, "harness").isEmpty();
	}

	/**
	 * Adds the launchers that the Markdown files of one skill name in {@code bin/}.
	 *
	 * @param skill the skill's directory, which need not exist
	 * @param named receives the launchers' names
	 * @throws IOException if a file cannot be read
	 */
	private static void collectLaunchers(Path skill, Set<String> named) throws IOException
	{
		if (!Files.isDirectory(skill))
			return;
		try (Stream<Path> files = Files.walk(skill))
		{
			for (Path file : files.filter(path -> path.toString().endsWith(".md")).toList())
			{
				Matcher matcher = BIN_LAUNCHER.matcher(Files.readString(file));
				while (matcher.find())
					named.add(matcher.group(1));
			}
		}
	}

	/**
	 * Visits a module's {@code skills} tree on the class path.
	 */
	@FunctionalInterface
	private interface SkillTreeVisitor
	{
		/**
		 * Visits the tree.
		 *
		 * @param skills the {@code skills} directory
		 * @throws IOException if the tree cannot be read
		 */
		void visit(Path skills) throws IOException;
	}

	/**
	 * Visits the {@code skills} tree of one skill module on the class path, whether a directory or a JAR.
	 *
	 * @param module  the module's harness, {@code claude}, {@code codex} or {@code common}
	 * @param visitor the visitor
	 * @throws IOException        if the tree cannot be read
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	private static void forEachSkillTree(String module, SkillTreeVisitor visitor) throws IOException,
		URISyntaxException
	{
		int visited = 0;
		for (URL url : resources("skills"))
		{
			if (!url.toString().matches(".*" + module + "[-/]skills.*"))
				continue;
			++visited;
			URI root = url.toURI();
			if (!root.getScheme().equals("jar"))
			{
				visitor.visit(Path.of(root));
				continue;
			}
			String location = root.getSchemeSpecificPart();
			Path jar = Path.of(new URI(location.substring(0, location.indexOf("!/"))));
			try (FileSystem contents = FileSystems.newFileSystem(jar))
			{
				visitor.visit(contents.getPath("/skills"));
			}
		}
		requireThat(visited, "visited").withContext(module, "module").isEqualTo(1);
	}

	/**
	 * Verifies that each image launches only its own harness's module or the common module.
	 */
	@Test
	public void imagesLaunchOnlyTheirOwnHarness()
	{
		DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
		for (JlinkImageBuilder.Launcher launcher : access.claudeLaunchers())
			requireThat(List.of(CLAUDE_MODULE, COMMON_MODULE), "modules").contains(launcher.module(), launcher.name());
		for (JlinkImageBuilder.Launcher launcher : access.codexLaunchers())
			requireThat(List.of(CODEX_MODULE, COMMON_MODULE), "modules").contains(launcher.module(), launcher.name());
	}

	/**
	 * Supplies each runner skill with the launcher that selects its artifact.
	 *
	 * @return the skill's name and its artifact selector
	 */
	@DataProvider
	public Object[][] runnerSkills()
	{
		return new Object[][]{{"claude-runner", "claude-artifact"}, {"codex-runner", "codex-artifact"}};
	}

	/**
	 * Verifies that both harnesses hold both runner skills over one shared body, which names the plugin's runner
	 * launcher, the launcher that selects the artifact, and the use of the launcher inside a run, and holds no shell
	 * syntax, since Codex runs commands without a shell.
	 *
	 * @param skill    the skill's name
	 * @param selector the launcher that selects the skill's artifact
	 * @throws IOException if a skill cannot be read
	 */
	@Test(dataProvider = "runnerSkills")
	public void bothHarnessesHoldRunnerSkill(String skill, String selector) throws IOException
	{
		// Requirement: R-I9
		List<URL> bodies = resources("skills/" + skill + "/first-use.md");
		requireThat(bodies, "bodies").size().isEqualTo(1);
		requireThat(bodies.getFirst().toString(), "body").matches(".*common[-/]skills.*");
		List<String> manifests = new ArrayList<>();
		for (URL manifest : resources("skills/" + skill + "/SKILL.md"))
			manifests.add(manifest.toString().replaceAll(".*(claude|codex)[-/]skills.*", "$1"));
		requireThat(manifests, "manifests").containsExactly(List.of("claude", "codex"));
		String body = read(bodies.getFirst());
		requireThat(body, "first-use.md").contains("bin/" + skill).contains("bin/" + selector).
			contains("CAT_SESSION_REQUESTS").contains("--input").contains("--request-depth").
			doesNotContain("printf").doesNotContain(" | ").doesNotContain("$(").doesNotContain("```bash").
			doesNotContain("cannot start a harness");
	}

	/**
	 * Returns every class path location of a resource.
	 *
	 * @param name the resource's name
	 * @return the locations
	 * @throws IOException if the class path cannot be read
	 */
	private static List<URL> resources(String name) throws IOException
	{
		return new ArrayList<>(Collections.list(RunnerSkillLaunchersTest.class.getClassLoader().getResources(name)));
	}

	/**
	 * Reads a resource.
	 *
	 * @param resource the resource's location
	 * @return its content
	 * @throws IOException if it cannot be read
	 */
	private static String read(URL resource) throws IOException
	{
		try (InputStream in = resource.openStream())
		{
			return new String(in.readAllBytes(), UTF_8);
		}
	}
}
