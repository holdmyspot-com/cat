package com.holdmyspot.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.distribution.bundle.Launcher;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests the launchers through which each plugin runs either harness, {@code claude-runner} and {@code codex-runner},
 * and the bundled rule that tells agents how to run them. Each plugin finds the other harness's artifact with a
 * launcher of its own harness and runs the runner launcher in that artifact's {@code bin/}, so neither plugin holds the
 * other harness's classes.
 */
public final class RunnerLaunchersTest
{
	private static final String CLAUDE_MODULE = "com.holdmyspot.cat.claude.cli";
	private static final String CODEX_MODULE = "com.holdmyspot.cat.codex.cli";
	private static final String COMMON_MODULE = "com.holdmyspot.cat.common.cli";
	/**
	 * The bundled rule that tells agents how to run a harness, relative to the class path.
	 */
	private static final String HARNESS_RUNNERS_RULE = "rules/include/common/harness-runners.md";
	/**
	 * A launcher that a skill names in its plugin's {@code bin/}, whose first group is the launcher's name.
	 */
	private static final Pattern BIN_LAUNCHER = Pattern.compile(
		"(?<![A-Za-z0-9_.>/-])bin/([A-Za-z0-9][A-Za-z0-9._-]*)");

	/** Creates a test instance. */
	public RunnerLaunchersTest()
	{
	}

	/**
	 * Verifies that the Claude plugin's {@code bin/} holds {@code codex-artifact}, which finds the Codex artifact, and
	 * {@code claude-runner}, which the Codex plugin's {@code claude-runner} launcher runs.
	 */
	@Test
	public void claudePluginHoldsRunnerLaunchers()
	{
		// Requirement: R-I9, R-I11
		DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
		requireThat(access.claudeLaunchers(), "claudeLaunchers").
			contains(new Launcher("codex-artifact", CLAUDE_MODULE,
				"com.holdmyspot.cat.claude.runner.CodexArtifact")).
			contains(new Launcher("claude-runner", CLAUDE_MODULE,
				"com.holdmyspot.cat.claude.runner.ClaudeRunnerCli")).
			contains(new Launcher("request-session", "com.holdmyspot.cat.common.cli",
				"com.holdmyspot.cat.common.runner.SessionRequestCli"));
	}

	/**
	 * Verifies that the Codex plugin's {@code bin/} holds {@code claude-artifact}, which finds the Claude artifact, and
	 * {@code codex-runner}, which the Claude plugin's {@code codex-runner} launcher runs.
	 */
	@Test
	public void codexPluginHoldsRunnerLaunchers()
	{
		// Requirement: R-I9, R-I11
		DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
		requireThat(access.codexLaunchers(), "codexLaunchers").
			contains(new Launcher("claude-artifact", CODEX_MODULE,
				"com.holdmyspot.cat.codex.runner.ClaudeArtifact")).
			contains(new Launcher("codex-runner", CODEX_MODULE,
				"com.holdmyspot.cat.codex.runner.CodexRunnerCli")).
			contains(new Launcher("request-session", "com.holdmyspot.cat.common.cli",
				"com.holdmyspot.cat.common.runner.SessionRequestCli"));
	}

	/**
	 * Verifies that each plugin's {@code bin/} holds both runner launchers, so that a session of either harness, and a
	 * session that it requests, can run either harness through the same command.
	 */
	@Test
	public void bothPluginsHoldBothRunners()
	{
		// Requirement: R-I9, R-I11
		DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
		requireThat(access.claudeLaunchers(), "claudeLaunchers").
			contains(new Launcher("codex-runner", CLAUDE_MODULE,
				"com.holdmyspot.cat.claude.runner.CodexRunnerRelay"));
		requireThat(access.codexLaunchers(), "codexLaunchers").
			contains(new Launcher("claude-runner", CODEX_MODULE,
				"com.holdmyspot.cat.codex.runner.ClaudeRunnerRelay"));
	}

	/**
	 * Verifies that each plugin's {@code bin/} holds the {@code session-launcher} that its SessionStart hook starts,
	 * which serves the sessions that its harness's sessions request. The table is the same on every platform.
	 */
	@Test
	public void bothPluginsHoldSessionLauncher()
	{
		DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
		requireThat(access.claudeLaunchers(), "claudeLaunchers").
			contains(new Launcher("session-launcher", CLAUDE_MODULE,
				"com.holdmyspot.cat.claude.runner.ClaudeSessionLauncherCli"));
		requireThat(access.codexLaunchers(), "codexLaunchers").
			contains(new Launcher("session-launcher", CODEX_MODULE,
				"com.holdmyspot.cat.codex.runner.CodexSessionLauncherCli"));
	}

	/**
	 * Supplies each plugin's harness with the launchers that the plugin holds in {@code bin/}.
	 *
	 * @return the harness's name and its plugin's launchers
	 */
	@DataProvider
	public Object[][] plugins()
	{
		DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
		return new Object[][]{{"claude", access.claudeLaunchers()}, {"codex", access.codexLaunchers()}};
	}

