package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.HarnessMarkers;
import com.holdmyspot.cat.common.runner.ParentSandbox;
import com.holdmyspot.cat.common.runner.RunnerRelay;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that a plugin's launcher for the other harness's runner runs that harness's CAT artifact, or forwards the
 * launch inside a run, through {@link RunnerRelay}.
 */
public final class RunnerRelayTest
{
	/**
	 * A pause for launches outside a run, which never wait for a requested session.
	 */
	private static final Pause NO_PAUSE = () ->
	{
		throw new AssertionError("Nothing waits outside a run");
	};

	/**
	 * A bound that no launch may reach.
	 */
	private static final RunnerRelay.LaunchBound NO_BOUND = (_, _, _) ->
	{
		throw new AssertionError("No launch is bounded");
	};

	/**
	 * The variable that marks a command of the plugin's own harness.
	 */
	private static final String OWN_MARKER = "HARNESS_A_SESSION";
	/**
	 * The variable that marks a command of the other harness.
	 */
	private static final String OTHER_MARKER = "HARNESS_B_THREAD";
	/**
	 * The markers of the plugin's own harness and of the other harness.
	 */
	private static final HarnessMarkers MARKERS = new HarnessMarkers(List.of(
		HarnessMarkers.Marker.equalTo("harness-a", OWN_MARKER, "1"),
		HarnessMarkers.Marker.notBlank("harness-b", OTHER_MARKER)));
	/**
	 * The environment of a command that the plugin's own harness runs.
	 */
	private static final Map<String, String> OWN_HARNESS = Map.of(OWN_MARKER, "1");

	/**
	 * Creates the test.
	 */
	public RunnerRelayTest()
	{
	}

	/**
	 * Verifies that on native Windows a launch from a command of the plugin's own harness is bounded by nothing here,
	 * but runs the selected artifact's runner, which refuses it on its own platform, with only the caller's options
	 * and environment, and exits with the runner's status.
	 *
	 * @throws IOException if the relay fails
	 */
	@Test
	public void runsSelectedArtifactRunnerOnWindows() throws IOException
	{
		// Requirement: R-I9, R-I11
		Path artifact = Path.of("/other-artifact").toAbsolutePath();
		Path runner = artifact.resolve("bin/harness-b-runner");
		List<List<String>> commands = new ArrayList<>();

		int status = RunnerRelay.relay("harness-b", "harness-a", MARKERS, new String[]{"--project", "project",
				"--input", "turns.jsonl"}, Map.of("PATH", "programs", OWN_MARKER, "1"), Platform.WINDOWS_X64,
			() -> "artifact=" + artifact + "\nrunner=" + runner + "\nsource=installed\n",
			(command, environment) ->
			{
				commands.add(command);
				requireThat(environment, "environment").isEqualTo(Map.of("PATH", "programs", OWN_MARKER, "1"));
				return 7;
			}, NO_BOUND, new StringReader(""), NO_PAUSE, new ByteArrayOutputStream(),
			new PrintWriter(new StringWriter()));

		requireThat(status, "status").isEqualTo(7);
		requireThat(commands, "commands").isEqualTo(List.of(List.of(runner.toString(), "--project", "project",
			"--input", "turns.jsonl")));
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
	 * Verifies that outside a run the launcher refuses an option that would choose the artifact it runs, exiting with
	 * status 2 and the reason before it selects an artifact, starts a runner or writes output.
	 *
	 * @param option the refused option
	 * @throws IOException if the relay fails
	 */
	@Test(dataProvider = "overrides")
	public void refusesOverride(String option) throws IOException
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		StringWriter errors = new StringWriter();

		int status = RunnerRelay.relay("harness-b", "harness-a", MARKERS, new String[]{"--project", "project",
				option, "elsewhere"}, OWN_HARNESS, Platform.LINUX_X64, () ->
			{
				throw new AssertionError("No artifact is selected");
			}, (_, _) ->
			{
				throw new AssertionError("No runner starts");
			}, NO_BOUND, new StringReader(""), NO_PAUSE, output, new PrintWriter(errors, true));

		requireThat(status, "status").isEqualTo(2);
		requireThat(errors.toString(), "errors").contains("harness-b-runner").contains(option).
			contains("its own CAT artifact").contains("PATH");
		requireThat(output.size(), "outputBytes").isEqualTo(0);
	}

