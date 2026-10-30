package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
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
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
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
	 * Creates the test.
	 */
	public RunnerRelayTest()
	{
	}

	/**
	 * Verifies that outside a run the launcher runs the selected artifact's runner with that artifact and the caller's
	 * options, and exits with the runner's status.
	 *
	 * @throws IOException if the relay fails
	 */
	@Test
	public void runsSelectedArtifactRunner() throws IOException
	{
		// Requirement: R-I9
		Path artifact = Path.of("/other-artifact").toAbsolutePath();
		Path runner = artifact.resolve("bin/harness-b-runner");
		List<List<String>> commands = new ArrayList<>();

		int status = RunnerRelay.relay("harness-b", new String[]{"--project", "project", "--input", "turns.jsonl"},
			Map.of("PATH", "programs"), () -> "artifact=" + artifact + "\nrunner=" + runner + "\nsource=installed\n",
			(command, environment) ->
			{
				commands.add(command);
				requireThat(environment.get("PATH"), "path").isEqualTo("programs");
				return 7;
			}, new StringReader(""), NO_PAUSE, new ByteArrayOutputStream(), new PrintWriter(new StringWriter()));

		requireThat(status, "status").isEqualTo(7);
		requireThat(commands, "commands").isEqualTo(List.of(List.of(runner.toString(), "--artifact",
			artifact.toString(), "--project", "project", "--input", "turns.jsonl")));
	}

	/**
	 * Verifies that the launcher refuses an artifact the caller names, since it runs only the artifact that it selects.
	 *
	 * @throws IOException if the relay fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*--artifact.*")
	public void refusesNamedArtifact() throws IOException
	{
		// Requirement: R-I9
		RunnerRelay.relay("harness-b", new String[]{"--artifact", "elsewhere"}, Map.of(), () ->
			{
				throw new AssertionError("No artifact is selected");
			}, (_, _) ->
			{
				throw new AssertionError("No runner starts");
			}, new StringReader(""), NO_PAUSE, new ByteArrayOutputStream(), new PrintWriter(new StringWriter()));
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
			// Requirement: R-I9
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "relay-")).toRealPath();
			Path requests = Files.createDirectories(root.resolve("run/requests"));
			Path project = Files.createDirectory(root.resolve("project"));

			int status = RunnerRelay.relay("harness-b", new String[]{"--project", project.toString()},
				Map.of(RunnerRelay.REQUESTS_VARIABLE, requests.toString()), () ->
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