	/**
	 * Verifies that following any skill that a plugin holds can run every launcher that it names: each
	 * {@code bin/<launcher>} named in the skill's manifest or in its shared body is in that plugin's {@code bin/}.
	 * Besides the launchers of its modules, every plugin's {@code bin/} holds the {@code installer} launcher.
	 *
	 * @param harness        the harness whose plugin to check
	 * @param imageLaunchers the launchers in the plugin's {@code bin/}
	 * @throws IOException        if a skill cannot be read
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test(dataProvider = "plugins")
	public void pluginHoldsEveryLauncherItsSkillsName(String harness, List<Launcher> imageLaunchers)
		throws IOException, URISyntaxException
	{
		Set<String> imageNames = new TreeSet<>(names(imageLaunchers));
		imageNames.add("installer");
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
				if (!imageNames.contains(launcher))
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
	 * Verifies that each plugin launches only its own harness's module or the common module.
	 */
	@Test
	public void imagesLaunchOnlyTheirOwnHarness()
	{
		DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
		for (Launcher launcher : access.claudeLaunchers())
			requireThat(List.of(CLAUDE_MODULE, COMMON_MODULE), "modules").contains(launcher.module(), launcher.name());
		for (Launcher launcher : access.codexLaunchers())
			requireThat(List.of(CODEX_MODULE, COMMON_MODULE), "modules").contains(launcher.module(), launcher.name());
	}

	/**
	 * Verifies that neither plugin offers a skill for running a harness, since agents follow the bundled rule instead.
	 *
	 * @throws IOException        if a skill tree cannot be read
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void noPluginOffersRunnerSkill() throws IOException, URISyntaxException
	{
		List<String> found = new ArrayList<>();
		for (String module : List.of("claude", "codex", "common"))
		{
			forEachSkillTree(module, tree ->
			{
				for (String skill : List.of("claude-runner", "codex-runner"))
				{
					if (Files.exists(tree.resolve(skill)))
						found.add(module + ": " + skill);
				}
			});
		}
		requireThat(found, "found").isEmpty();
	}

	/**
	 * Verifies that the bundled rule on running a harness names both runner launchers and both artifact selectors in
	 * the plugin's {@code bin/}, each of which both plugins hold; names their use inside a run; tells the agent where
	 * each turn's reply is in the transcript; and holds no shell syntax, since Codex runs commands without a shell.
	 *
	 * @throws IOException if the rule cannot be read
	 */
	@Test
	public void runnerRuleNamesLaunchersAndReplies() throws IOException
	{
		List<URL> rules = resources(HARNESS_RUNNERS_RULE);
		requireThat(rules, "rules").size().isEqualTo(1);
		String rule = read(rules.getFirst());
		DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
		for (String launcher : List.of("claude-runner", "claude-artifact", "codex-runner", "codex-artifact"))
		{
			requireThat(rule, "rule").contains("`${CAT_PLUGIN_ROOT}/bin/" + launcher + "`");
			requireThat(names(access.claudeLaunchers()), "claudeLaunchers").contains(launcher);
			requireThat(names(access.codexLaunchers()), "codexLaunchers").contains(launcher);
		}
		requireThat(rule, "rule").contains("CAT_SESSION_REQUESTS").contains("--input").contains("--request-depth").
			contains("--extra-permissions").contains("\"format_version\":1").
			contains("{\"type\":\"turn_completed\"").contains("assistant_response").contains("\"type\":\"turn_").
			doesNotContain("printf").doesNotContain(" | ").doesNotContain("$(").doesNotContain("```bash").
			doesNotContain("cannot start a harness");
	}

	/**
	 * Verifies that the bundled rule on running a harness has the agent decide whether it runs inside a run before any
	 * step that applies only outside one, says that inside a run the agent neither selects an artifact nor verifies one,
	 * and does not present the launcher that the session's instructions name as the only one that works there, since
	 * the plugin's runner launchers forward a launch inside a run as well.
	 *
	 * @throws IOException if the rule cannot be read
	 */
	@Test
	public void runnerRuleChecksForRunBeforeOutsideSteps() throws IOException
	{
		List<URL> rules = resources(HARNESS_RUNNERS_RULE);
		requireThat(rules, "rules").size().isEqualTo(1);
		String rule = read(rules.getFirst()).replaceAll("\\s+", " ");
		int check = rule.indexOf("First check whether the command environment sets `CAT_SESSION_REQUESTS`");
		requireThat(check, "check").isNotNegative();
		Map<String, String> outsideSteps = Map.of(
			"outsideSection", "### Outside a run",
			"artifactSelection", "selects. Run `<harness>-artifact` by its absolute path",
			"prerequisites", "Before a run, require all of these exact prerequisites",
			"artifactVerification", "`--verify-artifact <artifact>` succeeds");
		for (Map.Entry<String, String> step : outsideSteps.entrySet())
			requireThat(rule.indexOf(step.getValue()), step.getKey()).isGreaterThan(check);
		requireThat(rule, "rule").
			contains("Do not run `<harness>-artifact` or `--verify-artifact`, and do not check the prerequisites of " +
				"\"Outside a run\"").
			contains("the plugin's `<harness>-runner` behaves the same way inside a run").
			doesNotContain("instead of the plugin's");
	}

