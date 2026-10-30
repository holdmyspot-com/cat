package com.holdmyspot.cat.codex.runner.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.runner.CodexRunnerCli;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessCapacityWaitExpiredException;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import com.holdmyspot.cat.common.fakes.test.Yielding;

/**
 * Tests {@link CodexRunnerCli}.
 */
public final class CodexRunnerCliTest
{
	/**
	 * A Codex thread ID in canonical form.
	 */
	private static final String THREAD_ID = "01a147d8-bf56-7283-92b4-12dcaeb53738";
	/**
	 * The name of the CAT agent definition in a test artifact.
	 */
	private static final String AGENT = "probe-agent";

	/**
	 * Verifies that in a Codex thread a runner command keeps its runs in the temporary directory of the thread's CAT
	 * session directory in the nearest CAT project.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void defaultsRunRootToSessionTemp() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("codex-runner-cli-"));
			Files.createDirectories(project.resolve(".cat"));
			Path folder = Files.createDirectories(project.resolve("src"));

			try (ToolScope scope = CodexRunnerCli.toolScope(folder, Map.of("CODEX_THREAD_ID", THREAD_ID,
				"CODEX_HOME", project.resolve("codex-home").toString()), Yielding.waiting()))
			{
				HarnessRunnerOptions options = HarnessRunnerOptions.parse(new String[]{"--artifact",
					project.resolve("artifact").toAbsolutePath().toString(), "--model", "gpt-6-luna", "--effort", "low"},
					"codex", HarnessTemporaryRoot.from(scope));

				Path sessionTemp = project.toAbsolutePath().normalize().resolve(".cat/sessions").resolve(THREAD_ID).
					resolve("temp");
				requireThat(options.runRoot().getParent(), "runRootParent").isEqualTo(sessionTemp);
				requireThat(sessionTemp.resolveSibling("session.json"), "marker").isRegularFile();
			}
		}
	}

	/**
	 * Verifies that without {@code CODEX_THREAD_ID} a runner command must name {@code --run-root}, and that the refusal
	 * names the variable.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void requiresRunRootWithoutThreadId() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("codex-runner-cli-"));
			Files.createDirectories(project.resolve(".cat"));
			String[] args = {"--artifact", project.resolve("artifact").toAbsolutePath().toString(), "--model",
				"gpt-6-luna", "--effort", "low"};
			try (ToolScope scope = CodexRunnerCli.toolScope(project, Map.of(), Yielding.waiting()))
			{
				HarnessRunnerOptions.parse(args, "codex", HarnessTemporaryRoot.from(scope));
				throw new AssertionError("A command without a thread or --run-root chose a run root");
			}
			catch (IllegalArgumentException e)
			{
				requireThat(e.getMessage(), "message").contains("--run-root").contains("CODEX_THREAD_ID");
			}
			requireThat(Files.exists(project.resolve(".cat/sessions")), "sessionsRootExists").isFalse();
		}
	}

	/**
	 * Verifies the bridge command reads the session's tool-output token limit, and that a command without one keeps
	 * Codex's default.
	 */
	@Test
	public void readsTheToolOutputTokenLimit()
	{
		requireThat(CodexRunnerCli.toolOutputTokenLimit(new String[]{"serve", "--artifact", "/artifact",
			"--tool-output-token-limit", "5000", "--keep-run"}), "limited").isEqualTo(5000);
		requireThat(CodexRunnerCli.toolOutputTokenLimit(new String[]{"serve", "--artifact", "/artifact"}),
			"unlimited").isEqualTo(0);
	}

	/**
	 * Returns tool-output token limits that are missing or not a positive number.
	 *
	 * @return the bridge commands
	 */
	@DataProvider
	public Object[][] invalidLimits()
	{
		return new Object[][]{{new String[]{"serve", "--tool-output-token-limit"}},
			{new String[]{"serve", "--tool-output-token-limit", "--keep-run"}},
			{new String[]{"serve", "--tool-output-token-limit", "0"}},
			{new String[]{"serve", "--tool-output-token-limit", "many"}}};
	}

	/**
	 * Verifies a tool-output token limit that is missing or not a positive number is refused.
	 *
	 * @param args the bridge command
	 */
	@Test(dataProvider = "invalidLimits", expectedExceptions = IllegalArgumentException.class)
	public void refusesAnInvalidToolOutputTokenLimit(String[] args)
	{
		CodexRunnerCli.toolOutputTokenLimit(args);
	}

