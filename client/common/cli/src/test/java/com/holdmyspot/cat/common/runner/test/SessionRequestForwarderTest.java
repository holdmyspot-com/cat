package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.InvalidArgumentsException;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessEvaluationResult;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunReceipt;
import com.holdmyspot.cat.common.runner.KeptRunCopy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Clock;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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
			// Requirement: R-I9, R-I11
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "forward-")).
				toRealPath().resolve("parent\\Users"));
			Path receipt = fixture.root.resolve("evidence/receipt.json");
			ByteArrayOutputStream output = new ByteArrayOutputStream();

			int status = fixture.forward("harness-a", new String[]{"--project", fixture.project.toString(), "--model",
				"model-a", "--effort", "effort-a", "--install-cat", "--receipt", receipt.toString()}, new StringReader(RECORDS),
				output, "completed");

			requireThat(status, "status").isEqualTo(0);
			requireThat(fixture.entries.get("prompts/0"), "first").isEqualTo("first task");
			requireThat(fixture.entries.get("prompts/1"), "second").isEqualTo("second task");
			requireThat(fixture.entries.get("project/marker"), "project").isEqualTo("project bytes");
			JsonNode request = JsonMapper.builder().build().readTree(fixture.entries.get("request.json"));
			requireThat(request.path("harness").asString(), "harness").isEqualTo("harness-a");
			requireThat(request.path("model").asString(), "model").isEqualTo("model-a");
			requireThat(request.path("effort").asString(), "effort").isEqualTo("effort-a");
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
			// Requirement: R-I9, R-I11
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "forward-")).
				toRealPath());
			Path input = Files.writeString(fixture.root.resolve("records.jsonl"), RECORDS);
			Path transcript = fixture.root.resolve("evidence/transcript.jsonl");
			ByteArrayOutputStream output = new ByteArrayOutputStream();

			int status = fixture.forward("harness-a", new String[]{"--project", fixture.project.toString(), "--model",
				"model-a", "--effort", "effort-a", "--input", input.toString(), "--output", transcript.toString()},
				new StringReader(""), output, "completed");

			requireThat(status, "status").isEqualTo(0);
			requireThat(fixture.entries.get("prompts/1"), "second").isEqualTo("second task");
			requireThat(Files.readString(transcript), "transcript").isEqualTo(Fixture.TRANSCRIPT);
			requireThat(output.size(), "standardOutput").isEqualTo(0);
		}
	}

	/**
	 * Verifies the served command sees each root and child transcript copy before its matching completed-turn record.
	 *
	 * @throws IOException if the served session or its kept run cannot be copied
	 */
	@Test
	public void copiesTranscriptsBeforeTurnCompletion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("forward-transcript-copy-"));
			Path requests = Files.createDirectories(root.resolve("run/requests"));
			Path runRoot = root.resolve("kept-runs");
			Path project = Files.createDirectories(root.resolve("project"));
			Path artifact = Files.createDirectories(root.resolve("artifact"));
			Path childRoot = root.resolve("child-runs");
			Path responses = root.resolve("run/responses");
			AtomicInteger pauseCount = new AtomicInteger();
			List<String> copied = new ArrayList<>();
			String[] forwardedRunId = {""};
			Path[] finalResponse = {null};
			String childRunId = "ab12-cd34";
			JsonMapper json = JsonMapper.builder().build();
			try (Writer output = new Writer()
			{
				private final StringBuilder line = new StringBuilder();

				@Override
				public void write(char[] characters, int offset, int length) throws IOException
				{
					for (int index = offset; index < offset + length; index += 1)
					{
						if (characters[index] != '\n')
						{
							line.append(characters[index]);
							continue;
						}
						JsonNode record = json.readTree(line.toString());
						String type = record.path("type").asString();
						if (type.equals("started"))
							forwardedRunId[0] = record.path("run_id").asString();
						else if (type.equals("turn_completed"))
						{
							Path kept = runRoot.resolve("harnessa").resolve(forwardedRunId[0]);
							copied.add(readCopyOrMissing(kept.resolve(
								"artifacts/session-transcripts/rollout-root.jsonl"), "missing-root") +
								readCopyOrMissing(kept.resolve(
									"artifacts/session-transcripts/subagents/rollout-child.jsonl"), "missing-child"));
						}
						line.setLength(0);
					}
				}

				@Override
				public void flush()
				{
				}

				@Override
				public void close()
				{
				}
			})
			{
			Pause pause = () ->
			{
				int currentPause = pauseCount.incrementAndGet();
				if (currentPause > 2)
					throw new IOException("The kept-run response was not published");
				if (currentPause == 1)
				{
					Path archive;
					try (Stream<Path> files = Files.list(requests))
					{
						archive = files.filter(path -> path.getFileName().toString().endsWith(".tar")).findFirst().
							orElseThrow(() -> new IOException("The served request was not published"));
					}
					String id = archive.getFileName().toString().replace(".tar", "");
					Path response = Files.createDirectories(responses.resolve(id));
					Path turnCopy = Files.createDirectories(response.resolve("turn-copy"));
					writeTurnSnapshot(turnCopy, 1);
					Path sessionTranscripts = Files.createDirectories(
						turnCopy.resolve("artifacts/session-transcripts/subagents"));
					Files.writeString(turnCopy.resolve("artifacts/session-transcripts/rollout-root.jsonl"), "root-1\n");
					Files.writeString(sessionTranscripts.resolve("rollout-child.jsonl"), "child-1\n");
					Path transcript = response.resolve("transcript.jsonl");
					Files.writeString(transcript, "{\"type\":\"started\",\"run_id\":\"runa-1\"}\n" +
						"{\"type\":\"turn_completed\",\"run_id\":\"runa-1\"}\n");

					HarnessRunManager manager = new HarnessRunManager(new FileLocks(), childRoot, Clock.systemUTC(),
						Yielding.fileReplacer());
					HarnessRun child = manager.create("harnessa");
					Path childProject = child.directory().resolve("project");
					Files.writeString(childProject.resolve("marker"), "kept project");
					Path raw = Files.writeString(child.directory().resolve("artifacts/result.jsonl"), "result\n");
					Path nativeCopies = Files.createDirectories(
						child.directory().resolve("artifacts/session-transcripts/subagents"));
					Path rootCopy = child.directory().resolve("artifacts/session-transcripts/rollout-root.jsonl");
					Path childCopy = nativeCopies.resolve("rollout-child.jsonl");
					Files.writeString(rootCopy, "root-complete\n");
					Files.writeString(childCopy, "child-complete\n");
					Path receipt = child.directory().resolve("artifacts/receipt.json");
					HarnessRunReceipt.write(json, Yielding.fileReplacer(), receipt, child, raw,
						List.of(rootCopy, childCopy));
					manager.initialize(child, artifact, project, "fixture", List.of("fixture"));
					manager.markCompleted(child);
					Path kept = response.resolve("run");
					KeptRunCopy.Result copyResult = KeptRunCopy.copy(child.directory(), kept);
					requireThat(copyResult.incomplete(), "keptRun").isEmpty();
					HarnessRunManager.renameCopy(kept, childRunId);
				}
				else
				{
					Path response;
					try (Stream<Path> directories = Files.list(responses))
					{
						response = directories.findFirst().orElseThrow();
					}
					finalResponse[0] = response;
					Path turnCopy = response.resolve("turn-copy");
					writeTurnSnapshot(turnCopy, 2);
					Path sessionTranscripts = Files.createDirectories(
						turnCopy.resolve("artifacts/session-transcripts/subagents"));
					Files.writeString(turnCopy.resolve("artifacts/session-transcripts/rollout-root.jsonl"), "root-2\n");
					Files.writeString(sessionTranscripts.resolve("rollout-child.jsonl"), "child-2\n");
					Files.writeString(response.resolve("transcript.jsonl"), "{\"type\":\"started\",\"run_id\":\"runa-1\"}\n" +
						"{\"type\":\"turn_completed\",\"run_id\":\"runa-1\"}\n" +
						"{\"type\":\"turn_completed\",\"run_id\":\"runa-1\"}\n" +
						"{\"type\":\"completed\",\"run_id\":\"runa-1\"}\n");
					HarnessRun copiedChild = new HarnessRun("harnessa", childRunId, response.resolve("run"));
					requireThat(Files.isDirectory(copiedChild.directory().resolve("project")), "copiedProject").isTrue();
					requireThat(Files.isRegularFile(response.resolve("transcript.jsonl")), "externalOutput").isTrue();
					Path copiedRootTranscript = copiedChild.sessionTranscripts().resolve("rollout-root.jsonl");
					Path copiedChildTranscript = copiedChild.sessionTranscripts().resolve("subagents/rollout-child.jsonl");
					HarnessRunReceipt.write(json, Yielding.fileReplacer(),
						copiedChild.directory().resolve("artifacts/receipt.json"),
						copiedChild, response.resolve("transcript.jsonl"),
						List.of(copiedRootTranscript, copiedChildTranscript));
					ObjectNode result = json.createObjectNode().put("format_version", 1).
						put("id", response.getFileName().toString()).put("harness", "harnessa").
						put("status", "completed").put("reason", "").put("child_run_id", childRunId).
						put("transcript", response.resolve("transcript.jsonl").toString()).
						put("receipt", copiedChild.directory().resolve("artifacts/receipt.json").toString()).
						put("run", response.resolve("run").toString());
					json.writeValue(response.resolve("response.json").toFile(), result);
				}
			};
			String[] arguments = {"--run-root", runRoot.toString(), "--project", project.toString(), "--model",
				"model-a", "--effort", "effort-a", "--keep-run"};

			SessionRequestForwarder.serve("test command", "harnessa", artifact, List.of(arguments),
				new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
					"{\"type\":\"turn\",\"prompt\":\"second\"}\n{\"type\":\"complete\"}\n"), output,
				requests, pause);

			requireThat(copied, "copied").isEqualTo(List.of("root-1\nchild-1\n", "root-2\nchild-2\n"));
			Path retained = runRoot.resolve("harnessa").resolve(forwardedRunId[0]);
			Path receiptPath = retained.resolve("artifacts/receipt.json");
			String externalTranscript = Files.readString(finalResponse[0].resolve("transcript.jsonl"));
			Files.delete(finalResponse[0].resolve("transcript.jsonl"));
			HarnessEvaluationResult receipt = HarnessRunReceipt.read(json, receiptPath, retained.resolve("project"));
			requireThat(Files.isRegularFile(receipt.retainedProject().resolve("marker")), "retainedProjectMarker").
				isTrue();
			requireThat(Files.readString(receipt.retainedProject().resolve("marker")), "retainedProject").
				isEqualTo("kept project");
			requireThat(receipt.rawResult(), "retainedRawResult").
				isEqualTo(retained.resolve("artifacts/bridge-transcript.jsonl"));
			requireThat(Files.readString(receipt.rawResult()), "rawResult").isEqualTo(externalTranscript);
			requireThat(receipt.sessionTranscripts(), "nativeTranscripts").size().isEqualTo(2);
			}
		}
	}

	/**
	 * Replaces a turn's project and native transcript inputs for the next response.
	 *
	 * @param turnCopy the run snapshot the forwarder copies
	 * @param turn the snapshot number
	 * @throws IOException if the fixture cannot be written
	 */
	private static void writeTurnSnapshot(Path turnCopy, int turn) throws IOException
	{
		Files.createDirectories(turnCopy.resolve("project"));
		Files.createDirectories(turnCopy.resolve("initial-project"));
		Files.writeString(turnCopy.resolve("project/marker"), "project-" + turn);
		Files.writeString(turnCopy.resolve("initial-project/marker"), "initial-" + turn);
	}

	/**
	 * Reads a copied transcript, or returns the supplied marker when the copy is absent.
	 *
	 * @param path the copied transcript
	 * @param missingValue the marker to return if it is absent
	 * @return the copied text or marker
	 * @throws IOException if the transcript cannot be read
	 */
	private static String readCopyOrMissing(Path path, String missingValue) throws IOException
	{
		if (!Files.exists(path))
			return missingValue;
		return Files.readString(path);
	}

	/**
	 * Verifies that a launch inside a run that names a file of extra permissions asks the parent for the file's writes
	 * and hosts, as the content that the parent's runner reads, and that a launch without the file asks for none.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void forwardsExtraPermissions() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "forward-")).
				toRealPath());
			Path cache = fixture.root.resolve("registry cache");
			ExtraPermissions permissions = new ExtraPermissions(List.of(cache), Set.of("registry.example"));
			JsonMapper json = JsonMapper.builder().build();
			Path file = fixture.root.resolve("permissions.json");
			permissions.write(json, file);
			Fixture without = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "forward-")).
				toRealPath());

			fixture.forward("harness-a", new String[]{"--project", fixture.project.toString(), "--model", "model-a",
				"--effort", "effort-a", ExtraPermissions.OPTION, file.toString()}, new StringReader(RECORDS),
				new ByteArrayOutputStream(), "completed");
			without.forward("harness-a", new String[]{"--project", without.project.toString(), "--model", "model-a",
				"--effort", "effort-a"}, new StringReader(RECORDS), new ByteArrayOutputStream(), "completed");

			JsonNode request = json.readTree(fixture.entries.get("request.json"));
			requireThat(ExtraPermissions.fromDocument(request.path("extra_permissions"), "request"), "extraPermissions").
				isEqualTo(permissions);
			requireThat(json.readTree(without.entries.get("request.json")).has("extra_permissions"),
				"withoutExtraPermissions").isFalse();
		}
	}

	/**
	 * Verifies that a launch inside a run whose file of extra permissions is malformed, such as one that names a
	 * relative path, is refused with the reason before any request is written.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesInvalidExtraPermissions() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "forward-")).
				toRealPath());
			Path file = Files.writeString(fixture.root.resolve("permissions.json"),
				"{\"format_version\": 1, \"writes\": [\"relative/cache\"]}");

			try
			{
				fixture.forward("harness-a", new String[]{"--project", fixture.project.toString(), "--model",
					"model-a", "--effort", "effort-a", ExtraPermissions.OPTION, file.toString()},
					new StringReader(RECORDS), new ByteArrayOutputStream(), "completed");
				throw new AssertionError("The launch was not refused");
			}
			catch (InvalidArgumentsException e)
			{
				requireThat(e.getMessage(), "message").contains(Path.of("relative/cache").toString()).
					contains(file.toString());
			}
			try (Stream<Path> files = Files.list(fixture.requests))
			{
				requireThat(files.count(), "requests").isEqualTo(0);
			}
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
		return new Object[][]{{"capacity", 75}, {"failed", 1}, {"refused", 2}};
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

			int status = fixture.forward("harness-a", new String[]{"--project", fixture.project.toString(), "--model",
				"model-a", "--effort", "effort-a"}, new StringReader(RECORDS), new ByteArrayOutputStream(), outcome);

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
		return new Object[][]{{List.of("--session-requests", "2")}, {List.of("--request-depth", "1")},
			{List.of("--max-concurrency", "4")}, {List.of("--capacity-wait", "PT1M")}, {List.of("--agent", "agent-a")}};
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
			// Requirement: R-I9, R-I11
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
	 * Supplies options that no runner accepts, each with its arguments.
	 *
	 * @return each option with its arguments
	 */
	@DataProvider
	public Object[][] unsupportedOptions()
	{
		return new Object[][]{{List.of("--executable", "/bin/harness")}, {List.of("--exclude-user-rules")}};
	}

	/**
	 * Verifies that a launch naming an option that no runner accepts, such as {@code --executable} or
	 * {@code --exclude-user-rules}, is refused as an unsupported option before any request is written, also when the
	 * option is the launch's last argument.
	 *
	 * @param option the option and its arguments
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "unsupportedOptions", expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "Unsupported option: --(executable|exclude-user-rules)")
	public void refusesUnsupportedOption(List<String> option) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "forward-")).
				toRealPath());
			List<String> arguments = new ArrayList<>(List.of("--project", fixture.project.toString(), "--model",
				"model-a", "--effort", "effort-a"));
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
	 * Returns launches that lack a model, an effort or both, each with the start of the refusal.
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
	 * Verifies that a runner launch inside a run without {@code --model} or {@code --effort} is refused with a message
	 * that names the missing option and where to list the supported values, and requests no session.
	 *
	 * @param profile  the launch's profile options
	 * @param expected the start of the refusal
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "incompleteProfiles")
	public void refusesLaunchWithoutProfile(List<String> profile, String expected) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			// Requirement: R-I9, R-I11
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "forward-")).
				toRealPath());
			List<String> arguments = new ArrayList<>(List.of("--project", fixture.project.toString()));
			arguments.addAll(profile);
			try
			{
				fixture.forward("claude", arguments.toArray(String[]::new), new StringReader(RECORDS),
					new ByteArrayOutputStream(), "completed");
				throw new AssertionError("The launch was not refused");
			}
			catch (IllegalArgumentException e)
			{
				requireThat(e.getMessage(), "message").startsWith(expected).contains("`claude --help`");
			}
			try (Stream<Path> files = Files.list(fixture.requests))
			{
				requireThat(files.count(), "requests").isEqualTo(0);
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

			fixture.forward("harness-a", new String[]{"--project", fixture.project.toString(), "--model", "model-a",
				"--effort", "effort-a"}, new StringReader("""
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
					JsonMapper json = JsonMapper.builder().build();
					json.writeValue(response.resolve("response.json").toFile(), json.createObjectNode().
						put("status", outcome).put("reason", "reason for " + outcome).
						put("transcript", response.resolve("transcript.jsonl").toString()).
						put("receipt", response.resolve("receipt.json").toString()));
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
