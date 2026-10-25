package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that a runner launched inside a run hands its launch to the parent runner through
 * {@link SessionRequestForwarder}.
 */
public final class SessionRequestForwarderTest
{
	private static final String RECORDS = """
		{"type":"turn","prompt":"first task"}
		{"type":"turn","prompt":"second task"}
		{"type":"complete"}
		""";

	/**
	 * Creates the test.
	 */
	public SessionRequestForwarderTest()
	{
	}

	/**
	 * Verifies that the launch reaches the parent as a request for the launcher's harness with the turns in order, and
	 * that the requested session's transcript and receipt come back as the runner's own output.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void forwardsLaunchAndReturnsEvidence() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			// Requirement: R-I9
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "forward-")).
				toRealPath());
			Path receipt = fixture.root.resolve("evidence/receipt.json");
			ByteArrayOutputStream output = new ByteArrayOutputStream();

			int status = fixture.forward("harness-a", new String[]{"--project", fixture.project.toString(), "--model",
				"model-a", "--install-cat", "--receipt", receipt.toString()}, new StringReader(RECORDS), output,
				"completed");

			requireThat(status, "status").isEqualTo(0);
			requireThat(fixture.entries.get("prompts/0"), "first").isEqualTo("first task");
			requireThat(fixture.entries.get("prompts/1"), "second").isEqualTo("second task");
			requireThat(fixture.entries.get("project/marker"), "project").isEqualTo("project bytes");
			JsonNode request = JsonMapper.builder().build().readTree(fixture.entries.get("request.json"));
			requireThat(request.path("harness").asString(), "harness").isEqualTo("harness-a");
			requireThat(request.path("model").asString(), "model").isEqualTo("model-a");
			requireThat(request.path("install_cat").asBoolean(false), "installCat").isTrue();
			requireThat(output.toString(StandardCharsets.UTF_8), "transcript").isEqualTo(Fixture.TRANSCRIPT);
			requireThat(Files.readString(receipt), "receipt").isEqualTo(Fixture.RECEIPT);
		}
	}

	/**
	 * Verifies that the records may come from an input file and that the transcript goes to the named output file.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void readsInputFileAndWritesOutputFile() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			// Requirement: R-I9
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "forward-")).
				toRealPath());
			Path input = Files.writeString(fixture.root.resolve("records.jsonl"), RECORDS);
			Path transcript = fixture.root.resolve("evidence/transcript.jsonl");
			ByteArrayOutputStream output = new ByteArrayOutputStream();

			int status = fixture.forward("harness-a", new String[]{"--project", fixture.project.toString(), "--input",
				input.toString(), "--output", transcript.toString()}, new StringReader(""), output, "completed");

			requireThat(status, "status").isEqualTo(0);
			requireThat(fixture.entries.get("prompts/1"), "second").isEqualTo("second task");
			requireThat(Files.readString(transcript), "transcript").isEqualTo(Fixture.TRANSCRIPT);
			requireThat(output.size(), "standardOutput").isEqualTo(0);
		}
	}

	/**
	 * Supplies the parent's outcomes and the exit status each must produce.
	 *
	 * @return the response status and the expected exit status
	 */
	@DataProvider
	public Object[][] outcomes()
	{
		return new Object[][]{{"capacity", 75}, {"failed", 1}, {"refused", 1}};
	}

	/**
	 * Verifies that an unsuccessful requested session fails the runner with the parent's reason.
	 *
	 * @param outcome  the parent's response status
	 * @param expected the runner's exit status
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "outcomes")
	public void reportsUnsuccessfulOutcome(String outcome, int expected) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			// Requirement: R-I9
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "forward-")).
				toRealPath());

			int status = fixture.forward("harness-a", new String[]{"--project", fixture.project.toString()},
				new StringReader(RECORDS), new ByteArrayOutputStream(), outcome);

			requireThat(status, "status").isEqualTo(expected);
			requireThat(fixture.errors.toString(), "errors").contains("reason for " + outcome);
		}
	}

	/**
	 * Supplies options that only the parent runner may choose.
	 *
	 * @return each option with its arguments
	 */
	@DataProvider
	public Object[][] parentOptions()
	{
		return new Object[][]{{List.of("--deny-read", "path")}, {List.of("--session-requests", "2")},
			{List.of("--request-depth", "1")}, {List.of("--max-concurrency", "4")}, {List.of("--workspace", "path")}};
	}