	/**
	 * Verifies that each {@code --read-only-evidence} option of a bridge command names one path that the session may
	 * read but not change, in order.
	 *
	 * @throws IOException if the options cannot be parsed
	 */
	@Test
	public void parsesReadOnlyEvidence() throws IOException
	{
		Path effects = Path.of("/runs/trial/project").toAbsolutePath();
		Path artifacts = Path.of("/runs/trial/artifacts").toAbsolutePath();
		HarnessRunnerOptions options = CodexRunnerCli.options(new String[]{"serve", "--artifact",
			Path.of("/artifact").toAbsolutePath().toString(), "--run-root", Path.of("/runs").toAbsolutePath().toString(),
			"--read-only-evidence", effects.toString(), "--read-only-evidence",
			artifacts.toString(), "--model", "gpt-6-luna", "--effort", "low"}, HarnessTemporaryRoot.withoutSession(
			"no session"));

		requireThat(options.readOnlyEvidence(), "readOnlyEvidence").isEqualTo(List.of(effects, artifacts));
	}

	/**
	 * Returns bridge commands whose {@code --read-only-evidence} names no path.
	 *
	 * @return the bridge commands
	 */
	@DataProvider
	public Object[][] missingEvidence()
	{
		String artifact = Path.of("/artifact").toAbsolutePath().toString();
		String runRoot = Path.of("/runs").toAbsolutePath().toString();
		return new Object[][]{{new String[]{"serve", "--artifact", artifact, "--run-root", runRoot,
			"--read-only-evidence"}}, {new String[]{"serve", "--artifact", artifact, "--run-root", runRoot,
			"--read-only-evidence", "--keep-run"}}};
	}

	/**
	 * Verifies that a bridge command whose {@code --read-only-evidence} names no path is refused with the message
	 * that the Claude runner gives.
	 *
	 * @param args the bridge command
	 * @throws IOException if the options cannot be parsed
	 */
	@Test(dataProvider = "missingEvidence", expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "Missing path after --read-only-evidence")
	public void refusesMissingEvidencePath(String[] args) throws IOException
	{
		CodexRunnerCli.options(args, HarnessTemporaryRoot.withoutSession("no session"));
	}

	/**
	 * Verifies that a relative {@code --read-only-evidence} path is refused, naming the path, because the session
	 * could resolve it against a directory other than the one that holds the evidence.
	 *
	 * @throws IOException if the options cannot be parsed
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*trial[/\\\\]project.*")
	public void refusesRelativeEvidencePath() throws IOException
	{
		CodexRunnerCli.options(new String[]{"serve", "--artifact", Path.of("/artifact").toAbsolutePath().toString(),
			"--run-root", Path.of("/runs").toAbsolutePath().toString(), "--read-only-evidence", "trial/project",
			"--model", "gpt-6-luna", "--effort", "low"},
			HarnessTemporaryRoot.withoutSession("no session"));
	}

	/**
	 * Verifies that a bridge given {@code --capacity-wait} gives up when no capacity frees within the wait, before it
	 * starts a session.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void capacityWaitExpires() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I9
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
				ManualClock clock = new ManualClock(Instant.parse("2026-10-23T12:00:00Z"));
				AtomicInteger pauses = new AtomicInteger();
				Pause second = () ->
				{
					if (pauses.incrementAndGet() > 100)
						throw new AssertionError("The capacity wait did not end");
					clock.advance(Duration.ofSeconds(1));
				};
				HarnessCapacity capacity = HarnessCapacity.of(jvm.fileLocks(),
					root.resolve("locks"), 1, second, clock);
				String[] args = {"serve", "--artifact", root.resolve("artifact").toString(), "--project",
					root.resolve("project").toString(), "--run-root", root.resolve("runs").toString(), "--capacity-wait",
					"5", "--model", "gpt-6-luna", "--effort", "low"};
				try (HarnessCapacity.Permit _ = capacity.acquire())
				{
					CodexRunnerCli.serve(jvm, root, args, new StringReader(""), new StringWriter(), Map.of(), _ -> capacity);
					throw new AssertionError("The bridge did not give up waiting for capacity");
				}
				catch (HarnessCapacityWaitExpiredException _)
				{
					requireThat(pauses.get(), "pauses").isEqualTo(5);
				}
			}
		}
	}

	/**
	 * Returns launches that lack a model, an effort or both, each with the start of the refusal.
	 *
	 * @return the profile options of each launch and the refusal's expected start
	 */
	@DataProvider
	public Object[][] incompleteProfiles()
	{
		return new Object[][]{{List.of("--effort", "low"), "Missing --model."},
			{List.of("--model", "gpt-6-luna"), "Missing --effort."},
			{List.of(), "Missing --model and --effort."}};
	}

