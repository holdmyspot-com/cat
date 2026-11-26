package com.holdmyspot.cat.claude.runner.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.policy.ClaudeCommandSession;
import com.holdmyspot.cat.claude.runner.ClaudeRunnerCli;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SessionHarness;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.fakes.test.GitProjects;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.runner.AuthenticationOptions;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessCapacityWaitExpiredException;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.InvalidArgumentsException;
import com.holdmyspot.cat.common.runner.NestedSessionManager;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.PolicyBoundFile;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
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
import java.util.Optional;
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
					root.resolve("project").toString(), "--results", root.resolve("results").toString(), "--capacity-wait",
					"5", "--model", "sonnet", "--effort", "low", "--policy-bound", policyBound(root).toString()};
				try (HarnessCapacity.Permit _ = capacity.acquire())
				{
					ClaudeRunnerCli.serve(jvm, root, args, new StringReader(""), new StringWriter(), sourceHome(root),
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
	 * Verifies that starting {@code claude-runner} marks a Claude nested session as failed when the runner that owned it
	 * has ended, so that the nested session does not stay running after its runner died.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void marksSessionOfEndedOwnerFailed() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
			Path manifest = createSession(jvm, root.resolve("results"));
			// A start that differs from the one of the process holding the recorded identifier means that the
			// identifier now belongs to another process, so the runner that created the nested session has ended
			Files.writeString(manifest, Files.readString(manifest).replaceFirst("\"owner_started_at\": \"[^\"]+\"",
				"\"owner_started_at\": \"0\""));

			startUntilCapacityIsRequested(jvm, root);

			requireThat(Files.readString(manifest), "manifest").contains("\"state\": \"failed\"").
				contains("Runner process ended without recording a terminal result");
		}
	}

	/**
	 * Verifies that starting {@code claude-runner} leaves a Claude nested session running when the runner that owns it is
	 * alive.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void keepsSessionOfLiveOwnerRunning() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
			// The process that runs the test owns the nested session and is alive
			Path manifest = createSession(jvm, root.resolve("results"));

			startUntilCapacityIsRequested(jvm, root);

			requireThat(Files.readString(manifest), "manifest").contains("\"state\": \"running\"");
		}
	}

	/**
	 * Creates a running nested session that the process executing the test owns.
	 *
	 * @param jvm           the test's process scope
	 * @param resultsFolder the directory that holds the nested sessions
	 * @return the nested session's manifest
	 * @throws IOException if the nested session cannot be created
	 */
	private static Path createSession(TestJvmScope jvm, Path resultsFolder) throws IOException
	{
		NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), resultsFolder, Clock.systemUTC(),
			Yielding.fileReplacer());
		return manager.create("claude").directory().resolve("session.json");
	}

	/**
	 * Starts {@code claude-runner}, which stops when it asks for capacity: it asks only after the steps that it takes
	 * when it starts to prepare its nested sessions.
	 *
	 * @param jvm  the test's process scope
	 * @param root the test's directory, whose {@code runs} subdirectory holds the nested sessions
	 * @throws IOException if the launch fails before it asks for capacity
	 */
	private static void startUntilCapacityIsRequested(TestJvmScope jvm, Path root) throws IOException
	{
		String[] args = {"--artifact", root.resolve("artifact").toString(), "--project",
			root.resolve("project").toString(), "--results", root.resolve("results").toString(), "--model", "sonnet",
			"--effort", "low", "--policy-bound", policyBound(root).toString()};
		try
		{
			ClaudeRunnerCli.serve(jvm, root, args, new StringReader(""), new StringWriter(), sourceHome(root), _ ->
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
					"--project", root.resolve("project").toString(), "--results", root.resolve("results").toString()));
				args.addAll(profile);
				try
				{
					// Taking capacity comes before starting Claude, so a refusal that takes none starts no session
					ClaudeRunnerCli.serve(jvm, root, args.toArray(String[]::new), new StringReader(""), new StringWriter(),
						sourceHome(root), _ ->
						{
							throw new AssertionError("The launch took a capacity slot");
						});
					throw new AssertionError("The launch was not refused");
				}
				catch (IllegalArgumentException e)
				{
					requireThat(e.getMessage(), "message").startsWith(expected).contains("`claude --help`");
				}
				requireThat(Files.exists(root.resolve("results")), "resultsFolderExists").isFalse();
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
					root.resolve("project").toString(), "--results", root.resolve("results").toString(), "--agent", AGENT));
				args.addAll(profile);
				try
				{
					ClaudeRunnerCli.serve(jvm, root, args.toArray(String[]::new), new StringReader(""), new StringWriter(),
						sourceHome(root), _ ->
						{
							throw new AssertionError("The launch took a capacity slot");
						});
					throw new AssertionError("The launch was not refused");
				}
				catch (IllegalArgumentException e)
				{
					requireThat(e.getMessage(), "message").contains("--agent").contains("--model").contains("--effort");
				}
				requireThat(Files.exists(root.resolve("results")), "resultsFolderExists").isFalse();
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
					"--results", root.resolve("results").toString(), "--agent", AGENT};
				try
				{
					ClaudeRunnerCli.serve(jvm, root, args, new StringReader(""), new StringWriter(), sourceHome(root),
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
				requireThat(Files.exists(root.resolve("results")), "resultsFolderExists").isFalse();
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
					"--results", root.resolve("results").toString(), "--agent", AGENT, "--install-cat"},
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
	 * Verifies that outside a nested session the launcher refuses an option that would choose the artifact it runs,
	 * with the reason, before it does anything else.
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
	 * Verifies that inside a nested session whose runner lets its session request sessions, the launcher does not
	 * refuse the option, since it forwards the launch as a session request whose runner ignores it.
	 *
	 * @param option the option that the forwarded launch names
	 */
	@Test(dataProvider = "overrides")
	public void forwardsOverrideInsideNestedSession(String option)
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
	 * Verifies that on native Windows, where Claude Code has no sandbox, the launcher starts a judge, which runs without
	 * one, and still refuses a trial and a session with no role, which need one.
	 *
	 * @param platform the native Windows platform
	 */
	@Test(dataProvider = "nativeWindows")
	public void startsJudgeOnNativeWindows(Platform platform)
	{
		StringWriter judgeErrors = new StringWriter();

		boolean judgeRefused = ClaudeRunnerCli.refusesLaunch(new String[]{"--project", "project", "--model", "sonnet",
			"--effort", "low", "--session-role", "judge"}, WORKING_DIRECTORY, Map.of(), platform,
			new PrintWriter(judgeErrors, true));

		requireThat(judgeRefused, "judgeRefused").isFalse();
		requireThat(judgeErrors.toString(), "judgeErrors").isEmpty();
		for (String role : List.of("trial", "unknown"))
		{
			StringWriter errors = new StringWriter();

			boolean refused = ClaudeRunnerCli.refusesLaunch(new String[]{"--project", "project", "--model", "sonnet",
				"--effort", "low", "--session-role", role}, WORKING_DIRECTORY, Map.of(), platform,
				new PrintWriter(errors, true));

			requireThat(refused, "refused" + role).isTrue();
			requireThat(errors.toString(), "errors" + role).contains("Claude Code has no sandbox on native Windows");
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
				"--results", root.resolve("results").toString(), "--model", "sonnet", "--effort", "low"},
				HarnessTemporaryRoot.withoutSession("no session"));

			requireThat(options.artifact(), "artifact").isEqualTo(artifact);
		}
	}

	/**
	 * Verifies that a launch without {@code --policy-bound} is refused before it takes a capacity slot or creates a
	 * nested session, since only a request server starts a session and it always bounds the session's policy.
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
				root.resolve("project").toString(), "--results", root.resolve("results").toString(), "--model", "sonnet",
				"--effort", "low"};
			try
			{
				ClaudeRunnerCli.serve(jvm, root, args, new StringReader(""), new StringWriter(), sourceHome(root), _ ->
				{
					throw new AssertionError("The launch took a capacity slot");
				});
				throw new AssertionError("The launch was not refused");
			}
			catch (SessionRefusedException e)
			{
				requireThat(e.getMessage(), "message").contains("--policy-bound");
			}
			requireThat(Files.exists(root.resolve("results")), "resultsFolderExists").isFalse();
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
				root.resolve("project").toString(), "--results", root.resolve("results").toString(), "--model", "sonnet",
				"--effort", "low", "--policy-bound", bound.toString()};
			try
			{
				ClaudeRunnerCli.serve(jvm, root, args, new StringReader(""), new StringWriter(), sourceHome(root), _ ->
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
				root.resolve("project").toString(), "--results", root.resolve("results").toString(), "--model", "sonnet",
				"--effort", "low", "--policy-bound", policyBound(root).toString(), "--extra-permissions",
				extra.toString()};

			InvalidArgumentsException refusal = expectThrows(InvalidArgumentsException.class, () ->
				ClaudeRunnerCli.serve(jvm, root, args, new StringReader(""), new StringWriter(), sourceHome(root), _ ->
				{
					throw new AssertionError("The launch took a capacity slot");
				}));

			requireThat(refusal.getMessage(), "message").contains(Path.of("relative/cache").toString()).
				contains(extra.toString());
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
	 * Verifies that a launch that no harness session runs, such as one in a terminal or a script, is refused with the
	 * refusal status and a message that says to run the launcher from a Claude Code or Codex session, and starts
	 * nothing.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesUnmarkedLaunch() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("runner-home-")).toRealPath();
			Path project = Files.createDirectory(home.resolve("project"));
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(), home.toString());
			StringWriter errors = new StringWriter();
			ByteArrayOutputStream output = new ByteArrayOutputStream();

			int status = ClaudeRunnerCli.launch(new String[]{"--project", project.toString(), "--results",
				home.resolve("results").toString(), "--model", "sonnet", "--effort", "low"}, environment,
				Yielding.waiting(), new StringReader(""), output, new PrintWriter(errors, true));

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains("Run this command from a Claude Code or Codex session.");
			requireThat(output.size(), "outputSize").isZero();
			requireThat(Files.exists(home.resolve("results")), "resultsFolderExists").isFalse();
		}
	}


	/**
	 * Verifies that the runner that a request server asks for a child's needs writes them to the file it is given
	 * and exits with status 0, and, when the source's base URL names no host, writes no file and exits with the
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
			Map<String, String> environment = sourceHome(home);

			int status = ClaudeRunnerCli.reportChildNeeds(scope, environment, report, AuthenticationOptions.DEFAULT,
				SourceHomes.PLUGIN, new PrintWriter(errors, true));

			requireThat(status, "status").isZero();
			requireThat(ChildNeedsReport.read(scope.json(), report).hosts(), "hosts").isEqualTo(Set.of(
				"api.anthropic.com"));
			requireThat(errors.toString(), "errors").isEmpty();

			Path refused = home.resolve("refused.json");
			Path noHost = SourceHomes.create(home, "no-host");
			Path settings = SourceHomes.settings(noHost, """
				{"env": {"ANTHROPIC_BASE_URL": "file:///no-host", "ANTHROPIC_AUTH_TOKEN": "token"}}
				""");

			status = ClaudeRunnerCli.reportChildNeeds(scope, environment, refused, new AuthenticationOptions(
				Optional.of(noHost), Optional.of(HostLogin.API_KEY), false), SourceHomes.PLUGIN, new PrintWriter(errors, true));

			requireThat(status, "refusedStatus").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(Files.exists(refused), "refusedWritten").isFalse();
			requireThat(errors.toString().strip(), "refusal").contains("env.ANTHROPIC_BASE_URL").
				contains(settings.toString()).contains("names no host").doesNotContain("\n");
		}
	}

	/**
	 * Verifies that a launch from a command that Claude Code runs outside a nested session is refused with the refusal
	 * status when no request server serves the session, with a reason that names the command that starts the server,
	 * before the launcher writes a request or starts anything.
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
					"--effort", "low", "--results", home.resolve("results").toString()}, environment,
				Yielding.waiting(), new StringReader(""), output, new PrintWriter(errors, true), () -> plugin,
				() -> List.of(plugin), () -> ToolScope.forSession(project, environment, SessionHarness.installed(),
					Yielding.waiting(), GitProjects.IGNORING));

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains("No request server serves this session").
				contains(plugin.resolve("bin/request-server").toString());
			requireThat(output.size(), "outputSize").isZero();
			requireThat(Files.exists(project.resolve(".cat/sessions").resolve(SESSION_ID).resolve("requests")),
				"requestsExist").isFalse();
			requireThat(Files.exists(home.resolve("results")), "resultsFolderExists").isFalse();
		}
	}

	/**
	 * Verifies that a launch from a copy of CAT that is not the installed plugin, with no request server serving the
	 * session, is refused with a reason that names the installed plugin's request server, because the copy's own
	 * request server has no sandbox exception and would run inside the session's sandbox.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void namesInstalledServerFromCopy() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("runner-home-")).toRealPath();
			Path plugin = home.resolve("copy");
			Path installed = home.resolve("installed");

			String errors = launchWithoutServer(home, plugin, List.of(installed));

			requireThat(errors, "errors").contains("No request server serves this session").
				contains("Only the installed plugin's launchers start sessions").
				contains(installed.resolve("bin/request-server").toString()).
				doesNotContain(plugin.resolve("bin/request-server").toString());
		}
	}

	/**
	 * Verifies that a launch from a copy of CAT, when no plugin is installed and no request server serves the
	 * session, is refused with a reason that says to install CAT and names no request server of the copy.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void namesInstallCatFromCopy() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("runner-home-")).toRealPath();
			Path plugin = home.resolve("copy");

			String errors = launchWithoutServer(home, plugin, List.of());

			requireThat(errors, "errors").contains("No request server serves this session").contains("Install CAT").
				doesNotContain(plugin.resolve("bin/request-server").toString());
		}
	}

	/**
	 * Verifies that the installed plugin's own launcher, with no request server serving the session, still names its
	 * own request server, among other installed copies of CAT.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void namesOwnServerWhenInstalled() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("runner-home-")).toRealPath();
			Path plugin = home.resolve("plugin");

			String errors = launchWithoutServer(home, plugin, List.of(home.resolve("older"), plugin));

			requireThat(errors, "errors").contains("Start it in the background once").
				contains(plugin.resolve("bin/request-server").toString()).
				doesNotContain("Only the installed plugin's launchers");
		}
	}

	/**
	 * Runs a launch from a command that Claude Code runs outside a nested session, which no request server serves.
	 *
	 * @param home      the test's home directory
	 * @param plugin    the folder of the plugin that holds the launcher
	 * @param installed the folders of the installed plugin
	 * @return what the launcher wrote to its standard error
	 * @throws IOException if the test filesystem cannot be used
	 */
	private static String launchWithoutServer(Path home, Path plugin, List<Path> installed) throws IOException
	{
		Path project = Files.createDirectories(home.resolve("project/.cat")).getParent();
		Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(), home.toString(),
			"CLAUDECODE", "1", ClaudeCommandSession.SESSION_VARIABLE, SESSION_ID);
		StringWriter errors = new StringWriter();

		int status = ClaudeRunnerCli.launch(new String[]{"--project", project.toString(), "--model", "sonnet",
				"--effort", "low", "--results", home.resolve("results").toString()}, environment,
			Yielding.waiting(), new StringReader(""), new ByteArrayOutputStream(), new PrintWriter(errors, true),
			() -> plugin, () -> installed, () -> ToolScope.forSession(project, environment,
				SessionHarness.installed(), Yielding.waiting(), GitProjects.IGNORING));

		requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
		return errors.toString();
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
				}, List::of, () ->
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
			{Map.of("CAT_NESTED_SESSION", "1", "CLAUDECODE", "1"), "claude-runner refuses to start a session"}
		};
	}

	/**
	 * Verifies that a launch from a command that only Codex runs, or from a nested session whose runner lets its session
	 * request no sessions, is refused with the refusal status and the reason, and starts nothing.
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

	/**
	 * Verifies that a launch whose source home holds no Claude authentication is refused with the refusal status before
	 * it creates a nested session, naming the files that the runner read, although the runner's own environment and
	 * home folder hold a credential: only the source home's files decide.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesSourceHomeWithoutAuthentication() throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("source-home-")).toRealPath();
			Path source = SourceHomes.create(root, "source");
			SourceHomes.settings(source, """
				{"permissions": {"deny": []}}
				""");
			Path runnerHome = SourceHomes.create(root, "runner");
			SourceHomes.storedLogin(runnerHome);
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(),
				runnerHome.toString(), "ANTHROPIC_API_KEY", SourceHomes.SECRET);
			StringWriter errors = new StringWriter();

			int status = launchWithAuthentication(root, List.of("--home", source.toString()), environment, errors);

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireNoAuthenticationRefusal(errors.toString(), root, source);
			requireThat(Files.exists(root.resolve("results")), "resultsFolderExists").isFalse();
		}
	}

	/**
	 * Verifies that a launch whose source home's only Claude credential is a {@code /login} login, in
	 * {@code .credentials.json}, is refused with the refusal status before it creates a nested session, because sessions
	 * do not use such a login, and that the refusal names {@code claude setup-token} and the {@code claude-credential}
	 * command that saves CAT's credential file for that source home, by the launcher's absolute path.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesLoginOnlySourceHome() throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("source-home-")).toRealPath();
			Path source = SourceHomes.create(root, "source");
			SourceHomes.storedLogin(source);
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(),
				SourceHomes.create(root, "runner").toString());
			StringWriter errors = new StringWriter();

			int status = launchWithAuthentication(root, List.of("--home", source.toString()), environment, errors);

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireNoAuthenticationRefusal(errors.toString(), root, source);
			requireThat(Files.exists(root.resolve("results")), "resultsFolderExists").isFalse();
		}
	}

	/**
	 * Requires the refusal of a source home without Claude authentication: it names the files that the runner read,
	 * says that sessions do not use a {@code /login} login, names {@code claude setup-token} and the
	 * {@code claude-credential set} command for the source home by the launcher's absolute path, and quotes no secret.
	 *
	 * @param errors what the launch printed
	 * @param root   the test's directory, which holds the plugin folder that the launch names
	 * @param source the source home
	 */
	private static void requireNoAuthenticationRefusal(String errors, Path root, Path source)
	{
		requireThat(errors, "errors").contains(source.resolve(".claude/settings.json").toString()).
			contains(source.resolve(".cat/claude/.credentials.json").toString()).contains("/login").
			contains("claude setup-token").
			contains(root.resolve("plugin/bin/claude-credential") + " set --home " + source).
			doesNotContain(SourceHomes.SECRET).doesNotContain(SourceHomes.SETUP_TOKEN);
	}

	/**
	 * Verifies that a launch whose source home's CAT credential file is unusable is refused with the refusal status
	 * before it creates a nested session, naming the file and the fault, and for a token of another kind both accepted
	 * forms, without quoting the file's token.
	 *
	 * @param fault    the fault that the test gives the file
	 * @param expected text that the refusal names for the fault
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(dataProvider = "unusableCatCredentials")
	public void refusesUnusableCatCredential(String fault, List<String> expected) throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("source-home-")).toRealPath();
			Path source = SourceHomes.create(root, "source");
			Path file = source.resolve(".cat/claude/.credentials.json");
			String valid = """
				{"format_version": 1, "token": "%s"}
				""".formatted(SourceHomes.SECRET);
			switch (fault)
			{
				case "mode" -> SourceHomes.readableByOthers(SourceHomes.catCredentialFile(source, valid));
				case "link" ->
				{
					Path target = SourceHomes.secret(root.resolve("elsewhere/credentials.json"));
					Files.writeString(target, valid);
					Files.createDirectories(file.getParent());
					Files.createSymbolicLink(file, target);
				}
				case "prefix" -> SourceHomes.catCredential(source, "sk-proj-fixture");
				case "field" -> SourceHomes.catCredentialFile(source, """
					{"format_version": 1, "token": "%s", "login_class": "api_key"}
					""".formatted(SourceHomes.SECRET));
				case "version" -> SourceHomes.catCredentialFile(source, """
					{"format_version": 2, "token": "%s"}
					""".formatted(SourceHomes.SECRET));
				case "array" -> SourceHomes.catCredentialFile(source, "[\"" + SourceHomes.SECRET + "\"]");
				case "size" -> SourceHomes.catCredentialFile(source, """
					{"format_version": 1, "token": "%s%s"}
					""".formatted(SourceHomes.SECRET, "x".repeat(16 * 1024)));
				default -> throw new AssertionError(fault);
			}
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(),
				SourceHomes.create(root, "runner").toString());
			StringWriter errors = new StringWriter();

			int status = launchWithAuthentication(root, List.of("--home", source.toString()), environment, errors);

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains(file.toString()).doesNotContain(SourceHomes.SECRET).
				doesNotContain("sk-proj-fixture");
			for (String text : expected)
				requireThat(errors.toString(), "errors").contains(text);
			requireThat(Files.exists(root.resolve("results")), "resultsFolderExists").isFalse();
		}
	}

	/**
	 * Returns the faults of a CAT credential file, each with the text that its refusal names.
	 *
	 * @return the faults
	 */
	@DataProvider
	public static Object[][] unusableCatCredentials()
	{
		return new Object[][]
		{
			// Other users may read the file
			{"mode", SourceHomes.readableByOthersReasons()},
			// Anyone who may write the folder can change a link's target
			{"link", List.of("symbolic link")},
			// The token is neither an Anthropic API key nor a setup token
			{"prefix", List.of("sk-ant-api", "sk-ant-oat", "claude setup-token")},
			// The file holds a field beyond the format's
			{"field", List.of("login_class")},
			// The file is in a later format
			{"version", List.of("format_version")},
			// The file holds no JSON object
			{"array", List.of("JSON object")},
			// The file is larger than the format allows
			{"size", List.of("16384")}
		};
	}

	/**
	 * Verifies that a launch whose source home holds only CAT's credential file goes on to start its session, as far as
	 * a test without a CAT artifact goes.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void startsLaunchWithOnlyCatCredential() throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("source-home-")).toRealPath();
			Path home = SourceHomes.create(root, "runner");
			SourceHomes.catCredential(home, SourceHomes.SETUP_TOKEN);
			StringWriter errors = new StringWriter();

			String path = SandboxPrograms.path(root);
			IOException failure = expectThrows(IOException.class, () -> launchWithAuthentication(root, List.of(),
				Map.of("PATH", path, Platform.current().homeVariable(), home.toString()), errors));

			// The session starts and fails only because the test runs no CAT artifact
			requireThat(failure, "failure").isNotInstanceOf(SessionRefusedException.class);
			requireThat(errors.toString(), "errors").isEmpty();
		}
	}

	/**
	 * Verifies that a launch whose bound denies reading CAT's credential file of the source home is refused with the
	 * refusal status, naming the file and the denial.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesCatCredentialThatBoundDenies() throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("source-home-")).toRealPath();
			Path source = SourceHomes.create(root, "source");
			Path file = SourceHomes.catCredential(source, SourceHomes.SETUP_TOKEN);
			Path denied = source.resolve(".cat");
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(),
				SourceHomes.create(root, "runner").toString());
			StringWriter errors = new StringWriter();

			int status = launchWithAuthentication(root, List.of("--home", source.toString()), environment, errors,
				List.of(denied));

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains(file.toString()).doesNotContain(SourceHomes.SETUP_TOKEN);
			requireThat(errors.toString().replace(file.toString(), ""), "denial").contains(denied.toString());
			requireThat(Files.exists(root.resolve("results")), "resultsFolderExists").isFalse();
		}
	}

	/**
	 * Verifies that a launch whose {@code --home} names a relative path, or an absolute path that is not a folder, is
	 * refused with the refusal status before it creates a nested session, naming the option.
	 *
	 * @param relative {@code true} to name a relative path, {@code false} to name a missing absolute folder
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(dataProvider = "relativeOrMissing")
	public void refusesInvalidSourceHome(boolean relative) throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("source-home-")).toRealPath();
			Path runnerHome = SourceHomes.create(root, "runner");
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(),
				runnerHome.toString(), "ANTHROPIC_API_KEY", SourceHomes.SECRET);
			String home;
			if (relative)
				home = "relative/home";
			else
				home = root.resolve("missing").toString();
			StringWriter errors = new StringWriter();

			int status = launchWithAuthentication(root, List.of("--home", home), environment, errors);

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains("--home").contains(home);
			requireThat(Files.exists(root.resolve("results")), "resultsFolderExists").isFalse();
		}
	}

	/**
	 * Returns whether a source home is named by a relative path rather than by a missing absolute one.
	 *
	 * @return the choices
	 */
	@DataProvider
	public static Object[][] relativeOrMissing()
	{
		return new Object[][]{{true}, {false}};
	}

	/**
	 * Verifies that a launch whose source settings send requests to {@code ANTHROPIC_BASE_URL} and that declares no
	 * class of login is refused with the refusal status, naming {@code --gateway-login-class} and the settings file,
	 * and that the message holds none of the source's secrets.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesBaseUrlWithoutLoginClass() throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("source-home-")).toRealPath();
			Path source = SourceHomes.create(root, "source");
			Path settings = SourceHomes.settings(source, """
				{"env": {"ANTHROPIC_BASE_URL": "https://gateway.example", "ANTHROPIC_AUTH_TOKEN": "%s"}}
				""".formatted(SourceHomes.SECRET));
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(),
				SourceHomes.create(root, "runner").toString(), "ANTHROPIC_API_KEY", SourceHomes.SECRET);
			StringWriter errors = new StringWriter();

			int status = launchWithAuthentication(root, List.of("--home", source.toString()), environment, errors);

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains("--gateway-login-class").contains(settings.toString()).
				doesNotContain(SourceHomes.SECRET);
			requireThat(Files.exists(root.resolve("results")), "resultsFolderExists").isFalse();
		}
	}

	/**
	 * Verifies that a launch that declares a class of login other than {@code subscription} and {@code api_key} is
	 * refused with the refusal status, naming {@code --gateway-login-class}.
	 *
	 * @param loginClass the declared class
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(dataProvider = "invalidLoginClasses")
	public void refusesInvalidLoginClass(String loginClass) throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("source-home-")).toRealPath();
			Path source = SourceHomes.create(root, "source");
			SourceHomes.settings(source, """
				{"apiKeyHelper": "printf key"}
				""");
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(),
				SourceHomes.create(root, "runner").toString(), "ANTHROPIC_API_KEY", SourceHomes.SECRET);
			StringWriter errors = new StringWriter();

			int status = launchWithAuthentication(root, List.of("--home", source.toString(), "--gateway-login-class",
				loginClass), environment, errors);

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains("--gateway-login-class").contains(loginClass);
			requireThat(Files.exists(root.resolve("results")), "resultsFolderExists").isFalse();
		}
	}

	/**
	 * Returns values of {@code --gateway-login-class} that name no class that a gateway bills.
	 *
	 * @return the values
	 */
	@DataProvider
	public static Object[][] invalidLoginClasses()
	{
		return new Object[][]{{"unknown"}, {"API_KEY"}, {"oauth"}};
	}

	/**
	 * Verifies that a launch whose source settings select a credential that the runner does not copy, a cloud provider
	 * or a profile, is refused with the refusal status, naming the setting and the settings file: the session would
	 * otherwise bill another credential than the user's own Claude Code.
	 *
	 * @param variable the {@code env} variable that selects the credential
	 * @param value    its value
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(dataProvider = "uncopiedCredentials")
	public void refusesUncopiedCredentialSetting(String variable, String value) throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("source-home-")).toRealPath();
			Path source = SourceHomes.create(root, "source");
			Path settings = SourceHomes.settings(source, """
				{"apiKeyHelper": "printf key", "env": {"%s": "%s"}}
				""".formatted(variable, value));
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(),
				SourceHomes.create(root, "runner").toString(), "ANTHROPIC_API_KEY", SourceHomes.SECRET);
			StringWriter errors = new StringWriter();

			int status = launchWithAuthentication(root, List.of("--home", source.toString()), environment, errors);

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains(variable).contains(settings.toString());
			requireThat(Files.exists(root.resolve("results")), "resultsFolderExists").isFalse();
		}
	}

	/**
	 * Returns source {@code env} settings that select a credential that the runner does not copy.
	 *
	 * @return the variables and their values
	 */
	@DataProvider
	public static Object[][] uncopiedCredentials()
	{
		return new Object[][]{{"CLAUDE_CODE_USE_BEDROCK", "1"}, {"ANTHROPIC_PROFILE", "work"}};
	}

	/**
	 * Verifies that a launch that continues a nested session's harness session takes the source home's checks before it
	 * reads the nested session, so that a source home without Claude authentication is refused the same way.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void resumedLaunchTakesSourceHomeChecks() throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("source-home-")).toRealPath();
			Path source = SourceHomes.create(root, "source");
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(),
				SourceHomes.create(root, "runner").toString(), "ANTHROPIC_API_KEY", SourceHomes.SECRET);
			StringWriter errors = new StringWriter();

			int status = launchWithAuthentication(root, List.of("--home", source.toString(), "--slot-id",
				NestedSessionManager.newSlotId(Clock.systemUTC()), "--resume-slot", SESSION_ID), environment, errors);

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains(source.resolve(".claude/settings.json").toString());
		}
	}

	/**
	 * Verifies that the runner that a request server asks for a child's needs takes the source home's checks, so that
	 * a source home without Claude authentication refuses the report with the refusal status, naming the files that
	 * the runner read, and writes no report.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void childNeedsTakeSourceHomeChecks() throws IOException
	{
		// Requirement: R-I14
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("source-home-")).toRealPath();
			Path source = SourceHomes.create(root, "source");
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(),
				SourceHomes.create(root, "runner").toString(), "ANTHROPIC_API_KEY", SourceHomes.SECRET);
			Path report = root.resolve("needs.json");
			StringWriter errors = new StringWriter();

			int status = ClaudeRunnerCli.reportChildNeeds(scope, environment, report, new AuthenticationOptions(
				Optional.of(source), Optional.empty(), false), SourceHomes.PLUGIN, new PrintWriter(errors, true));

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains(source.resolve(".claude/settings.json").toString());
			requireThat(Files.exists(report), "reportWritten").isFalse();
		}
	}

	/**
	 * Verifies that without {@code --home} the runner's own home folder is the source home, and that the runner's
	 * {@code CLAUDE_CONFIG_DIR} then names the source's Claude folder, as for the user's own Claude Code; and that with
	 * {@code --home} that variable changes nothing. Each source here holds no authentication, so the refusal names the
	 * files that the runner read.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void choosesSourceClaudeFolder() throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("source-home-")).toRealPath();
			Path runnerHome = SourceHomes.create(root, "runner");
			Path configured = Files.createDirectories(root.resolve("configured"));
			Path named = SourceHomes.create(root, "named");

			StringWriter defaultErrors = new StringWriter();
			int status = launchWithAuthentication(root, List.of(), Map.of("PATH", "",
				Platform.current().homeVariable(), runnerHome.toString()), defaultErrors);
			requireThat(status, "defaultStatus").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(defaultErrors.toString(), "defaultErrors").contains(
				runnerHome.resolve(".claude/settings.json").toString());

			StringWriter configuredErrors = new StringWriter();
			status = launchWithAuthentication(root, List.of(), Map.of("PATH", "", Platform.current().homeVariable(),
				runnerHome.toString(), "CLAUDE_CONFIG_DIR", configured.toString()), configuredErrors);
			requireThat(status, "configuredStatus").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(configuredErrors.toString(), "configuredErrors").
				contains(configured.resolve("settings.json").toString()).
				doesNotContain(runnerHome.resolve(".claude").toString());

			StringWriter namedErrors = new StringWriter();
			status = launchWithAuthentication(root, List.of("--home", named.toString()), Map.of("PATH", "",
				Platform.current().homeVariable(), runnerHome.toString(), "CLAUDE_CONFIG_DIR", configured.toString()),
				namedErrors);
			requireThat(status, "namedStatus").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(namedErrors.toString(), "namedErrors").contains(named.resolve(".claude/settings.json").toString()).
				doesNotContain(configured.toString());
		}
	}

	/**
	 * Verifies that a launch whose source home holds only an {@code apiKeyHelper} goes on to start its session although
	 * the Claude folder holds no login and the environment no credential, which the runner's earlier login check refused,
	 * as far as a test without a CAT artifact goes.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void startsLaunchWithOnlyKeyHelper() throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("source-home-")).toRealPath();
			Path home = SourceHomes.create(root, "runner");
			SourceHomes.secret(home.resolve("key"));
			SourceHomes.settings(home, """
				{"apiKeyHelper": "cat ~/key"}
				""");
			StringWriter errors = new StringWriter();

			String path = SandboxPrograms.path(root);
			IOException failure = expectThrows(IOException.class, () -> launchWithAuthentication(root, List.of(),
				Map.of("PATH", path, Platform.current().homeVariable(), home.toString()), errors));

			// The session starts and fails only because the test runs no CAT artifact
			requireThat(failure, "failure").isNotInstanceOf(SessionRefusedException.class);
			requireThat(errors.toString(), "errors").isEmpty();
		}
	}

	/**
	 * Verifies that a launch whose bound, which its starter names, denies reading a file that the runner reads from the
	 * source home is refused with the refusal status, naming the file and the denial, while the same launch passed on by
	 * a nested session's runner, whose bound denies those files by design, is not refused for them.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesSourceFileThatBoundDenies() throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("source-home-")).toRealPath();
			Path source = SourceHomes.create(root, "source");
			Path settings = SourceHomes.settings(source, """
				{"apiKeyHelper": "printf key"}
				""");
			Path denied = source.resolve(".claude");
			Map<String, String> environment = Map.of("PATH", SandboxPrograms.path(root),
				Platform.current().homeVariable(), SourceHomes.create(root, "runner").toString());
			StringWriter errors = new StringWriter();

			int status = launchWithAuthentication(root, List.of("--home", source.toString()), environment, errors,
				List.of(denied));

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains(settings.toString());
			// The denied folder's path begins the file's, so the denial is named only if it remains without the file's
			requireThat(errors.toString().replace(settings.toString(), ""), "denial").contains(denied.toString());
			requireThat(Files.exists(root.resolve("results")), "resultsFolderExists").isFalse();

			StringWriter inheritedErrors = new StringWriter();
			IOException failure = expectThrows(IOException.class, () -> launchWithAuthentication(root, List.of(
				"--home", source.toString(), "--inherited-source-home"), environment, inheritedErrors, List.of(denied)));
			// The session starts and fails only because the test runs no CAT artifact
			requireThat(failure, "failure").isNotInstanceOf(SessionRefusedException.class);
			requireThat(inheritedErrors.toString(), "inheritedErrors").isEmpty();
		}
	}

	/**
	 * Returns the environment of a runner whose own home folder is the source home of its launches, below a folder, and
	 * holds an {@code apiKeyHelper}, for a test whose subject is not the session's credential.
	 *
	 * @param root the folder that receives the home folder
	 * @return the environment
	 * @throws IOException if the home folder cannot be written
	 */
	private static Map<String, String> sourceHome(Path root) throws IOException
	{
		Path home = SourceHomes.create(root, "runner-home");
		SourceHomes.settings(home, """
			{"apiKeyHelper": "printf key"}
			""");
		return Map.of("PATH", "", Platform.current().homeVariable(), home.toString());
	}

	/**
	 * Runs a launch in this process, as CAT's own starter starts it with a bound that denies nothing and a capacity
	 * slot that the starter holds, and returns its exit status.
	 *
	 * @param root        the test's directory, which holds the project and the results folder
	 * @param options     the launch's options beyond its project, results folder, profile and bound
	 * @param environment the runner's environment
	 * @param errors      receives why the launch was refused
	 * @return the exit status
	 * @throws IOException if the launch fails without being refused
	 */
	private static int launchWithAuthentication(Path root, List<String> options, Map<String, String> environment,
		StringWriter errors) throws IOException
	{
		return launchWithAuthentication(root, options, environment, errors, List.of());
	}

	/**
	 * Runs a launch in this process, as CAT's own starter starts it with a bound and a capacity slot that the starter
	 * holds, and returns its exit status.
	 *
	 * @param root        the test's directory, which holds the project and the results folder
	 * @param options     the launch's options beyond its project, results folder, profile and bound
	 * @param environment the runner's environment
	 * @param errors      receives why the launch was refused
	 * @param readDenials the files and folders that the bound denies the session reading
	 * @return the exit status
	 * @throws IOException if the launch fails without being refused
	 */
	private static int launchWithAuthentication(Path root, List<String> options, Map<String, String> environment,
		StringWriter errors, List<Path> readDenials) throws IOException
	{
		Path project = Files.createDirectories(root.resolve("project"));
		JsonMapper json = JsonMapper.builder().build();
		List<String> denials = new ArrayList<>();
		for (Path denial : readDenials)
			denials.add(json.writeValueAsString(denial.toString()));
		Path bound = Files.writeString(root.resolve("authentication-bound.json"), """
			{"format_version": 1, "write_roots": [%s], "write_denials": [], "read_denials": [%s],
			 "read_reallowances": [], "hosts": ["api.anthropic.com", "gateway.example"], "allow_local_binding": false}
			""".formatted(json.writeValueAsString(root.toString()), String.join(", ", denials)));
		List<String> args = new ArrayList<>(List.of("--project", project.toString(), "--results",
			root.resolve("results").toString(), "--model", "sonnet", "--effort", "low",
			PolicyBoundFile.OPTION, bound.toString()));
		args.addAll(options);
		PrintWriter writer = new PrintWriter(errors, true);
		return HarnessRunnerOptions.refusingInvalidArguments(() -> ClaudeRunnerCli.launch(args.toArray(String[]::new),
			environment, Yielding.waiting(), new StringReader(""), new ByteArrayOutputStream(), writer,
			() -> root.resolve("plugin"), List::of, () ->
			{
				throw new AssertionError("No session's tool scope is opened");
			}), writer);
	}
}