	/**
	 * Verifies that a session cannot choose the limits that its parent imposes on the sessions it requests.
	 *
	 * @param option the option and its arguments
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "parentOptions", expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*parent runner.*")
	public void refusesParentOptions(List<String> option) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			// Requirement: R-I9
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "forward-")).
				toRealPath());
			List<String> arguments = new ArrayList<>(List.of("--project", fixture.project.toString()));
			arguments.addAll(option);
			try
			{
				fixture.forward("harness-a", arguments.toArray(String[]::new), new StringReader(RECORDS),
					new ByteArrayOutputStream(), "completed");
			}
			finally
			{
				try (Stream<Path> files = Files.list(fixture.requests))
				{
					requireThat(files.count(), "requests").isEqualTo(0);
				}
			}
		}
	}

	/**
	 * Verifies that only turns and the final completion can be forwarded, since the parent replays them in order.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*turn.*complete.*")
	public void refusesOtherRecords() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			// Requirement: R-I9
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "forward-")).
				toRealPath());

			fixture.forward("harness-a", new String[]{"--project", fixture.project.toString()}, new StringReader("""
				{"type":"turn","prompt":"task"}
				{"type":"steer","prompt":"other"}
				{"type":"complete"}
				"""), new ByteArrayOutputStream(), "completed");
		}
	}

	/**
	 * A run's requests directory with a fake parent runner that answers the first request.
	 */
	private static final class Fixture
	{
		static final String TRANSCRIPT = "{\"type\":\"started\"}\n{\"type\":\"completed\"}\n";
		static final String RECEIPT = "{\"run_id\":\"child\"}";
		final Path root;
		final Path requests;
		final Path project;
		final Map<String, String> entries = new HashMap<>();
		final StringWriter errors = new StringWriter();

		/**
		 * Creates the fixture.
		 *
		 * @param root a real temporary directory
		 * @throws IOException if the directories cannot be created
		 */
		Fixture(Path root) throws IOException
		{
			this.root = root;
			this.requests = Files.createDirectories(root.resolve("run/requests"));
			Files.createDirectories(root.resolve("run/responses"));
			this.project = Files.createDirectory(root.resolve("project"));
			Files.writeString(project.resolve("marker"), "project bytes");
		}

		/**
		 * Forwards a launch while the fake parent answers it, writing the transcript in two parts so that the
		 * forwarder must keep reading while the session runs.
		 *
		 * @param harness the launcher's harness
		 * @param args    the launcher's arguments
		 * @param input   the launcher's standard input
		 * @param output  the launcher's standard output
		 * @param outcome the response status
		 * @return the forwarder's exit status
		 * @throws IOException if forwarding fails
		 */
		int forward(String harness, String[] args, StringReader input, ByteArrayOutputStream output, String outcome)
			throws IOException
		{
			int[] pauses = {0};
			return SessionRequestForwarder.forward(harness, args, input, requests, () ->
			{
				pauses[0] += 1;
				Path response = responseDirectory();
				if (pauses[0] == 1)
				{
					readRequest();
					Files.createDirectories(response);
					Files.writeString(response.resolve("transcript.jsonl"), TRANSCRIPT.substring(0, 20));
				}
				else if (pauses[0] == 2)
				{
					Files.writeString(response.resolve("transcript.jsonl"), TRANSCRIPT);
					Files.writeString(response.resolve("receipt.json"), RECEIPT);
					Files.writeString(response.resolve("response.json"), "{\"status\":\"" + outcome +
						"\",\"reason\":\"reason for " + outcome + "\",\"transcript\":\"" +
						response.resolve("transcript.jsonl") + "\",\"receipt\":\"" + response.resolve("receipt.json") +
						"\"}");
				}
				else if (pauses[0] > 3)
					throw new AssertionError("The response was published");
			}, output, new PrintWriter(errors, true));
		}

		/**
		 * Returns the response directory of the only request.
		 *
		 * @return the directory
		 * @throws IOException if no request exists
		 */
		private Path responseDirectory() throws IOException
		{
			try (Stream<Path> files = Files.list(requests))
			{
				Path file = files.filter(path -> path.getFileName().toString().endsWith(".tar")).findFirst().
					orElseThrow(() -> new IOException("No request"));
				String id = file.getFileName().toString().replace(".tar", "");
				return root.resolve("run/responses").resolve(id);
			}
		}

		/**
		 * Reads the request's archive entries.
		 *
		 * @throws IOException if the archive cannot be read
		 */
		private void readRequest() throws IOException
		{
			try (Stream<Path> files = Files.list(requests))
			{
				Path file = files.filter(path -> path.getFileName().toString().endsWith(".tar")).findFirst().
					orElseThrow();
				try (InputStream bytes = Files.newInputStream(file);
					TarArchiveInputStream archive = new TarArchiveInputStream(bytes))
				{
					for (TarArchiveEntry entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry())
						entries.put(entry.getName(), new String(archive.readNBytes(65_536), StandardCharsets.UTF_8));
				}
			}
		}
	}
}
