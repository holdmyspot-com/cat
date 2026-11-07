package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.ParentSandbox;
import com.holdmyspot.cat.common.runner.RunnerRelay;
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
import java.util.Set;
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
	 * A session launcher that no launch may reach.
	 */
	private static final RunnerRelay.SessionLaunch NO_REQUEST = (_, _, _, _, _) ->
	{
		throw new AssertionError("No request is sent to the session launcher");
	};

	/**
	 * The harnesses that run the command when the plugin's own harness runs it.
	 */
	private static final Set<String> OWN_HARNESS = Set.of("harness-a");

	/**
	 * Creates the test.
	 */
	public RunnerRelayTest()
	{
	}

	/**
	 * Verifies that on native Windows, which has no session launcher, a launch from a command of the plugin's own
	 * harness sends no request but runs the selected artifact's runner, which runs its own artifact, with only the
	 * caller's options, and exits with the runner's status.
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

		int status = RunnerRelay.relay("harness-b", "harness-a", OWN_HARNESS, new String[]{"--project", "project",
				"--input", "turns.jsonl"},
			Map.of("PATH", "programs"), Platform.WINDOWS_X64, NO_REQUEST,
			() -> "artifact=" + artifact + "\nrunner=" + runner + "\nsource=installed\n",
			(command, environment) ->
			{
				commands.add(command);
				requireThat(environment.get("PATH"), "path").isEqualTo("programs");
				return 7;
			}, new StringReader(""), NO_PAUSE, new ByteArrayOutputStream(), new PrintWriter(new StringWriter()));

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

		int status = RunnerRelay.relay("harness-b", "harness-a", OWN_HARNESS, new String[]{"--project", "project",
				option, "elsewhere"}, Map.of(), Platform.LINUX_X64, NO_REQUEST, () ->
			{
				throw new AssertionError("No artifact is selected");
			}, (_, _) ->
			{
				throw new AssertionError("No runner starts");
			}, new StringReader(""), NO_PAUSE, output, new PrintWriter(errors, true));

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

		int status = RunnerRelay.relay("harness-b", "harness-a", OWN_HARNESS, new String[]{"--project", "project"},
			Map.of(ParentSandbox.NETWORK_DISABLED_VARIABLE, "1"), Platform.LINUX_X64, NO_REQUEST, () ->
			{
				throw new AssertionError("No artifact is selected");
			}, (_, _) ->
			{
				throw new AssertionError("No runner starts");
			}, new StringReader(""), NO_PAUSE, output, new PrintWriter(errors, true));

		requireThat(status, "status").isEqualTo(2);
		requireThat(errors.toString(), "errors").contains("harness-b-runner").
			contains("sandbox_workspace_write.network_access = true");
		requireThat(output.size(), "outputBytes").isEqualTo(0);
	}

	/**
	 * Verifies that outside a run, except on native Windows, a launch from a command of the plugin's own harness
	 * selects no artifact and starts no runner, but goes to the session launcher, and the launcher exits with its
	 * status.
	 *
	 * @throws IOException if the relay fails
	 */
	@Test
	public void sendsLaunchToSessionLauncher() throws IOException
	{
		List<List<String>> launches = new ArrayList<>();

		int status = RunnerRelay.relay("harness-b", "harness-a", OWN_HARNESS, new String[]{"--project", "project"},
			Map.of(), Platform.LINUX_X64, (harness, args, _, _, _) ->
			{
				List<String> launch = new ArrayList<>(List.of(harness));
				launch.addAll(List.of(args));
				launches.add(launch);
				return 75;
			}, () ->
			{
				throw new AssertionError("No artifact is selected");
			}, (_, _) ->
			{
				throw new AssertionError("No runner starts");
			}, new StringReader(""), NO_PAUSE, new ByteArrayOutputStream(), new PrintWriter(new StringWriter()));

		requireThat(status, "status").isEqualTo(75);
		requireThat(launches, "launches").isEqualTo(List.of(List.of("harness-b", "--project", "project")));
	}

	/**
	 * Verifies that a launch that a session launcher started runs the selected artifact's runner in place, without
	 * sending another request, though the plugin's own harness runs the command.
	 *
	 * @throws IOException if the relay fails
	 */
	@Test
	public void runsLauncherChildInPlace() throws IOException
	{
		Path artifact = Path.of("/other-artifact").toAbsolutePath();
		Path runner = artifact.resolve("bin/harness-b-runner");
		List<List<String>> commands = new ArrayList<>();

		int status = RunnerRelay.relay("harness-b", "harness-a", OWN_HARNESS, new String[]{"--policy-bound",
				"policy.json"}, Map.of(),
			Platform.LINUX_X64, NO_REQUEST,
			() -> "artifact=" + artifact + "\nrunner=" + runner + "\nsource=installed\n",
			(command, _) ->
			{
				commands.add(command);
				return 0;
			}, new StringReader(""), NO_PAUSE, new ByteArrayOutputStream(), new PrintWriter(new StringWriter()));

		requireThat(status, "status").isEqualTo(0);
		requireThat(commands, "commands").isEqualTo(List.of(List.of(runner.toString(), "--policy-bound",
			"policy.json")));
	}

	/**
	 * Verifies that a launch from a command that no harness runs runs the selected artifact's runner with only the
	 * caller's options, which starts the session in place, without sending a request.
	 *
	 * @throws IOException if the relay fails
	 */
	@Test
	public void runsUnmarkedLaunchInPlace() throws IOException
	{
		Path artifact = Path.of("/other-artifact").toAbsolutePath();
		Path runner = artifact.resolve("bin/harness-b-runner");
		List<List<String>> commands = new ArrayList<>();

		int status = RunnerRelay.relay("harness-b", "harness-a", Set.of(), new String[]{"--project", "project"},
			Map.of(), Platform.LINUX_X64, NO_REQUEST,
			() -> "artifact=" + artifact + "\nrunner=" + runner + "\nsource=installed\n",
			(command, _) ->
			{
				commands.add(command);
				return 0;
			}, new StringReader(""), NO_PAUSE, new ByteArrayOutputStream(), new PrintWriter(new StringWriter()));

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

		int status = RunnerRelay.relay("harness-b", "harness-a", Set.of("harness-b"),
			new String[]{"--project", "project"}, Map.of(), Platform.LINUX_X64, NO_REQUEST, () ->
			{
				throw new AssertionError("No artifact is selected");
			}, (_, _) ->
			{
				throw new AssertionError("No runner starts");
			}, new StringReader(""), NO_PAUSE, output, new PrintWriter(errors, true));

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

		int status = RunnerRelay.relay("harness-b", "harness-a", OWN_HARNESS, new String[]{"--project", "project"},
			Map.of(HarnessEnvironment.RUN_VARIABLE, HarnessEnvironment.RUN_VALUE), Platform.LINUX_X64, NO_REQUEST,
			() ->
			{
				throw new AssertionError("No artifact is selected");
			}, (_, _) ->
			{
				throw new AssertionError("No runner starts");
			}, new StringReader(""), NO_PAUSE, output, new PrintWriter(errors, true));

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

			int status = RunnerRelay.relay("harness-b", "harness-a", OWN_HARNESS, new String[]{"--project",
					project.toString(), "--model", "model-b", "--effort", "effort-b"},
				Map.of(RunnerRelay.REQUESTS_VARIABLE, requests.toString()), Platform.LINUX_X64, NO_REQUEST, () ->
				{
					throw new AssertionError("No artifact is selected");
				}, (_, _) ->
				{
					throw new AssertionError("No runner starts");
				}, new StringReader("{\"type\":\"turn\",\"prompt\":\"task\"}\n{\"type\":\"complete\"}\n"),
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