	/**
	 * Verifies that a bridge launch without {@code --model} or {@code --effort} is refused with a message that names
	 * the missing option and where to list the supported values, before it takes a capacity slot or starts Codex.
	 *
	 * @param profile  the launch's profile options
	 * @param expected the start of the refusal
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(dataProvider = "incompleteProfiles")
	public void refusesLaunchWithoutProfile(List<String> profile, String expected) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I11
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
				List<String> args = new ArrayList<>(List.of("serve", "--artifact", root.resolve("artifact").toString(),
					"--project", root.resolve("project").toString(), "--run-root", root.resolve("runs").toString()));
				args.addAll(profile);
				try
				{
					// Taking capacity comes before starting Codex, so a refusal that takes none starts no session
					CodexRunnerCli.serve(jvm, root, args.toArray(String[]::new), new StringReader(""), new StringWriter(),
						Map.of(), _ ->
						{
							throw new AssertionError("The launch took a capacity slot");
						});
					throw new AssertionError("The launch was not refused");
				}
				catch (IllegalArgumentException e)
				{
					requireThat(e.getMessage(), "message").startsWith(expected).contains("`codex debug models`");
				}
				requireThat(Files.exists(root.resolve("runs")), "runRootExists").isFalse();
			}
		}
	}

	/**
	 * Verifies that a one-shot {@code run} without {@code --model} or {@code --effort} is refused, naming the missing
	 * option, before it takes a capacity slot or starts Codex.
	 *
	 * @param profile  the run's profile options
	 * @param expected the start of the refusal
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(dataProvider = "incompleteProfiles")
	public void refusesOneShotRunWithoutProfile(List<String> profile, String expected) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I11
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
				Path prompt = Files.writeString(root.resolve("prompt.txt"), "task");
				List<String> args = new ArrayList<>(List.of("run", "--artifact", root.resolve("artifact").toString(),
					"--project", root.resolve("project").toString(), "--run-root", root.resolve("runs").toString(),
					"--prompt-file", prompt.toString(), "--output", root.resolve("output").toString()));
				args.addAll(profile);
				HarnessCapacity capacity = HarnessCapacity.of(jvm.fileLocks(), root.resolve("locks"), 1, () ->
				{
					throw new AssertionError("The run waited for capacity");
				}, new ManualClock(Instant.parse("2026-10-30T12:00:00Z")));
				try (HarnessCapacity.Permit _ = capacity.acquire())
				{
					// The only slot is taken, so a run that tried to take one would wait and fail the test
					CodexRunnerCli.run(jvm, root, args.toArray(String[]::new), Map.of(), capacity);
					throw new AssertionError("The run was not refused");
				}
				catch (IllegalArgumentException e)
				{
					requireThat(e.getMessage(), "message").startsWith(expected).contains("`codex debug models`");
				}
				requireThat(Files.exists(root.resolve("runs")), "runRootExists").isFalse();
			}
		}
	}

	/**
	 * Returns the profile options that a launch naming {@code --agent} adds, for a bridge and a one-shot run.
	 *
	 * @return the command of each launch and its profile options
	 */
	@DataProvider
	public Object[][] profilesBesideAgent()
	{
		List<List<String>> profiles = List.of(List.of("--model", "gpt-6-luna"), List.of("--effort", "low"),
			List.of("--model", "gpt-6-luna", "--effort", "low"));
		List<Object[]> launches = new ArrayList<>();
		for (String command : List.of("serve", "run"))
			for (List<String> profile : profiles)
				launches.add(new Object[]{command, profile});
		return launches.toArray(Object[][]::new);
	}

	/**
	 * Verifies that a bridge or one-shot launch that names {@code --agent} together with {@code --model} or
	 * {@code --effort} is refused, because the agent definition supplies the profile, before it takes a capacity slot
	 * or starts Codex.
	 *
	 * @param command the launch's command, {@code serve} or {@code run}
	 * @param profile the launch's profile options
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(dataProvider = "profilesBesideAgent")
	public void refusesProfileBesideAgent(String command, List<String> profile) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I11
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
				Path artifact = artifactWithAgent(root, "model = \"gpt-6-luna\"\nmodel_reasoning_effort = \"high\"\n");
				List<String> args = launch(root, command, artifact);
				args.addAll(profile);
				try
				{
					start(jvm, root, args);
					throw new AssertionError("The launch was not refused");
				}
				catch (IllegalArgumentException e)
				{
					requireThat(e.getMessage(), "message").contains("--agent").contains("--model").contains("--effort");
				}
				requireThat(Files.exists(root.resolve("runs")), "runRootExists").isFalse();
			}
		}
	}

	/**
	 * Returns agent definitions that lack a model or an effort, for a bridge and a one-shot run.
	 *
	 * @return the command of each launch, the definition's profile fields and the field it lacks
	 */
	@DataProvider
	public Object[][] incompleteAgents()
	{
		List<Object[]> launches = new ArrayList<>();
		for (String command : List.of("serve", "run"))
		{
			launches.add(new Object[]{command, "model_reasoning_effort = \"high\"\n", "model"});
			launches.add(new Object[]{command, "model = \"gpt-6-luna\"\n", "model_reasoning_effort"});
		}
		return launches.toArray(Object[][]::new);
	}