	/**
	 * Verifies that when the Codex sandbox that holds the launcher has network off, the launcher exits with status 2
	 * and a reason that names the setting that enables network, before it selects an artifact, starts a runner or
	 * writes output.
	 *
	 * @throws IOException if the relay fails
	 */
	@Test
	public void refusesWhenCodexSandboxHasNoNetwork() throws IOException
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		StringWriter errors = new StringWriter();

		int status = RunnerRelay.relay("harness-b", "harness-a", MARKERS, new String[]{"--project", "project"},
			Map.of(ParentSandbox.NETWORK_DISABLED_VARIABLE, "1", OWN_MARKER, "1"), Platform.LINUX_X64, () ->
			{
				throw new AssertionError("No artifact is selected");
			}, (_, _) ->
			{
				throw new AssertionError("No runner starts");
			}, NO_BOUND, new StringReader(""), NO_PAUSE, output, new PrintWriter(errors, true));

		requireThat(status, "status").isEqualTo(2);
		requireThat(errors.toString(), "errors").contains("harness-b-runner").
			contains("sandbox_workspace_write.network_access = true");
		requireThat(output.size(), "outputBytes").isEqualTo(0);
	}

	/**
	 * Verifies that outside a run, except on native Windows, a launch from a command of the plugin's own harness is
	 * bounded by that session's policy, and the selected artifact's runner runs the bounded launch with an
	 * environment that names no harness, so that it serves the session within that bound, and the launcher exits with
	 * the runner's status.
	 *
	 * @throws IOException if the relay fails
	 */
	@Test
	public void runsBoundedLaunchFromOwnHarness() throws IOException
	{
		Path artifact = Path.of("/other-artifact").toAbsolutePath();
		Path runner = artifact.resolve("bin/harness-b-runner");
		List<Path> boundRunners = new ArrayList<>();
		List<List<String>> commands = new ArrayList<>();

		int status = RunnerRelay.relay("harness-b", "harness-a", MARKERS, new String[]{"--project", "project"},
			Map.of("PATH", "programs", OWN_MARKER, "1", OTHER_MARKER, "thread"), Platform.LINUX_X64,
			() -> "artifact=" + artifact + "\nrunner=" + runner + "\nsource=installed\n",
			(command, environment) ->
			{
				commands.add(command);
				requireThat(environment, "environment").isEqualTo(Map.of("PATH", "programs"));
				return 75;
			}, (boundRunner, args, _) ->
			{
				boundRunners.add(boundRunner);
				List<String> bounded = new ArrayList<>(List.of(args));
				bounded.addAll(List.of("--policy-bound", "bound.json"));
				return bounded;
			}, new StringReader(""), NO_PAUSE, new ByteArrayOutputStream(), new PrintWriter(new StringWriter()));

		requireThat(status, "status").isEqualTo(75);
		requireThat(boundRunners, "boundRunners").isEqualTo(List.of(runner));
		requireThat(commands, "commands").isEqualTo(List.of(List.of(runner.toString(), "--project", "project",
			"--policy-bound", "bound.json")));
	}

	/**
	 * Verifies that a launch from a command of the plugin's own harness that fails a check of its bound is refused
	 * with status 2 and the check's reason, before any runner starts.
	 *
	 * @throws IOException if the relay fails
	 */
	@Test
	public void refusesLaunchThatFailsItsBound() throws IOException
	{
		Path artifact = Path.of("/other-artifact").toAbsolutePath();
		Path runner = artifact.resolve("bin/harness-b-runner");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		StringWriter errors = new StringWriter();

		int status = RunnerRelay.relay("harness-b", "harness-a", MARKERS, new String[]{"--project", "project"},
			OWN_HARNESS, Platform.LINUX_X64,
			() -> "artifact=" + artifact + "\nrunner=" + runner + "\nsource=installed\n", (_, _) ->
			{
				throw new AssertionError("No runner starts");
			}, (_, _, _) ->
			{
				throw new SessionRefusedException("CAT cannot start the session: the check failed.");
			}, new StringReader(""), NO_PAUSE, output, new PrintWriter(errors, true));

		requireThat(status, "status").isEqualTo(2);
		requireThat(errors.toString().strip(), "errors").isEqualTo("CAT cannot start the session: the check failed.");
		requireThat(output.size(), "outputBytes").isEqualTo(0);
	}

	/**
	 * Verifies that a launch from a command of a harness session that names an option that only CAT's own starters
	 * pass is refused with status 2, naming the option, before the launcher selects an artifact, bounds the launch or
	 * starts a runner.
	 *
	 * @throws IOException if the relay fails
	 */
	@Test
	public void refusesInternalOptionFromHarnessSession() throws IOException
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		StringWriter errors = new StringWriter();

		int status = RunnerRelay.relay("harness-b", "harness-a", MARKERS, new String[]{"--policy-bound",
				"policy.json"}, OWN_HARNESS, Platform.LINUX_X64, () ->
			{
				throw new AssertionError("No artifact is selected");
			}, (_, _) ->
			{
				throw new AssertionError("No runner starts");
			}, NO_BOUND, new StringReader(""), NO_PAUSE, output, new PrintWriter(errors, true));

		requireThat(status, "status").isEqualTo(2);
		requireThat(errors.toString(), "errors").contains("--policy-bound").contains("without --policy-bound");
		requireThat(output.size(), "outputBytes").isEqualTo(0);
	}

	/**
	 * Verifies that a launch that CAT's own starter bounds, from a command that no harness runs, runs the selected
	 * artifact's runner in place with the caller's options.
	 *
	 * @throws IOException if the relay fails
	 */
	@Test
	public void runsStarterBoundedLaunchInPlace() throws IOException
	{
		Path artifact = Path.of("/other-artifact").toAbsolutePath();
		Path runner = artifact.resolve("bin/harness-b-runner");
		List<List<String>> commands = new ArrayList<>();

		int status = RunnerRelay.relay("harness-b", "harness-a", MARKERS, new String[]{"--policy-bound",
				"policy.json"}, Map.of(), Platform.LINUX_X64,
			() -> "artifact=" + artifact + "\nrunner=" + runner + "\nsource=installed\n",
			(command, _) ->
			{
				commands.add(command);
				return 0;
			}, NO_BOUND, new StringReader(""), NO_PAUSE, new ByteArrayOutputStream(),
			new PrintWriter(new StringWriter()));

		requireThat(status, "status").isEqualTo(0);
		requireThat(commands, "commands").isEqualTo(List.of(List.of(runner.toString(), "--policy-bound",
			"policy.json")));
	}

	/**
	 * Verifies that a launch from a command that no harness runs runs the selected artifact's runner with only the
	 * caller's options, which starts the session in place, without bounding it here.
	 *
	 * @throws IOException if the relay fails
	 */
	@Test
	public void runsUnmarkedLaunchInPlace() throws IOException
	{
		Path artifact = Path.of("/other-artifact").toAbsolutePath();
		Path runner = artifact.resolve("bin/harness-b-runner");
		List<List<String>> commands = new ArrayList<>();

		int status = RunnerRelay.relay("harness-b", "harness-a", MARKERS, new String[]{"--project", "project"},
			Map.of(), Platform.LINUX_X64,
			() -> "artifact=" + artifact + "\nrunner=" + runner + "\nsource=installed\n",
			(command, _) ->
			{
				commands.add(command);
				return 0;
			}, NO_BOUND, new StringReader(""), NO_PAUSE, new ByteArrayOutputStream(),
			new PrintWriter(new StringWriter()));

		requireThat(status, "status").isEqualTo(0);
		requireThat(commands, "commands").isEqualTo(List.of(List.of(runner.toString(), "--project", "project")));
	}

	/**
	 * Verifies that a launch from a command that only the other harness runs is refused with status 2, naming the
	 * plugin to use, before the launcher selects an artifact, starts a runner or sends a request.
	 *
	 * @throws IOException if the relay fails
	 */
	@Test
	public void refusesOtherHarness() throws IOException
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		StringWriter errors = new StringWriter();

		int status = RunnerRelay.relay("harness-b", "harness-a", MARKERS, new String[]{"--project", "project"},
			Map.of(OTHER_MARKER, "thread"), Platform.LINUX_X64, () ->
			{
				throw new AssertionError("No artifact is selected");
			}, (_, _) ->
			{
				throw new AssertionError("No runner starts");
			}, NO_BOUND, new StringReader(""), NO_PAUSE, output, new PrintWriter(errors, true));

		requireThat(status, "status").isEqualTo(2);
		requireThat(errors.toString(), "errors").contains("harness-b runs this command").
			contains("Run the runner of CAT's harness-b plugin instead");
		requireThat(output.size(), "outputBytes").isEqualTo(0);
	}

	/**
	 * Verifies that inside a run whose runner lets its session request no sessions, a launch is refused with status 2
	 * and the option that lets it, before the launcher selects an artifact, starts a runner or sends a request.
	 *
	 * @throws IOException if the relay fails
	 */
	@Test
	public void refusesInsideRunWithoutRequests() throws IOException
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		StringWriter errors = new StringWriter();

		int status = RunnerRelay.relay("harness-b", "harness-a", MARKERS, new String[]{"--project", "project"},
			Map.of(HarnessEnvironment.RUN_VARIABLE, HarnessEnvironment.RUN_VALUE, OWN_MARKER, "1"),
			Platform.LINUX_X64, () ->
			{
				throw new AssertionError("No artifact is selected");
			}, (_, _) ->
			{
				throw new AssertionError("No runner starts");
			}, NO_BOUND, new StringReader(""), NO_PAUSE, output, new PrintWriter(errors, true));

		requireThat(status, "status").isEqualTo(2);
		requireThat(errors.toString(), "errors").contains("harness-b-runner refuses to start a session").
			contains("--session-requests");
		requireThat(output.size(), "outputBytes").isEqualTo(0);
	}

	/**
	 * Verifies that inside a run the launcher selects no artifact and starts no runner, but requests a session of its
	 * harness from the run's runner.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void forwardsInsideRun() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			// Requirement: R-I9, R-I11
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "relay-")).toRealPath();
			Path requests = Files.createDirectories(root.resolve("run/requests"));
			Path project = Files.createDirectory(root.resolve("project"));

			int status = RunnerRelay.relay("harness-b", "harness-a", MARKERS, new String[]{"--project",
					project.toString(), "--model", "model-b", "--effort", "effort-b"},
				Map.of(RunnerRelay.REQUESTS_VARIABLE, requests.toString(), OWN_MARKER, "1"), Platform.LINUX_X64, () ->
				{
					throw new AssertionError("No artifact is selected");
				}, (_, _) ->
				{
					throw new AssertionError("No runner starts");
				}, NO_BOUND, new StringReader("{\"type\":\"turn\",\"prompt\":\"task\"}\n{\"type\":\"complete\"}\n"),
				() -> answer(requests), new ByteArrayOutputStream(), new PrintWriter(new StringWriter()));

			requireThat(status, "status").isEqualTo(0);
		}
	}

	/**
	 * Answers the only request as a fake parent runner would: it completes a request for {@code harness-b} and refuses
	 * any other.
	 *
	 * @param requests the run's requests directory
	 * @throws IOException if the request cannot be read or the response written
	 */
	private static void answer(Path requests) throws IOException
	{
		Path file;
		try (Stream<Path> files = Files.list(requests))
		{
			file = files.filter(path -> path.getFileName().toString().endsWith(".tar")).findFirst().
				orElseThrow(() -> new IOException("No request"));
		}
		String harness = "";
		try (InputStream bytes = Files.newInputStream(file);
			TarArchiveInputStream archive = new TarArchiveInputStream(bytes))
		{
			for (TarArchiveEntry entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry())
			{
				if (entry.getName().equals("request.json"))
				{
					harness = JsonMapper.builder().build().readTree(new String(archive.readAllBytes(),
						StandardCharsets.UTF_8)).path("harness").asString();
				}
			}
		}
		String status = "refused";
		if (harness.equals("harness-b"))
			status = "completed";
		String id = file.getFileName().toString().replace(".tar", "");
		Path response = Files.createDirectories(requests.resolveSibling("responses").resolve(id));
		Files.writeString(response.resolve("response.json"), "{\"status\":\"" + status + "\"}");
	}
}
