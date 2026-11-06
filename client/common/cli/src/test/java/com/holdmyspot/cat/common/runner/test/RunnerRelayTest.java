package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.ParentSandbox;
import com.holdmyspot.cat.common.runner.RunnerRelay;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.ParentPolicySource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that a plugin's launcher for the other harness's runner refuses a launch that it must not run, runs the other
 * harness's CAT artifact, and forwards a launch inside a run, through {@link RunnerRelay}.
 */
public final class RunnerRelayTest
{
	/**
	 * A parent whose policy no forwarded launch reads.
	 */
	private static final ParentPolicySource NO_PARENT = new ParentPolicySource()
	{
		@Override
		public String harnessName()
		{
			return "Harness A";
		}

		@Override
		public ParentPolicy read()
		{
			throw new AssertionError("A forwarded launch reads no parent policy");
		}

		@Override
		public String permissionEntries(List<Path> writes, Set<String> hosts)
		{
			throw new AssertionError("A forwarded launch names no settings");
		}
	};

	/**
	 * Creates the test.
	 */
	public RunnerRelayTest()
	{
	}

	/**
	 * Verifies that the launcher runs the selected artifact's runner, which runs its own artifact, with only the
	 * caller's options.
	 */
	@Test
	public void runsSelectedArtifactRunner()
	{
		// Requirement: R-I9, R-I11
		Path runner = Path.of("/other-artifact/bin/harness-b-runner").toAbsolutePath();

		List<String> command = RunnerRelay.command(runner, List.of("--project", "project", "--input",
			"turns.jsonl"));

		requireThat(command, "command").isEqualTo(List.of(runner.toString(), "--project", "project", "--input",
			"turns.jsonl"));
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
	 * Verifies that outside a run the launcher refuses an option that would choose the artifact it runs, with a
	 * reason that names the runner and the option.
	 *
	 * @param option the refused option
	 */
	@Test(dataProvider = "overrides")
	public void refusesOverride(String option)
	{
		Optional<String> refusal = RunnerRelay.refusal("harness-b", new String[]{"--project", "project", option,
			"elsewhere"}, Map.of());

		requireThat(refusal.orElseThrow(), "refusal").contains("harness-b-runner").contains(option).
			contains("its own CAT artifact").contains("PATH");
	}

	/**
	 * Verifies that when the Codex sandbox that holds the launcher has network off, the launcher refuses the launch
	 * with a reason that names the setting that enables network, even inside a run that may request sessions.
	 */
	@Test
	public void refusesWhenCodexSandboxHasNoNetwork()
	{
		for (Map<String, String> environment : List.of(Map.of(ParentSandbox.NETWORK_DISABLED_VARIABLE, "1"),
			Map.of(ParentSandbox.NETWORK_DISABLED_VARIABLE, "1", RunnerRelay.REQUESTS_VARIABLE, "/run/requests")))
		{
			Optional<String> refusal = RunnerRelay.refusal("harness-b", new String[]{"--project", "project"},
				environment);

			requireThat(refusal.orElseThrow(), "refusal").contains("harness-b-runner").
				contains("sandbox_workspace_write.network_access = true");
		}
	}

	/**
	 * Verifies that inside a CAT run whose runner lets its session request no sessions, the launcher refuses the
	 * launch rather than starting a session in place, naming the option that lets the run's session request sessions.
	 */
	@Test
	public void refusesInRunWithoutRequests()
	{
		Optional<String> refusal = RunnerRelay.refusal("harness-b", new String[]{"--project", "project"},
			Map.of(HarnessEnvironment.RUN_VARIABLE, HarnessEnvironment.RUN_VALUE));

		requireThat(refusal.orElseThrow(), "refusal").startsWith("harness-b-runner refuses to start a session").
			contains("--session-requests");
	}

	/**
	 * Verifies that the launcher lets a launch outside a run that names no refused option go on, and a launch inside
	 * a run that may request sessions, which the run's runner validates.
	 */
	@Test
	public void letsPermittedLaunchGoOn()
	{
		requireThat(RunnerRelay.refusal("harness-b", new String[]{"--project", "project"}, Map.of()), "outsideRun").
			isEmpty();
		requireThat(RunnerRelay.refusal("harness-b", new String[]{"--project", "project"},
			Map.of(HarnessEnvironment.RUN_VARIABLE, HarnessEnvironment.RUN_VALUE, RunnerRelay.REQUESTS_VARIABLE,
				"/run/requests")), "insideRun").isEmpty();
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

			int status = RunnerRelay.relay("harness-b", new String[]{"--project", project.toString(), "--model",
				"model-b", "--effort", "effort-b"},
				Map.of(HarnessEnvironment.RUN_VARIABLE, HarnessEnvironment.RUN_VALUE, RunnerRelay.REQUESTS_VARIABLE,
					requests.toString()), NO_PARENT, () ->
				{
					throw new AssertionError("No artifact is selected");
				}, (_, _) ->
				{
					throw new AssertionError("No runner starts");
				}, new StringReader("{\"type\":\"turn\",\"prompt\":\"task\"}\n{\"type\":\"complete\"}\n"),
				_ -> () -> answer(requests), new ByteArrayOutputStream(), new PrintWriter(new StringWriter()));

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