	/**
	 * Verifies that an {@code --agent} launch whose agent definition does not name both a model and an effort is
	 * refused, naming the missing field, before it takes a capacity slot or starts Codex.
	 *
	 * @param command the launch's command, {@code serve} or {@code run}
	 * @param fields  the definition's profile fields
	 * @param missing the field that the definition lacks
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(dataProvider = "incompleteAgents")
	public void refusesAgentWithoutProfile(String command, String fields, String missing) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I11
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
				Path artifact = artifactWithAgent(root, fields);
				try
				{
					start(jvm, root, launch(root, command, artifact));
					throw new AssertionError("The launch was not refused");
				}
				catch (IOException e)
				{
					requireThat(e.getMessage(), "message").contains(AGENT).contains("no " + missing + " ");
				}
				requireThat(Files.exists(root.resolve("runs")), "runRootExists").isFalse();
			}
		}
	}

	/**
	 * Verifies that an {@code --agent} bridge launch without {@code --model} and {@code --effort} runs under the model
	 * and effort that the agent definition names.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void agentSuppliesProfile() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I11
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
				Path artifact = artifactWithAgent(root, "model = \"gpt-6-luna\"\nmodel_reasoning_effort = \"none\"\n");
				HarnessRunnerOptions options = CodexRunnerCli.options(new String[]{"serve", "--artifact",
					artifact.toString(), "--run-root", root.resolve("runs").toString(), "--agent", AGENT, "--install-cat"},
					HarnessTemporaryRoot.withoutSession("no session"));
				requireThat(options.model(), "model").isEqualTo("gpt-6-luna");
				requireThat(options.effort(), "effort").isEqualTo("none");
			}
		}
	}

	/**
	 * Returns the options of an {@code --agent} launch without a profile.
	 *
	 * @param root     the test's directory
	 * @param command  the launch's command, {@code serve} or {@code run}
	 * @param artifact the artifact that holds the agent definition
	 * @return the launch's arguments
	 * @throws IOException if the prompt file cannot be written
	 */
	private static List<String> launch(Path root, String command, Path artifact) throws IOException
	{
		List<String> args = new ArrayList<>(List.of(command, "--artifact", artifact.toString(), "--project",
			root.resolve("project").toString(), "--run-root", root.resolve("runs").toString(), "--agent", AGENT,
			"--install-cat"));
		if (command.equals("run"))
		{
			Path prompt = Files.writeString(root.resolve("prompt.txt"), "task");
			args.addAll(List.of("--prompt-file", prompt.toString(), "--output", root.resolve("output").toString()));
		}
		return args;
	}

	/**
	 * Starts a launch whose capacity can never be taken, so that a launch that is not refused fails the test.
	 *
	 * @param jvm  the test's process scope
	 * @param root the test's directory
	 * @param args the launch's arguments
	 * @throws IOException if the launch is refused for its agent definition
	 */
	private static void start(TestJvmScope jvm, Path root, List<String> args) throws IOException
	{
		String[] arguments = args.toArray(String[]::new);
		if (arguments[0].equals("serve"))
		{
			CodexRunnerCli.serve(jvm, root, arguments, new StringReader(""), new StringWriter(), Map.of(), _ ->
			{
				throw new AssertionError("The launch took a capacity slot");
			});
			return;
		}
		HarnessCapacity capacity = HarnessCapacity.of(jvm.fileLocks(), root.resolve("locks"), 1, () ->
		{
			throw new AssertionError("The run waited for capacity");
		}, new ManualClock(Instant.parse("2026-10-30T12:00:00Z")));
		try (HarnessCapacity.Permit _ = capacity.acquire())
		{
			// The only slot is taken, so a run that tried to take one would wait and fail the test
			CodexRunnerCli.run(jvm, root, arguments, Map.of(), capacity);
		}
	}

	/**
	 * Creates an artifact that holds one CAT Codex agent definition.
	 *
	 * @param root   the test's directory
	 * @param fields the definition's profile fields, one per line
	 * @return the artifact
	 * @throws IOException if the definition cannot be written
	 */
	private static Path artifactWithAgent(Path root, String fields) throws IOException
	{
		Path artifact = root.resolve("artifact");
		Path agents = Files.createDirectories(artifact.resolve("agents/codex"));
		Files.writeString(agents.resolve(AGENT + ".toml"), "name = \"" + AGENT + "\"\n" + fields +
			"developer_instructions = '''\nReview the change.\n'''\n");
		return artifact;
	}
}
