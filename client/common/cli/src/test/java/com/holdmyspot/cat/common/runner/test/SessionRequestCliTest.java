package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.SessionRequestCli;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import java.time.Duration;
import java.time.Instant;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import java.util.stream.Stream;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests file submission, bounded waiting, and resuming a request through {@link SessionRequestCli}.
 */
public final class SessionRequestCliTest
{
	/**
	 * Creates the test.
	 */
	public SessionRequestCliTest()
	{
	}

	/**
	 * Verifies that a submission becomes a versioned request and times out with an actionable await command.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void submitsThenAwaits() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I9
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "request-"));
				Path requests = Files.createDirectory(root.resolve("requests"));
				ManualClock time = new ManualClock(Instant.EPOCH);
				StringWriter output = new StringWriter();
				int status = SessionRequestCli.execute(new String[]{"--harness", "harness-a", "--project", root.toString(),
					"--prompt", root.resolve("prompt").toString()}, requests, time,
					() -> time.advance(Duration.ofSeconds(300)), new PrintWriter(output));
				requireThat(status, "status").isEqualTo(3);
				requireThat(output.toString(), "output").contains("--await");
				try (Stream<Path> files = Files.list(requests))
				{
					Path file = files.findFirst().orElseThrow();
					JsonNode request = JsonMapper.builder().build().readTree(file.toFile());
					requireThat(request.path("harness").asString(), "harness").isEqualTo("harness-a");
					requireThat(request.path("prompts").size(), "prompts").isEqualTo(1);
					String id = file.getFileName().toString().replace(".json", "");
					Path response = Files.createDirectories(root.resolve("responses").resolve(id)).resolve("response.json");
					Files.writeString(response, "{\"status\":\"completed\"}");
					StringWriter resumed = new StringWriter();
					requireThat(SessionRequestCli.execute(new String[]{"--await", id}, requests, time,
						() ->
						{
							throw new AssertionError("Response exists");
						}, new PrintWriter(resumed)), "resumed").isEqualTo(0);
					requireThat(resumed.toString().strip(), "response").isEqualTo(response.toString());
					Files.writeString(response, "{\"status\":\"refused\"}");
					requireThat(SessionRequestCli.execute(new String[]{"--await", id}, requests, time,
						() ->
						{
							throw new AssertionError("Response exists");
						}, new PrintWriter(new StringWriter())),
						"refused").isEqualTo(1);
				}
			}
		}
	}
	/**
	 * Verifies that awaiting an existing request rejects submission options.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*--await.*")
	public void rejectsMixedAwaitOptions() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I9
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "await-"));
				Path requests = Files.createDirectory(root.resolve("requests"));
				Files.writeString(Files.createDirectories(root.resolve("responses/first")).resolve("response.json"),
					"{\"status\":\"completed\"}");
				SessionRequestCli.execute(new String[]{"--await", "first", "--project", root.toString()}, requests,
					new ManualClock(Instant.EPOCH), Thread::yield, new PrintWriter(new StringWriter()));
			}
		}
	}
}