	/**
	 * Verifies that the bundled rule on running a harness has the agent write the records file that {@code --input}
	 * names before the launch, and says that {@code --input} takes that file rather than a prompt file, which only the
	 * {@code request-session} launcher takes.
	 *
	 * @throws IOException if the rule cannot be read
	 */
	@Test
	public void runnerRuleWritesRecordsBeforeLaunch() throws IOException
	{
		List<URL> rules = resources(HARNESS_RUNNERS_RULE);
		requireThat(rules, "rules").size().isEqualTo(1);
		String rule = read(rules.getFirst()).replaceAll("\\s+", " ");
		requireThat(rule, "rule").
			contains("Before you run it, write the run's records with a file-writing tool into a file").
			contains("`{\"type\":\"turn\",\"prompt\":\"<task>\"}`").
			contains("`--input` takes only that records file, never a prompt file; prompt files are for the " +
				"`request-session` launcher");
	}

	/**
	 * Verifies that the bundled rule on running a harness also covers a session request made with the
	 * {@code request-session} launcher: the request takes the model and the effort the same way, and its response names
	 * the transcript whose replies the agent reads.
	 *
	 * @throws IOException if the rule cannot be read
	 */
	@Test
	public void runnerRuleCoversSessionRequests() throws IOException
	{
		List<URL> rules = resources(HARNESS_RUNNERS_RULE);
		requireThat(rules, "rules").size().isEqualTo(1);
		requireThat(read(rules.getFirst()), "rule").contains("`request-session`").
			contains("Every run and every session request takes `--model <model>` and `--effort <effort>`").
			contains("`transcript` field");
	}

	/**
	 * Verifies that the bundled rule on running a harness says, before the launcher steps, that a runner is only for a
	 * task that needs a separate, isolated session, names the kinds of task that need one, and leaves whether any
	 * other task goes to a subagent to the agent's delegation rules instead of sending every such task to one.
	 *
	 * @throws IOException if the rule cannot be read
	 */
	@Test
	public void runnerRuleStartsOnlyIsolatedSessions() throws IOException
	{
		List<URL> rules = resources(HARNESS_RUNNERS_RULE);
		requireThat(rules, "rules").size().isEqualTo(1);
		String rule = read(rules.getFirst()).replaceAll("\\s+", " ");
		int isolation = rule.indexOf("only when the task needs a separate, isolated session");
		requireThat(isolation, "isolation").isNotNegative();
		requireThat(rule.indexOf("### Launcher"), "launcher").isGreaterThan(isolation);
		requireThat(rule, "rule").contains("runs in a harness other than yours").
			contains("tests a plugin or a fresh CAT installation").contains("starts from a fresh copy of the project").
			contains("must not see this session's context").
			contains("Do not start a runner for a task that needs no isolated session; whether you delegate it to a " +
				"subagent or do it yourself follows your delegation rules").
			doesNotContain("delegate it to that subagent instead");
	}

	/**
	 * Verifies that the bundled workflow routing loads the rule on running a harness, so that an agent that runs a
	 * runner launcher, or requests a session with the {@code request-session} launcher, reads it first, and that its
	 * condition names those launchers rather than any new Claude Code or Codex session.
	 *
	 * @throws IOException if the routing cannot be read
	 */
	@Test
	public void workflowRoutingLoadsRunnerRule() throws IOException
	{
		List<URL> routing = resources("rules/include/common/rule-routing/workflows.md");
		requireThat(routing, "routing").size().isEqualTo(1);
		String text = read(routing.getFirst()).replaceAll("\\s+", " ");
		requireThat(text, "workflows.md").
			contains("Before starting a session through CAT's `claude-runner` or `codex-runner` launcher").
			contains("`request-session`").contains("lazy load `../harness-runners.md`").
			doesNotContain("new Claude Code or Codex session");
	}

	/**
	 * Verifies that the always-loaded rule routing loads the workflow routing on starting a session through a runner
	 * or session-request launcher, and not on any new Claude Code or Codex session, which a subagent could be read
	 * as.
	 *
	 * @throws IOException if the routing cannot be read
	 */
	@Test
	public void ruleRoutingNamesRunnerLaunchers() throws IOException
	{
		List<URL> routing = resources("rules/common/rule-routing.md");
		requireThat(routing, "routing").size().isEqualTo(1);
		String text = read(routing.getFirst()).replaceAll("\\s+", " ");
		requireThat(text, "rule-routing.md").
			contains("starting a session through CAT's `claude-runner`, `codex-runner` or `request-session` launcher").
			doesNotContain("new Claude Code or Codex session");
	}

	/**
	 * Returns the names of launchers.
	 *
	 * @param launchers the launchers
	 * @return their names
	 */
	private static Set<String> names(List<Launcher> launchers)
	{
		Set<String> names = new TreeSet<>();
		for (Launcher launcher : launchers)
			names.add(launcher.name());
		return names;
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
		return new ArrayList<>(Collections.list(RunnerLaunchersTest.class.getClassLoader().getResources(name)));
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
