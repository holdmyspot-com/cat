package com.holdmyspot.cat.claude.runner.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import com.holdmyspot.cat.claude.runner.ClaudeRunnerCli;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessCapacityWaitExpiredException;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link ClaudeRunnerCli}.
 */
public final class ClaudeRunnerCliTest
{
	/**
	 * The name of the CAT agent definition in a test artifact.
	 */
	private static final String AGENT = "probe-agent";

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
					ClaudeRunnerCli.serve(jvm, root, args, new StringReader(""), new StringWriter(), Map.of(),
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
			// A start time that differs from the one of the process holding the recorded identifier means that the
			// identifier now belongs to another process, so the runner that created the run has ended
			Files.writeString(manifest, Files.readString(manifest).replaceFirst("\"owner_started_at\": \"[^\"]+\"",
				"\"owner_started_at\": \"2000-01-01T00:00:00Z\""));

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
			ClaudeRunnerCli.serve(jvm, root, args, new StringReader(""), new StringWriter(), Map.of(), _ ->
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
				"--effort", "low"}, environment, platform, new PrintWriter(errors, true));

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

		boolean helpRefused = ClaudeRunnerCli.refusesLaunch(new String[]{"--help"}, Map.of(), platform,
			new PrintWriter(errors, true));
		boolean verifyRefused = ClaudeRunnerCli.refusesLaunch(new String[]{"--verify-artifact", "artifact"}, Map.of(),
			platform, new PrintWriter(errors, true));

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
				"--effort", "low"}, Map.of(), platform, new PrintWriter(errors, true));

			requireThat(refused, "refused").withContext(platform, "platform").isFalse();
			requireThat(errors.toString(), "errors").withContext(platform, "platform").isEmpty();
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
	 * run, since only the session launcher starts a session and it always bounds the session's policy.
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
