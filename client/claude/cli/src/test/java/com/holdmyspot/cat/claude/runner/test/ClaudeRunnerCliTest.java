package com.holdmyspot.cat.claude.runner.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import com.holdmyspot.cat.claude.policy.ClaudeCommandSession;
import com.holdmyspot.cat.claude.runner.ClaudeRunner;
import com.holdmyspot.cat.claude.runner.ClaudeRunnerCli;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SessionHarness;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.fakes.test.GitProjects;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessCapacityWaitExpiredException;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.InvalidArgumentsException;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.LaunchRoute;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyBoundFile;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.expectThrows;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link ClaudeRunnerCli}.
 */
public final class ClaudeRunnerCliTest
{
	/**
	 * The working directory of a launch whose environment names no home folder, so that the launcher never resolves
	 * it.
	 */
	private static final Path WORKING_DIRECTORY = Path.of("/working-directory");
	/**
	 * The name of the CAT agent definition in a test artifact.
	 */
	private static final String AGENT = "probe-agent";
	/**
	 * The identifier of the Claude Code session that runs the launcher.
	 */
	private static final String SESSION_ID = "5d2c8e4a-7b1f-4c3d-9e6a-1f2b3c4d5e6f";
	/**
	 * The environment of a launch that holds a Claude login, which a run requires before it takes a capacity slot.
	 */
	private static final Map<String, String> LOGIN = Map.of("CLAUDE_CODE_OAUTH_TOKEN", "fake-token");

	/**
	 * Creates the test.
	 */
	public ClaudeRunnerCliTest()
	{
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
				String[] args = {"--artifact", root.resolve("artifact").toString(), "--project",
					root.resolve("project").toString(), "--run-root", root.resolve("runs").toString(), "--capacity-wait",
					"5", "--model", "sonnet", "--effort", "low", "--policy-bound", policyBound(root).toString()};
				try (HarnessCapacity.Permit _ = capacity.acquire())
				{
					ClaudeRunnerCli.serve(jvm, root, args, new StringReader(""), new StringWriter(), LOGIN,
						_ -> capacity);
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
	 * Verifies that starting {@code claude-runner} marks a Claude run as failed when the runner that owned it has
	 * ended, so that the run does not stay running after its runner died.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void marksRunOfEndedOwnerFailed() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
			Path manifest = createRun(jvm, root.resolve("runs"));
			// A start that differs from the one of the process holding the recorded identifier means that the
			// identifier now belongs to another process, so the runner that created the run has ended
			Files.writeString(manifest, Files.readString(manifest).replaceFirst("\"owner_started_at\": \"[^\"]+\"",
				"\"owner_started_at\": \"0\""));

			startUntilCapacityIsRequested(jvm, root);

			requireThat(Files.readString(manifest), "manifest").contains("\"state\": \"failed\"").
				contains("Runner process ended without recording a terminal result");
		}
	}

	/**
	 * Verifies that starting {@code claude-runner} leaves a Claude run running when the runner that owns it is alive.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void keepsRunOfLiveOwnerRunning() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
			// The process that runs the test owns the run and is alive
			Path manifest = createRun(jvm, root.resolve("runs"));

			startUntilCapacityIsRequested(jvm, root);

			requireThat(Files.readString(manifest), "manifest").contains("\"state\": \"running\"");
		}
	}

	/**
	 * Creates a running run that the process executing the test owns.
	 *
	 * @param jvm     the test's process scope
	 * @param runRoot the directory that holds the runs
	 * @return the run's manifest
	 * @throws IOException if the run cannot be created
	 */
	private static Path createRun(TestJvmScope jvm, Path runRoot) throws IOException
	{
		HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), runRoot, Clock.systemUTC(),
			Yielding.fileReplacer());
		return manager.create("claude").directory().resolve("run.json");
	}

	/**
	 * Starts {@code claude-runner}, which stops when it asks for capacity: it asks only after the steps that it takes
	 * when it starts to prepare its runs.
	 *
	 * @param jvm  the test's process scope
	 * @param root the test's directory, whose {@code runs} subdirectory holds the runs
	 * @throws IOException if the launch fails before it asks for capacity
	 */
	private static void startUntilCapacityIsRequested(TestJvmScope jvm, Path root) throws IOException
	{
		String[] args = {"--artifact", root.resolve("artifact").toString(), "--project",
			root.resolve("project").toString(), "--run-root", root.resolve("runs").toString(), "--model", "sonnet",
			"--effort", "low", "--policy-bound", policyBound(root).toString()};
		try
		{
			ClaudeRunnerCli.serve(jvm, root, args, new StringReader(""), new StringWriter(), LOGIN, _ ->
			{
				throw new IllegalStateException("Capacity requested");
			});
			throw new AssertionError("The launch did not ask for capacity");
		}
		catch (IllegalStateException e)
		{
			requireThat(e.getMessage(), "message").isEqualTo("Capacity requested");
		}
	}

	/**
	 * Returns launches that lack a model, an effort or both, each with the options that the refusal must name.
	 *
	 * @return the profile options of each launch and the refusal's expected start
	 */
	@DataProvider
	public Object[][] incompleteProfiles()
	{
		return new Object[][]{{List.of("--effort", "low"), "Missing --model."},
			{List.of("--model", "sonnet"), "Missing --effort."},
			{List.of(), "Missing --model and --effort."}};
	}

	/**
	 * Verifies that a launch without {@code --model} or {@code --effort} is refused with a message that names the
	 * missing option and where to list the supported values, before it takes a capacity slot or starts Claude.
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
				List<String> args = new ArrayList<>(List.of("--artifact", root.resolve("artifact").toString(),
					"--project", root.resolve("project").toString(), "--run-root", root.resolve("runs").toString()));
				args.addAll(profile);
				try
				{
					// Taking capacity comes before starting Claude, so a refusal that takes none starts no session
					ClaudeRunnerCli.serve(jvm, root, args.toArray(String[]::new), new StringReader(""), new StringWriter(),
						Map.of(), _ ->
						{
							throw new AssertionError("The launch took a capacity slot");
						});
					throw new AssertionError("The launch was not refused");
				}
				catch (IllegalArgumentException e)
				{
					requireThat(e.getMessage(), "message").startsWith(expected).contains("`claude --help`");
				}
				requireThat(Files.exists(root.resolve("runs")), "runRootExists").isFalse();
			}
		}
	}

	/**
	 * Returns the profile options that a launch naming {@code --agent} adds.
	 *
	 * @return the profile options of each launch
	 */
	@DataProvider
	public Object[][] profilesBesideAgent()
	{
		return new Object[][]{{List.of("--model", "sonnet")}, {List.of("--effort", "low")},
			{List.of("--model", "sonnet", "--effort", "low")}};
	}

	/**
	 * Verifies that a launch that names {@code --agent} together with {@code --model} or {@code --effort} is refused,
	 * because the agent definition supplies the profile, before it takes a capacity slot or starts Claude.
	 *
	 * @param profile the launch's profile options
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(dataProvider = "profilesBesideAgent")
	public void refusesProfileBesideAgent(List<String> profile) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I11
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
				Path artifact = artifactWithAgent(root, "model: opus\neffort: high\n");
				List<String> args = new ArrayList<>(List.of("--artifact", artifact.toString(), "--project",
					root.resolve("project").toString(), "--run-root", root.resolve("runs").toString(), "--agent", AGENT));
				args.addAll(profile);
				try
				{
					ClaudeRunnerCli.serve(jvm, root, args.toArray(String[]::new), new StringReader(""), new StringWriter(),
						Map.of(), _ ->
						{
							throw new AssertionError("The launch took a capacity slot");
						});
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
	 * Returns agent definitions that lack a model or an effort, each with the missing field.
	 *
	 * @return the frontmatter of each definition and the field it lacks
	 */
	@DataProvider
	public Object[][] incompleteAgents()
	{
		return new Object[][]{{"effort: high\n", "model"}, {"model: opus\n", "effort"}};
	}

	/**
	 * Verifies that an {@code --agent} launch whose agent definition does not name both a model and an effort is
	 * refused, naming the missing field, before it takes a capacity slot or starts Claude.
	 *
	 * @param frontmatter the definition's frontmatter fields
	 * @param missing     the field that the definition lacks
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(dataProvider = "incompleteAgents")
	public void refusesAgentWithoutProfile(String frontmatter, String missing) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I11
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
				Path artifact = artifactWithAgent(root, frontmatter);
				String[] args = {"--artifact", artifact.toString(), "--project", root.resolve("project").toString(),
					"--run-root", root.resolve("runs").toString(), "--agent", AGENT};
				try
				{
					ClaudeRunnerCli.serve(jvm, root, args, new StringReader(""), new StringWriter(), Map.of(),
						_ ->
					{
						throw new AssertionError("The launch took a capacity slot");
					});
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
	 * Verifies that an {@code --agent} launch without {@code --model} and {@code --effort} runs under the model and
	 * effort that the agent definition names.
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
				Path artifact = artifactWithAgent(root, "model: opus\neffort: none\n");
				HarnessRunnerOptions options = ClaudeRunnerCli.options(new String[]{"--artifact", artifact.toString(),
					"--run-root", root.resolve("runs").toString(), "--agent", AGENT, "--install-cat"},
					HarnessTemporaryRoot.withoutSession("no session"));
				requireThat(options.model(), "model").isEqualTo("opus");
				requireThat(options.effort(), "effort").isEqualTo("none");
			}
		}
	}

	/**
	 * Returns the options that would let a caller choose what the launcher runs.
	 *
	 * @return each option
	 */
	@DataProvider
	public Object[][] overrides()
	{
		return new Object[][]{{"--artifact"}};
	}

	/**
	 * Verifies that outside a run the launcher refuses an option that would choose the artifact it runs, with the
	 * reason, before it does anything else.
	 *
	 * @param option the refused option
	 */
	@Test(dataProvider = "overrides")
	public void refusesOverride(String option)
	{
		StringWriter errors = new StringWriter();

		boolean refused = ClaudeRunnerCli.refusesOverride(new String[]{"--project", "project", option, "elsewhere",
			"--model", "sonnet", "--effort", "low"}, Map.of(), new PrintWriter(errors, true));

		requireThat(refused, "refused").isTrue();
		requireThat(errors.toString(), "errors").contains("claude-runner").contains(option).
			contains("its own CAT artifact").contains("PATH");
	}

	/**
	 * Verifies that inside a run whose runner lets its session request sessions, the launcher does not refuse the
	 * option, since it forwards the launch as a session request whose runner ignores it.
	 *
	 * @param option the option that the forwarded launch names
	 */
	@Test(dataProvider = "overrides")
	public void forwardsOverrideInsideRun(String option)
	{
		StringWriter errors = new StringWriter();

		boolean refused = ClaudeRunnerCli.refusesOverride(new String[]{option, "elsewhere", "--model", "sonnet",
			"--effort", "low"}, Map.of(SessionRequestAccess.REQUESTS_VARIABLE, "requests"),
			new PrintWriter(errors, true));

		requireThat(refused, "refused").isFalse();
		requireThat(errors.toString(), "errors").isEmpty();
	}

	/**
	 * Returns each native Windows platform.
	 *
	 * @return the platform
	 */
	@DataProvider
	public Object[][] nativeWindows()
	{
		return new Object[][]{{Platform.WINDOWS_X64}, {Platform.WINDOWS_ARM64}};
	}

	/**
	 * Verifies that on native Windows, where Claude Code has no sandbox, the launcher refuses every launch that starts
	 * a Claude session, forwarded or not, with a reason that names what is missing and directs the user to WSL2.
	 *
	 * @param platform the native Windows platform
	 */
	@Test(dataProvider = "nativeWindows")
	public void refusesSessionOnNativeWindows(Platform platform)
	{
		for (Map<String, String> environment : List.of(Map.<String, String>of(),
			Map.of(SessionRequestAccess.REQUESTS_VARIABLE, "requests")))
		{
			StringWriter errors = new StringWriter();

			boolean refused = ClaudeRunnerCli.refusesLaunch(new String[]{"--project", "project", "--model", "sonnet",
				"--effort", "low"}, WORKING_DIRECTORY, environment, platform, new PrintWriter(errors, true));

			requireThat(refused, "refused").isTrue();
			requireThat(errors.toString(), "errors").contains("Claude Code has no sandbox on native Windows").
				contains("WSL2").contains("wsl --install");
		}
	}

	/**
	 * Verifies that on native Windows the launcher still answers {@code --help} and verifies an artifact, since
	 * neither starts a session.
	 *
	 * @param platform the native Windows platform
	 */
	@Test(dataProvider = "nativeWindows")
	public void answersHelpOnNativeWindows(Platform platform)
	{
		StringWriter errors = new StringWriter();

		boolean helpRefused = ClaudeRunnerCli.refusesLaunch(new String[]{"--help"}, WORKING_DIRECTORY, Map.of(), platform,
			new PrintWriter(errors, true));
		boolean verifyRefused = ClaudeRunnerCli.refusesLaunch(new String[]{"--verify-artifact", "artifact"},
			WORKING_DIRECTORY, Map.of(), platform, new PrintWriter(errors, true));

		requireThat(helpRefused, "helpRefused").isFalse();
		requireThat(verifyRefused, "verifyRefused").isFalse();
		requireThat(errors.toString(), "errors").isEmpty();
	}

	/**
	 * Verifies that on Linux and macOS, where Claude Code sandboxes a session, the launcher does not refuse a launch
	 * that starts one.
	 */
	@Test
	public void startsSessionOnLinuxAndMacOs()
	{
		for (Platform platform : List.of(Platform.LINUX_X64, Platform.LINUX_ARM64, Platform.MACOS_X64,
			Platform.MACOS_ARM64))
		{
			StringWriter errors = new StringWriter();

			boolean refused = ClaudeRunnerCli.refusesLaunch(new String[]{"--project", "project", "--model", "sonnet",
				"--effort", "low"}, WORKING_DIRECTORY, Map.of(), platform, new PrintWriter(errors, true));

			requireThat(refused, "refused").withContext(platform, "platform").isFalse();
			requireThat(errors.toString(), "errors").withContext(platform, "platform").isEmpty();
		}
	}

	/**
	 * Verifies that the launcher refuses a launch whose project folder is the user's home folder, whether the launch
	 * names it with {@code --project} or runs in it without {@code --project}, and starts a launch whose project is a
	 * folder below the home folder.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesHomeFolderProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("runner-home-"));
			Files.createDirectory(home.resolve("project"));
			Map<String, String> environment = Map.of(Platform.LINUX_X64.homeVariable(), home.toString());
			List<String> profile = List.of("--model", "sonnet", "--effort", "low");

			for (List<String> project : List.of(List.of("--project", home.toString()), List.<String>of()))
			{
				StringWriter errors = new StringWriter();
				List<String> args = new ArrayList<>(project);
				args.addAll(profile);

				boolean refused = ClaudeRunnerCli.refusesLaunch(args.toArray(String[]::new), home, environment,
					Platform.LINUX_X64, new PrintWriter(errors, true));

				requireThat(refused, "refused").withContext(project, "project").isTrue();
				requireThat(errors.toString(), "errors").withContext(project, "project").
					contains("cannot be a CAT project").contains("Open a folder below the home folder");
			}
			StringWriter errors = new StringWriter();
			List<String> below = new ArrayList<>(List.of("--project", "project"));
			below.addAll(profile);

			boolean refused = ClaudeRunnerCli.refusesLaunch(below.toArray(String[]::new), home, environment,
				Platform.LINUX_X64, new PrintWriter(errors, true));

			requireThat(refused, "belowRefused").isFalse();
			requireThat(errors.toString(), "belowErrors").isEmpty();
		}
	}

	/**
	 * Verifies that the bridge that the optimizer and tests run in-process still honours a named artifact.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void inProcessBridgeHonoursArtifact() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
			Path artifact = root.resolve("artifact");
			HarnessRunnerOptions options = ClaudeRunnerCli.options(new String[]{"--artifact", artifact.toString(),
				"--run-root", root.resolve("runs").toString(), "--model", "sonnet", "--effort", "low"},
				HarnessTemporaryRoot.withoutSession("no session"));

			requireThat(options.artifact(), "artifact").isEqualTo(artifact);
		}
	}

	/**
	 * Verifies that a launch without {@code --policy-bound} is refused before it takes a capacity slot or creates a
	 * run, since only a request server starts a session and it always bounds the session's policy.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesLaunchWithoutPolicyBound() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
			String[] args = {"--artifact", root.resolve("artifact").toString(), "--project",
				root.resolve("project").toString(), "--run-root", root.resolve("runs").toString(), "--model", "sonnet",
				"--effort", "low"};
			try
			{
				ClaudeRunnerCli.serve(jvm, root, args, new StringReader(""), new StringWriter(), Map.of(), _ ->
				{
					throw new AssertionError("The launch took a capacity slot");
				});
				throw new AssertionError("The launch was not refused");
			}
			catch (SessionRefusedException e)
			{
				requireThat(e.getMessage(), "message").contains("--policy-bound");
			}
			requireThat(Files.exists(root.resolve("runs")), "runRootExists").isFalse();
		}
	}

	/**
	 * Verifies that a policy bound with a field that its format does not define is rejected rather than ignored.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void rejectsPolicyBoundWithUnknownField() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
			Path bound = Files.writeString(root.resolve("bound.json"), Files.readString(policyBound(root)).
				replace("\"format_version\"", "\"allow_everything\": true, \"format_version\""));
			String[] args = {"--artifact", root.resolve("artifact").toString(), "--project",
				root.resolve("project").toString(), "--run-root", root.resolve("runs").toString(), "--model", "sonnet",
				"--effort", "low", "--policy-bound", bound.toString()};
			try
			{
				ClaudeRunnerCli.serve(jvm, root, args, new StringReader(""), new StringWriter(), Map.of(), _ ->
				{
					throw new AssertionError("The launch took a capacity slot");
				});
				throw new AssertionError("The bound was not rejected");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("allow_everything").contains(bound.toString());
			}
		}
	}

	/**
	 * Verifies that a launch whose file of extra permissions is invalid is refused, naming the file and the problem,
	 * before it takes a capacity slot.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void rejectsInvalidExtraPermissions() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
			Path extra = Files.writeString(root.resolve("extra.json"),
				"{\"format_version\": 1, \"writes\": [\"relative/cache\"]}");
			String[] args = {"--artifact", root.resolve("artifact").toString(), "--project",
				root.resolve("project").toString(), "--run-root", root.resolve("runs").toString(), "--model", "sonnet",
				"--effort", "low", "--policy-bound", policyBound(root).toString(), "--extra-permissions",
				extra.toString()};

			InvalidArgumentsException refusal = expectThrows(InvalidArgumentsException.class, () ->
				ClaudeRunnerCli.serve(jvm, root, args, new StringReader(""), new StringWriter(), Map.of(), _ ->
				{
					throw new AssertionError("The launch took a capacity slot");
				}));

			requireThat(refusal.getMessage(), "message").contains(Path.of("relative/cache").toString()).
				contains(extra.toString());
		}
	}

	/**
	 * Verifies that a launch whose requester holds the session's capacity slot takes none, so that it starts even
	 * while every slot is taken.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void heldCapacityTakesNoSlot() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
			String[] args = {"--artifact", root.resolve("artifact").toString(), "--project",
				root.resolve("project").toString(), "--run-root", root.resolve("runs").toString(), "--model", "sonnet",
				"--effort", "low", "--policy-bound", policyBound(root).toString(), "--capacity-held"};
			try
			{
				ClaudeRunnerCli.serve(jvm, root, args, new StringReader(""), new StringWriter(), Map.of(), _ ->
				{
					throw new AssertionError("The launch took a capacity slot");
				});
				throw new AssertionError("The launch started a session without a CAT artifact");
			}
			catch (IOException _)
			{
				// The session starts without a slot, and fails only because the test runs no CAT artifact
			}
		}
	}

	/**
	 * Writes a policy bound that lets a session's commands write the test's directory and reach Anthropic's API.
	 *
	 * @param root the test's directory
	 * @return the bound's file
	 * @throws IOException if the file cannot be written
	 */
	private static Path policyBound(Path root) throws IOException
	{
		return Files.writeString(root.resolve("policy-bound.json"), """
			{"format_version": 1, "write_roots": [%s], "write_denials": [], "read_denials": [],
			 "read_reallowances": [], "hosts": ["api.anthropic.com"], "allow_local_binding": false}
			""".formatted(JsonMapper.builder().build().writeValueAsString(root.toAbsolutePath().toString())));
	}

	/**
	 * Returns command environments and the harnesses whose markers they hold.
	 *
	 * @return the environments and the harnesses that run their commands
	 */
	@DataProvider
	public static Object[][] markedEnvironments()
	{
		return new Object[][]
		{
			{Map.of(), Set.of()},
			{Map.of("CLAUDECODE", "1"), Set.of("claude")},
			{Map.of("CLAUDECODE", "0"), Set.of()},
			{Map.of("CLAUDECODE", ""), Set.of()},
			{Map.of("CODEX_THREAD_ID", "thread"), Set.of("codex")},
			{Map.of("CODEX_THREAD_ID", " "), Set.of()},
			{Map.of("CLAUDECODE", "1", "CODEX_THREAD_ID", "thread"), Set.of("claude", "codex")}
		};
	}

	/**
	 * Verifies that the launcher names a harness as running the command only when that harness's marker says so:
	 * {@code CLAUDECODE} set to {@code 1}, or a {@code CODEX_THREAD_ID} that is not blank.
	 *
	 * @param environment the command's environment
	 * @param expected    the harnesses that run the command
	 */
	@Test(dataProvider = "markedEnvironments")
	public void readsHarnessMarkers(Map<String, String> environment, Set<String> expected)
	{
		Set<String> harnesses = ClaudeRunnerCli.MARKERS.running(environment);

		requireThat(harnesses, "harnesses").withContext(environment, "environment").isEqualTo(expected);
	}

	/**
	 * Verifies that a launch that no harness runs is bounded by exactly what a Claude session needs: its run folder
	 * and the writes and hosts that the Claude runner reports, the reads that the launch denies, and nothing more.
	 * The bounded launch then serves its session in place within that bound.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void boundsUnmarkedLaunchByNeeds() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("runner-home-")).toRealPath();
			Path project = Files.createDirectory(home.resolve("project"));
			Path runRoot = home.resolve("runs");
			Path secret = Files.createDirectory(home.resolve("secret"));
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(), home.toString());
			String[] args = {"--project", project.toString(), "--run-root", runRoot.toString(), "--deny-read",
				secret.toString(), "--model", "sonnet", "--effort", "low"};
			ChildNeedsReport needs = ClaudeRunner.childNeeds(scope, environment);

			List<String> bounded = ClaudeRunnerCli.inPlaceArguments(scope, project, args, environment, cleanup);

			requireThat(bounded.subList(0, args.length), "options").isEqualTo(List.of(args));
			requireThat(bounded.get(args.length), "option").isEqualTo(PolicyBoundFile.OPTION);
			ChildPolicy policy = PolicyBoundFile.read(scope.json(), Path.of(bounded.get(args.length + 1)));
			List<Path> writes = new ArrayList<>(List.of(runRoot));
			writes.addAll(needs.writes());
			requireThat(policy.writeRoots(), "writeRoots").isEqualTo(writes);
			requireThat(policy.writeDenials(), "writeDenials").isEmpty();
			requireThat(policy.readDenials(), "readDenials").isEqualTo(List.of(secret));
			requireThat(policy.hosts(), "hosts").isEqualTo(needs.hosts());
			requireThat(LaunchRoute.decide("claude", "claude", ClaudeRunnerCli.MARKERS.running(environment),
				bounded.toArray(String[]::new), environment), "route").isEqualTo(new LaunchRoute.Bounded());
		}
	}

	/**
	 * Verifies that a launch of a session in this process, which every session starts through, is refused with the
	 * refusal status when the environment holds no Claude authentication variable, even though the user's Claude
	 * configuration directory holds a login file: the reason names the command that creates a token and the variable
	 * that holds it, and the launch creates no run and takes no capacity slot.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesLaunchWithoutLoginVariable() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("runner-home-")).toRealPath();
			Files.writeString(Files.createDirectory(home.resolve(".claude")).resolve(".credentials.json"), "{}");
			Path project = Files.createDirectory(home.resolve("project"));
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(), home.toString());
			StringWriter errors = new StringWriter();
			ByteArrayOutputStream output = new ByteArrayOutputStream();

			int status = ClaudeRunnerCli.launch(new String[]{"--project", project.toString(), "--run-root",
				home.resolve("runs").toString(), "--model", "sonnet", "--effort", "low", PolicyBoundFile.OPTION,
				policyBound(home).toString()}, environment, Yielding.waiting(), new StringReader(""), output,
				new PrintWriter(errors, true), () -> home.resolve("plugin"), () ->
				{
					throw new AssertionError("No session's tool scope is opened");
				});

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains("claude setup-token").contains("CLAUDE_CODE_OAUTH_TOKEN");
			requireThat(output.size(), "outputSize").isZero();
			requireThat(Files.exists(home.resolve("runs")), "runRootExists").isFalse();
		}
	}

	/**
	 * Verifies that a launch of a session in this process that holds an authentication variable is not refused for
	 * lack of a login, so that its run goes on to start, as far as a test without a CAT artifact goes.
	 *
	 * @param variable the authentication variable
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(dataProvider = "authenticationVariables")
	public void startsLaunchWithLoginVariable(String variable) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("runner-home-")).toRealPath();
			Path project = Files.createDirectory(home.resolve("project"));
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(), home.toString(),
				variable, "fake-token");
			StringWriter errors = new StringWriter();

			IOException failure = expectThrows(IOException.class, () ->
				ClaudeRunnerCli.launch(new String[]{"--project", project.toString(), "--run-root",
					home.resolve("runs").toString(), "--model", "sonnet", "--effort", "low", "--capacity-held",
					PolicyBoundFile.OPTION, policyBound(home).toString()}, environment, Yielding.waiting(),
					new StringReader(""), new ByteArrayOutputStream(), new PrintWriter(errors, true),
					() -> home.resolve("plugin"), () ->
					{
						throw new AssertionError("No session's tool scope is opened");
					}));

			// The session starts and fails only because the test runs no CAT artifact
			requireThat(failure, "failure").isNotInstanceOf(SessionRefusedException.class);
			requireThat(failure.getMessage(), "message").doesNotContain("setup-token");
			requireThat(errors.toString(), "errors").isEmpty();
		}
	}

	/**
	 * Returns the variables that each authenticate a Claude session.
	 *
	 * @return the variable names
	 */
	@DataProvider
	public static Object[][] authenticationVariables()
	{
		return new Object[][]
		{
			{"CLAUDE_CODE_OAUTH_TOKEN"},
			{"ANTHROPIC_API_KEY"},
			{"ANTHROPIC_AUTH_TOKEN"}
		};
	}

	/**
	 * Verifies that a launch that no harness runs is refused with the refusal status, and starts nothing, when what
	 * its session needs cannot be determined: CAT cannot then read the session's sandbox policy.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesUnmarkedLaunchWithoutNeeds() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("runner-home-")).toRealPath();
			Path project = Files.createDirectory(home.resolve("project"));
			Files.writeString(home.resolve(".npm"), "not a folder");
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(), home.toString());
			StringWriter errors = new StringWriter();
			ByteArrayOutputStream output = new ByteArrayOutputStream();

			int status = ClaudeRunnerCli.launch(new String[]{"--project", project.toString(), "--run-root",
				home.resolve("runs").toString(), "--model", "sonnet", "--effort", "low"}, environment,
				Yielding.waiting(), new StringReader(""), output, new PrintWriter(errors, true));

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").startsWith("CAT cannot start the session: it cannot read this " +
				"Claude Code session's sandbox policy: ");
			requireThat(output.size(), "outputSize").isZero();
		}
	}

	/**
	 * Verifies that a launch that no harness runs is refused with the refusal status, as every launch is whose needs
	 * cannot be determined, when its model provider's base URL names no host, and that the refusal names the setting
	 * and starts nothing.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesUnmarkedLaunchWithoutModelHost() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("runner-home-")).toRealPath();
			Path project = Files.createDirectory(home.resolve("project"));
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(), home.toString(),
				"ANTHROPIC_BASE_URL", "file:///no-host");
			StringWriter errors = new StringWriter();
			ByteArrayOutputStream output = new ByteArrayOutputStream();

			int status = ClaudeRunnerCli.launch(new String[]{"--project", project.toString(), "--run-root",
				home.resolve("runs").toString(), "--model", "sonnet", "--effort", "low"}, environment,
				Yielding.waiting(), new StringReader(""), output, new PrintWriter(errors, true));

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").startsWith("CAT cannot start the session: it cannot read this " +
				"Claude Code session's sandbox policy: ").contains("ANTHROPIC_BASE_URL names no host: file:///no-host");
			requireThat(output.size(), "outputSize").isZero();
		}
	}

	/**
	 * Verifies that the runner that a request server asks for a child's needs writes them to the file it is given
	 * and exits with status 0, and, when its model provider's base URL names no host, writes no file and exits with the
	 * refusal status after one line that names the setting, without a stack trace.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void reportsChildNeedsOrRefuses() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("runner-home-")).toRealPath();
			Path report = home.resolve("needs.json");
			StringWriter errors = new StringWriter();
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(), home.toString());

			int status = ClaudeRunnerCli.reportChildNeeds(scope, environment, report, new PrintWriter(errors, true));

			requireThat(status, "status").isZero();
			requireThat(ChildNeedsReport.read(scope.json(), report).hosts(), "hosts").isEqualTo(Set.of(
				"api.anthropic.com"));
			requireThat(errors.toString(), "errors").isEmpty();

			Path refused = home.resolve("refused.json");
			Map<String, String> noHost = Map.of("PATH", "", Platform.current().homeVariable(), home.toString(),
				"ANTHROPIC_BASE_URL", "file:///no-host");

			status = ClaudeRunnerCli.reportChildNeeds(scope, noHost, refused, new PrintWriter(errors, true));

			requireThat(status, "refusedStatus").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(Files.exists(refused), "refusedWritten").isFalse();
			requireThat(errors.toString().strip(), "refusal").isEqualTo(
				"ANTHROPIC_BASE_URL names no host: file:///no-host");
		}
	}

	/**
	 * Verifies that a launch from a command that Claude Code runs outside a run is refused with the refusal status
	 * when no request server serves the session, with a reason that names the command that starts the server, before
	 * the launcher writes a request or starts anything.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesClaudeLaunchWithoutServer() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("runner-home-")).toRealPath();
			Path project = Files.createDirectories(home.resolve("project/.cat")).getParent();
			Path plugin = home.resolve("plugin");
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(), home.toString(),
				"CLAUDECODE", "1", ClaudeCommandSession.SESSION_VARIABLE, SESSION_ID);
			StringWriter errors = new StringWriter();
			ByteArrayOutputStream output = new ByteArrayOutputStream();

			int status = ClaudeRunnerCli.launch(new String[]{"--project", project.toString(), "--model", "sonnet",
					"--effort", "low", "--run-root", home.resolve("runs").toString()}, environment,
				Yielding.waiting(), new StringReader(""), output, new PrintWriter(errors, true), () -> plugin,
				() -> ToolScope.forSession(project, environment, SessionHarness.installed(), Yielding.waiting(),
					GitProjects.IGNORING));

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains("No request server serves this session").
				contains(plugin.resolve("bin/request-server").toString());
			requireThat(output.size(), "outputSize").isZero();
			requireThat(Files.exists(project.resolve(".cat/sessions").resolve(SESSION_ID).resolve("requests")),
				"requestsExist").isFalse();
			requireThat(Files.exists(home.resolve("runs")), "runRootExists").isFalse();
		}
	}

	/**
	 * Verifies that a launch from a command that Claude Code runs is refused with the refusal status, before the
	 * launcher sends a request, when the folder that the command runs in, which the new session would take as its
	 * project, is the user's home folder.
	 */
	@Test
	public void refusesHomeProjectFromClaudeSession()
	{
		Path home = Path.of("/home/user").toAbsolutePath();
		Map<String, String> environment = Map.of("PATH", "", Platform.LINUX_X64.homeVariable(), home.toString(),
			"CLAUDECODE", "1");
		StringWriter errors = new StringWriter();

		boolean refused = ClaudeRunnerCli.refusesLaunch(new String[]{"--model", "sonnet", "--effort", "low"}, home,
			environment, Platform.LINUX_X64, new PrintWriter(errors, true));

		requireThat(refused, "refused").isTrue();
		requireThat(errors.toString(), "errors").contains("cannot be a CAT project").
			contains("Open a folder below the home folder");
	}

	/**
	 * Verifies that a launch from a command that Claude Code runs is refused with the refusal status when it names an
	 * option that only CAT's own starters pass, and starts nothing.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesInternalOptionFromClaudeSession() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("runner-home-")).toRealPath();
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(), home.toString(),
				"CLAUDECODE", "1");
			StringWriter errors = new StringWriter();
			ByteArrayOutputStream output = new ByteArrayOutputStream();

			int status = ClaudeRunnerCli.launch(new String[]{"--project", home.toString(), PolicyBoundFile.OPTION,
					home.resolve("bound.json").toString()}, environment, Yielding.waiting(), new StringReader(""),
				output, new PrintWriter(errors, true), () ->
				{
					throw new AssertionError("No plugin is found");
				}, () ->
				{
					throw new AssertionError("No session's tool scope is opened");
				});

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains("only CAT itself passes " + PolicyBoundFile.OPTION);
			requireThat(output.size(), "outputSize").isZero();
		}
	}

	/**
	 * Returns environments in which a launch is refused before it starts anything, and a part of the reason.
	 *
	 * @return the environments and the reasons
	 */
	@DataProvider
	public static Object[][] refusedEnvironments()
	{
		return new Object[][]
		{
			{Map.of("CODEX_THREAD_ID", "thread"), "Codex runs this command, and CAT's Claude Code plugin holds no " +
				"request server for a Codex session. Run the runner of CAT's Codex plugin instead."},
			{Map.of("CAT_RUN", "1", "CLAUDECODE", "1"), "claude-runner refuses to start a session"}
		};
	}

	/**
	 * Verifies that a launch from a command that only Codex runs, or from a run whose runner lets its session request
	 * no sessions, is refused with the refusal status and the reason, and starts nothing.
	 *
	 * @param environment the command's environment
	 * @param reason      a part of the reason
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(dataProvider = "refusedEnvironments")
	public void refusesLaunchOnItsRoute(Map<String, String> environment, String reason) throws IOException
	{
		StringWriter errors = new StringWriter();
		ByteArrayOutputStream output = new ByteArrayOutputStream();

		int status = ClaudeRunnerCli.launch(new String[]{"--project", "project", "--model", "sonnet", "--effort",
			"low"}, environment, Yielding.waiting(), new StringReader(""), output, new PrintWriter(errors, true));

		requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
		requireThat(errors.toString(), "errors").contains(reason);
		requireThat(output.size(), "outputSize").isZero();
	}

	/**
	 * Creates an artifact that holds one CAT Claude agent definition.
	 *
	 * @param root        the test's directory
	 * @param frontmatter the definition's frontmatter fields, one per line
	 * @return the artifact
	 * @throws IOException if the definition cannot be written
	 */
	private static Path artifactWithAgent(Path root, String frontmatter) throws IOException
	{
		Path artifact = root.resolve("artifact");
		Path agents = Files.createDirectories(artifact.resolve("agents/claude"));
		Files.writeString(agents.resolve(AGENT + ".md"), "---\nname: " + AGENT + "\n" + frontmatter +
			"---\nReview the change.\n");
		return artifact;
	}
}
