package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.AbstractJvmScope;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.optimizer.adapter.Bridge;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessEvaluationResult;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.InvalidArgumentsException;
import com.holdmyspot.cat.common.runner.LaunchRefusedException;
import com.holdmyspot.cat.common.runner.NestedSession;
import com.holdmyspot.cat.common.runner.NestedSessionManager;
import com.holdmyspot.cat.common.runner.NestedSessionReceipt;
import com.holdmyspot.cat.common.runner.ResultsCopy;
import com.holdmyspot.cat.common.runner.RetainedTrialClaim;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.HarnessSession;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.Identity;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.TrialPluginInput;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

/**
 * Tests that a runner launched inside a nested session hands its launch to the parent runner through
 * {@link SessionRequestForwarder}.
 */
public final class SessionRequestForwarderTest
{
	/**
	 * The identifier that the fake harness gives its session.
	 */
	private static final String SESSION_ID = "5d1c0f3e-2a4b-4c6d-8e9f-0a1b2c3d4e5f";
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
	 * @throws IOException if the served session or its results copy cannot be copied
	 */
	@Test
	public void copiesTranscriptsBeforeTurnCompletion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("forward-transcript-copy-"));
			Path requests = Files.createDirectories(root.resolve("session/requests"));
			Path resultsFolder = root.resolve("kept-results");
			Path project = Files.createDirectories(root.resolve("project"));
			Path artifact = Files.createDirectories(root.resolve("artifact"));
			Path childRoot = root.resolve("child-results");
			Path responses = root.resolve("session/responses");
			AtomicInteger pauseCount = new AtomicInteger();
			List<String> copied = new ArrayList<>();
			String[] forwardedSlotId = {""};
			String[] childSlotId = {""};
			Path[] finalResponse = {null};
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
						if (type.equals("session_id"))
							forwardedSlotId[0] = record.path("session_id").asString();
						else if (type.equals("turn_completed"))
						{
							Path kept = resultsFolder.resolve("harnessa").resolve(forwardedSlotId[0]);
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
					Files.writeString(transcript, "{\"type\":\"started\"}\n" +
						"{\"type\":\"session_id\",\"session_id\":\"" + SESSION_ID + "\"}\n" +
						"{\"type\":\"turn_completed\"}\n");

					NestedSessionManager manager = new NestedSessionManager(new FileLocks(), childRoot, Clock.systemUTC(),
						Yielding.fileReplacer());
					NestedSession child = manager.create("harnessa");
					childSlotId[0] = child.id();
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
					NestedSessionReceipt.write(json, Yielding.fileReplacer(), receipt, child, raw,
						List.of(rootCopy, childCopy));
					manager.initialize(child, artifact, project, "fixture", List.of("fixture"));
					manager.markCompleted(child);
					Path kept = response.resolve("results-copy");
					ResultsCopy.Result copyResult = ResultsCopy.copy(child.directory(), kept);
					requireThat(copyResult.incomplete(), "resultsCopy").isEmpty();
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
					Files.writeString(response.resolve("transcript.jsonl"), "{\"type\":\"started\"}\n" +
						"{\"type\":\"session_id\",\"session_id\":\"" + SESSION_ID + "\"}\n" +
						"{\"type\":\"turn_completed\"}\n" +
						"{\"type\":\"turn_completed\"}\n" +
						"{\"type\":\"completed\"}\n");
					NestedSession copiedChild = new NestedSession("harnessa", childSlotId[0], response.resolve("results-copy"));
					requireThat(Files.isDirectory(copiedChild.directory().resolve("project")), "copiedProject").isTrue();
					requireThat(Files.isRegularFile(response.resolve("transcript.jsonl")), "externalOutput").isTrue();
					Path copiedRootTranscript = copiedChild.sessionTranscripts().resolve("rollout-root.jsonl");
					Path copiedChildTranscript = copiedChild.sessionTranscripts().resolve("subagents/rollout-child.jsonl");
					NestedSessionReceipt.write(json, Yielding.fileReplacer(),
						copiedChild.directory().resolve("artifacts/receipt.json"),
						copiedChild, response.resolve("transcript.jsonl"),
						List.of(copiedRootTranscript, copiedChildTranscript));
					ObjectNode result = json.createObjectNode().put("format_version", 1).
						put("id", response.getFileName().toString()).put("harness", "harnessa").
						put("status", "completed").put("reason", "").put("session_id", SESSION_ID).
						put("transcript", response.resolve("transcript.jsonl").toString()).
						put("receipt", copiedChild.directory().resolve("artifacts/receipt.json").toString()).
						put("results_copy", response.resolve("results-copy").toString());
					json.writeValue(response.resolve("response.json").toFile(), result);
				}
			};
			String[] arguments = {"--results", resultsFolder.toString(), "--project", project.toString(), "--model",
				"model-a", "--effort", "effort-a"};

			SessionRequestForwarder.serve("test command", "harnessa", artifact, List.of(arguments),
				new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
					"{\"type\":\"turn\",\"prompt\":\"second\"}\n{\"type\":\"complete\"}\n"), output,
				requests, pause);

			requireThat(copied, "copied").isEqualTo(List.of("root-1\nchild-1\n", "root-2\nchild-2\n"));
			requireThat(forwardedSlotId[0], "sessionId").isEqualTo(SESSION_ID);
			Path retained = resultsFolder.resolve("harnessa").resolve(SESSION_ID);
			Path receiptPath = retained.resolve("artifacts/receipt.json");
			String externalTranscript = Files.readString(finalResponse[0].resolve("transcript.jsonl"));
			Files.delete(finalResponse[0].resolve("transcript.jsonl"));
			HarnessEvaluationResult receipt = NestedSessionReceipt.read(json, receiptPath, retained.resolve("project"));
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
	 * Verifies that the results copy that a served session's runner copies into the command's results folder names, as
	 * its project, the project that the command's launch named, as a nested session that the command started itself
	 * would. The runner ran the session in the copy of that project that the request carried, below its own folder, so
	 * the nested session's manifest that it copies names that copy instead.
	 *
	 * @throws IOException if the served session or its results copy cannot be copied
	 */
	@Test
	public void resultsCopyNamesTheLaunchProject() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "forward-kept-project-")).
				toRealPath();
			Path requests = Files.createDirectories(root.resolve("session/requests"));
			Path responses = Files.createDirectories(root.resolve("session/responses"));
			Path resultsFolder = root.resolve("kept-results");
			Path project = Files.createDirectories(root.resolve("campaign/trial/project"));
			Path artifact = Files.createDirectories(root.resolve("artifact"));
			// The parent runner unpacks the request's copy of the project below its response and runs the session there
			Path requestProject = Files.createDirectories(root.resolve("parent/responses/request/inputs/project"));
			JsonMapper json = JsonMapper.builder().build();
			Pause pause = () ->
			{
				Path response;
				try (Stream<Path> directories = Files.list(responses))
				{
					response = directories.findFirst().orElse(null);
				}
				if (response == null)
				{
					try (Stream<Path> files = Files.list(requests))
					{
						String id = files.map(path -> path.getFileName().toString()).
							filter(name -> name.endsWith(".tar")).findFirst().
							orElseThrow(() -> new IOException("The served request was not published")).
							replace(".tar", "");
						response = Files.createDirectories(responses.resolve(id));
					}
				}
				else
					throw new IOException("The response was published, but the forwarder kept waiting");
				NestedSessionManager slot = new NestedSessionManager(jvm.fileLocks(), root.resolve("parent/slot"),
					Clock.systemUTC(), Yielding.fileReplacer());
				NestedSession child = slot.create("harnessa");
				slot.initialize(child, artifact, requestProject, "fixture", List.of("fixture"));
				slot.markCompleted(child);
				Path kept = response.resolve("results-copy");
				requireThat(ResultsCopy.copy(child.directory(), kept).incomplete(), "resultsCopy").isEmpty();
				Files.writeString(response.resolve("transcript.jsonl"), "{\"type\":\"completed\"}\n");
				json.writeValue(response.resolve("response.json").toFile(), json.createObjectNode().
					put("format_version", 1).put("id", response.getFileName().toString()).put("harness", "harnessa").
					put("status", "completed").put("reason", "").put("session_id", SESSION_ID).
					put("transcript", response.resolve("transcript.jsonl").toString()).
					put("results_copy", kept.toString()));
			};
			String[] arguments = {"--results", resultsFolder.toString(), "--project", project.toString(), "--model",
				"model-a", "--effort", "effort-a"};

			SessionRequestForwarder.serve("test command", "harnessa", artifact, List.of(arguments),
				new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n{\"type\":\"complete\"}\n"),
				Writer.nullWriter(), requests, pause);

			List<Path> resultsCopies;
			try (Stream<Path> runs = Files.list(resultsFolder.resolve("harnessa")))
			{
				resultsCopies = runs.toList();
			}
			requireThat(resultsCopies,
				"resultsCopies").isEqualTo(List.of(resultsFolder.resolve("harnessa").resolve(SESSION_ID)));
			JsonNode manifest = json.readTree(resultsCopies.getFirst().resolve("session.json").toFile());
			requireThat(manifest.path("id").asString(), "id").isEqualTo(SESSION_ID);
			requireThat(manifest.path("project_path").asString(), "projectPath").isEqualTo(project.toString());
		}
	}

	/**
	 * Verifies that a launch inside a nested session that names a results folder gets the copy of its session's nested
	 * session that the nested session's runner put in its response copied into that folder, as a launch outside a nested
	 * session does, and that the nested session's runner, which owns the response, still finds it.
	 *
	 * @throws IOException if the launch or its copy fails
	 */
	@Test
	public void copiesResultsUnderReportedSessionId() throws IOException
	{
		// Requirement: R-I13
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "forward-")).
				toRealPath());
			Path results = fixture.root.resolve("results");
			Path artifact = Files.createDirectories(fixture.root.resolve("artifact"));
			JsonMapper json = JsonMapper.builder().build();
			Path[] response = new Path[1];

			int status = SessionRequestForwarder.forward("harnessa", new String[]{"--project",
				fixture.project.toString(), "--results", results.toString(), "--model", "model-a", "--effort",
				"effort-a"}, new StringReader(RECORDS), fixture.requests, () ->
			{
				if (response[0] != null)
					throw new AssertionError("The response was published, but the forwarder kept waiting");
				response[0] = Files.createDirectories(fixture.responseDirectory());
				NestedSessionManager slot = new NestedSessionManager(jvm.fileLocks(), fixture.root.resolve("slot"),
					Clock.systemUTC(), Yielding.fileReplacer());
				NestedSession child = slot.create("harnessa");
				slot.initialize(child, artifact, fixture.project, "fixture", List.of("fixture"));
				slot.markCompleted(child);
				Path kept = response[0].resolve("results-copy");
				requireThat(ResultsCopy.copy(child.directory(), kept).incomplete(), "resultsCopy").isEmpty();
				Files.writeString(response[0].resolve("transcript.jsonl"), Fixture.TRANSCRIPT);
				json.writeValue(response[0].resolve("response.json").toFile(), json.createObjectNode().
					put("format_version", 1).put("id", response[0].getFileName().toString()).
					put("harness", "harnessa").put("status", "completed").put("reason", "").
					put("session_id", SESSION_ID).put("transcript", response[0].resolve("transcript.jsonl").toString()).
					put("results_copy", kept.toString()));
			}, new ByteArrayOutputStream(), new PrintWriter(fixture.errors, true));

			requireThat(status, "status").withContext(fixture.errors, "errors").isEqualTo(0);
			List<Path> copies;
			try (Stream<Path> runs = Files.list(results.resolve("harnessa")))
			{
				copies = runs.toList();
			}
			requireThat(copies, "copies").isEqualTo(List.of(results.resolve("harnessa").resolve(SESSION_ID)));
			requireThat(Files.isDirectory(copies.getFirst().resolve("project")), "copiedProject").isTrue();
			requireThat(Files.isDirectory(response[0].resolve("results-copy")), "responseCopy").isTrue();
		}
	}

	/**
	 * Verifies that a caller-allocated request identifier names the archive and its held sender lock.
	 *
	 * @throws IOException if the request or response fixture cannot be written
	 */
	@Test
	public void servesUsingCallerAllocatedRequestId() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			// Requirement: R-I12
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"forward-fixed-id-")).toRealPath());
			UUID requestId = UUID.fromString("07d8b2ce-723d-4b9e-a540-fb3e8f95c4d1");
			Path artifact = Files.createDirectories(fixture.root.resolve("artifact"));
			Path results = fixture.root.resolve("results");
			JsonMapper json = JsonMapper.builder().build();
			boolean[] exactArchivePublished = {false};
			boolean[] senderLockHeld = {false};
			Pause pause = () ->
			{
				fixture.readRequest();
				exactArchivePublished[0] = Files.isRegularFile(fixture.requests.resolve(requestId + ".tar"));
				requireThat(exactArchivePublished[0], "exactArchivePublished").isTrue();
				Path lockPath = fixture.requests.resolve(requestId + ".lock");
				try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.READ,
					StandardOpenOption.WRITE))
				{
					try
					{
						FileLock lock = channel.tryLock();
						senderLockHeld[0] = lock == null;
						if (lock != null)
							lock.release();
					}
					catch (OverlappingFileLockException _)
					{
						senderLockHeld[0] = true;
					}
				}
				Path response = Files.createDirectories(fixture.responseDirectory());
				Path retainedRoot = Files.createDirectories(fixture.root.resolve("retained"));
				NestedSessionManager manager = new NestedSessionManager(new FileLocks(), retainedRoot, Clock.systemUTC(),
					Yielding.fileReplacer());
				NestedSession retained = manager.create("harness");
				manager.initialize(retained, artifact, fixture.project, "fixture", List.of("fixture"));
				manager.markCompleted(retained);
				Path kept = response.resolve("results-copy");
				requireThat(ResultsCopy.copy(retained.directory(), kept).incomplete(), "resultsCopy").isEmpty();
				Files.writeString(response.resolve("transcript.jsonl"), Fixture.TRANSCRIPT);
				json.writeValue(response.resolve("response.json").toFile(), json.createObjectNode().
					put("status", "completed").put("reason", "").put("session_id", SESSION_ID).
					put("results_copy", kept.toString()).put("transcript", response.resolve("transcript.jsonl").toString()));
			};
			StringWriter output = new StringWriter();

			SessionRequestForwarder.serve(SessionRequestForwarder.Route.RUN, requestId, "test command", "harness-a",
				artifact, List.of("--project", fixture.project.toString(), "--results", results.toString(), "--model",
					"model-a", "--effort", "effort-a"), new StringReader(""), output, fixture.requests, pause);

			requireThat(exactArchivePublished[0], "exactArchivePublished").isTrue();
			requireThat(senderLockHeld[0], "senderLockHeld").isTrue();
			requireThat(fixture.entries.get("project/marker"), "projectArchive").isEqualTo("project bytes");
			requireThat(output.toString(), "transcript").isEqualTo(Fixture.TRANSCRIPT);
		}
	}

	/**
	 * Verifies that an installed peer evaluator uses the owner's broker and request identifier with the evaluator's
	 * harness and artifact.
	 *
	 * @throws IOException if the fixture request or response cannot be read or written
	 */
	@Test(expectedExceptions = LaunchRefusedException.class, expectedExceptionsMessageRegExp = "The fixture stopped")
	public void servesPeerEvaluatorThroughOwnerRoute() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"forward-evaluator-")).toRealPath());
			UUID requestId = UUID.randomUUID();
			Path evaluatorArtifact = Files.createDirectory(fixture.root.resolve("claude-artifact"));
			Path results = fixture.root.resolve("results");
			JsonMapper json = jvm.json();
			Pause respond = () ->
			{
				requireThat(fixture.requests.resolve(requestId + ".tar"), "ownerRequest").isRegularFile();
				fixture.readRequest();
				JsonNode request = json.readTree(fixture.entries.get("request.json"));
				requireThat(request.path("harness").asString(), "evaluatorHarness").isEqualTo("claude");
				requireThat(request.path("agent").asString(), "evaluatorAgent").isEqualTo("evaluator-agent");
				requireThat(request.path("install_cat").asBoolean(false), "installCat").isTrue();
				requireThat(request.path("plugins").size(), "extraPlugins").isEqualTo(0);
				Path response = Files.createDirectories(fixture.responseDirectory());
				json.writeValue(response.resolve("response.json").toFile(),
					json.createObjectNode().put("status", "refused").put("reason", "The fixture stopped"));
			};
			try (AbstractJvmScope scope = new AbstractJvmScope(fixture.root, _ -> respond)
			{
			})
			{
				RunnerSessions.Settings settings = new RunnerSessions.Settings(scope, evaluatorArtifact, results,
					List::of, false, Map.of(), fixture.root, HarnessCapacity.unlimited(), Clock.systemUTC(),
					Yielding.fileReplacer());
				Bridge bridge = RunnerSessions.evaluatorBridge(settings, "claude",
					SessionRequestForwarder.Route.SERVER, fixture.requests);
				bridge.serveTrial(requestId, Optional.empty(), fixture.project, List.of("--project",
					fixture.project.toString(), "--results", results.toString(), "--model", "model-a", "--effort",
					"effort-a", "--agent", "evaluator-agent", "--install-cat", "--artifact",
					evaluatorArtifact.toString()), new StringReader(""), new StringWriter());
			}
		}
	}

	/**
	 * Preserves the identifiers beside their archive paths when the served launch prepends a candidate CAT.
	 *
	 * @throws IOException if the request fixture cannot be read or written
	 */
	@Test(expectedExceptions = LaunchRefusedException.class, expectedExceptionsMessageRegExp = "The fixture stopped")
	public void preservesPluginIdsInArchiveOrder() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"forward-plugin-provenance-")).toRealPath());
			Path artifact = Files.createDirectory(fixture.root.resolve("artifact"));
			Path candidate = Files.createDirectory(fixture.root.resolve("candidate"));
			Path first = Files.createDirectory(fixture.root.resolve("first"));
			Path legacy = Files.createDirectory(fixture.root.resolve("legacy"));
			Path last = Files.createDirectory(fixture.root.resolve("last"));
			JsonMapper json = jvm.json();
			Pause respond = () ->
			{
				fixture.readRequest();
				JsonNode request = json.readTree(fixture.entries.get("request.json"));
				requireThat(request.path("plugins"), "paths").
					isEqualTo(json.readTree("[\"plugins/0\",\"plugins/1\",\"plugins/2\",\"plugins/3\"]"));
				JsonNode provenance = request.path(TrialPluginInput.PROVENANCE_FIELD);
				requireThat(provenance, "provenance").
					isEqualTo(json.readTree("[null,\"guide@one\",null,\"guide@two\"]"));
				List<Path> paths = List.of(candidate, first, legacy, last);
				List<TrialPluginInput> inputs = TrialPluginInput.readProvenance(paths, provenance);
				requireThat(inputs, "inputs").isEqualTo(List.of(new TrialPluginInput(candidate, Optional.empty()),
					new TrialPluginInput(first, Optional.of("guide@one")), new TrialPluginInput(legacy, Optional.empty()),
					new TrialPluginInput(last, Optional.of("guide@two"))));
				requireThat(TrialPluginInput.readProvenance(paths, request.path("absent")), "legacy").
					isEqualTo(TrialPluginInput.unbound(paths));
				expectThrows(IOException.class, () -> TrialPluginInput.readProvenance(paths, json.readTree("[null]")));
				Path response = Files.createDirectories(fixture.responseDirectory());
				json.writeValue(response.resolve("response.json").toFile(),
					json.createObjectNode().put("status", "refused").put("reason", "The fixture stopped"));
			};

			SessionRequestForwarder.serve(SessionRequestForwarder.Route.SERVER, "test command", "codex", artifact,
				List.of("--project", fixture.project.toString(), "--results", fixture.root.resolve("results").toString(),
					"--artifact", candidate.toString(), "--install-cat", "--model", "model-a", "--effort", "effort-a",
					"--plugin", first.toString(), TrialPluginInput.ID_OPTION, "guide@one", "--plugin", legacy.toString(),
					"--plugin", last.toString(), TrialPluginInput.ID_OPTION, "guide@two"), new StringReader(""),
				new StringWriter(), fixture.requests, respond);
		}
	}

	/**
	 * Verifies that a served resume uses its existing session allowance and carries no project copy.
	 *
	 * @throws IOException if the request or response fixture cannot be written
	 */
	@Test
	public void resumesAtExhaustedSessionRequestLimit() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			// Requirement: R-I12
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"forward-resume-limit-")).toRealPath());
			Path responses = fixture.root.resolve("session/responses");
			Path artifact = Files.createDirectories(fixture.root.resolve("artifact"));
			Path results = fixture.root.resolve("results");
			Files.writeString(responses.resolve("limits.json"), """
				{"format_version":1,"requests_allowed":1,"requests_used":1,"depth_remaining":1}
				""");
			JsonMapper json = JsonMapper.builder().build();
			String[] resume = {""};
			boolean[] requestHasProject = {true};
			boolean[] projectEntryArchived = {false};
			Pause pause = () ->
			{
				fixture.readRequest();
				JsonNode request = json.readTree(fixture.entries.get("request.json"));
				resume[0] = request.path("resume").asString("");
				requestHasProject[0] = request.has("project");
				projectEntryArchived[0] = fixture.entries.keySet().stream().anyMatch(entry ->
					entry.equals("project") || entry.startsWith("project/"));
				Path response = Files.createDirectories(fixture.responseDirectory());
				Path retainedRoot = Files.createDirectories(fixture.root.resolve("retained"));
				NestedSessionManager manager = new NestedSessionManager(new FileLocks(), retainedRoot, Clock.systemUTC(),
					Yielding.fileReplacer());
				NestedSession retained = manager.create("harness");
				manager.initialize(retained, artifact, fixture.project, "fixture", List.of("fixture"));
				manager.markCompleted(retained);
				Path kept = response.resolve("results-copy");
				requireThat(ResultsCopy.copy(retained.directory(), kept).incomplete(), "resultsCopy").isEmpty();
				Files.writeString(response.resolve("transcript.jsonl"), Fixture.TRANSCRIPT);
				json.writeValue(response.resolve("response.json").toFile(), json.createObjectNode().
					put("status", "completed").put("reason", "").put("session_id", SESSION_ID).
					put("results_copy", kept.toString()).put("transcript", response.resolve("transcript.jsonl").toString()));
			};

			StringWriter output = new StringWriter();
			SessionRequestForwarder.serve(SessionRequestForwarder.Route.SERVER, "test command", "harness-a", artifact,
				List.of("--resume", SESSION_ID, "--results", results.toString(), "--model", "model-a", "--effort",
					"effort-a"), new StringReader("""
					{"type":"turn","prompt":"resume"}
					{"type":"complete"}
					"""), output, fixture.requests, pause);

			requireThat(resume[0], "resumeSessionId").isEqualTo(SESSION_ID);
			requireThat(requestHasProject[0], "requestHasProject").isFalse();
			requireThat(projectEntryArchived[0], "projectEntryArchived").isFalse();
			requireThat(output.toString(), "transcript").isEqualTo(Fixture.TRANSCRIPT);
		}
	}

	/**
	 * Verifies that a guarded Claude resume publishes its replay before the native process identifies itself.
	 *
	 * @throws IOException if the request or response fixture cannot be written
	 */
	@Test
	public void replaysBeforeNativeSessionId() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			// Claude waits for its next prompt before it reports the native session ID.
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"forward-retained-")).toRealPath());
			Path artifact = Files.createDirectory(fixture.root.resolve("artifact"));
			Path results = fixture.root.resolve("results");
			StringWriter output = new StringWriter();
			AtomicInteger pauses = new AtomicInteger();
			RetainedTrialClaim claim = new RetainedTrialClaim(UUID.randomUUID(),
				new Identity(UUID.randomUUID(), Optional.of(new HarnessSession("claude", SESSION_ID))), 1);
			Pause pause = () ->
			{
				Path response = fixture.responseDirectory();
				if (pauses.incrementAndGet() == 1)
				{
					Path prefix = Files.createDirectories(response.resolve("retained-prefix"));
					writeTurnSnapshot(prefix, 1);
					Files.writeString(prefix.resolve("turn-checkpoint.json"),
						"{\"format_version\":1,\"turn_number\":1,\"state\":\"ready\"}\n");
					Path artifacts = Files.createDirectory(prefix.resolve("artifacts"));
					Files.writeString(artifacts.resolve("turn-1.events"), "previous event\n");
					Files.writeString(artifacts.resolve("turn-1.reply.json"), """
						{"type":"turn_completed","exit_code":0,"error":"","assistant_response":"previous",
						 "assistant_messages":["previous"]}
						""");
					Files.writeString(response.resolve("transcript.jsonl"), "{\"type\":\"started\",\"resumed\":true}\n");
					return;
				}
				requireThat(output.toString(), "recordsBeforeNativeId").contains("retained_session_id").
					contains("previous event").contains("turn_completed").contains("started");
				requireThat(Files.readString(results.resolve("claude").resolve(SESSION_ID).resolve("project/marker")),
					"retainedProjectBeforePrompt").isEqualTo("project-1");
				JsonMapper json = JsonMapper.builder().build();
				json.writeValue(response.resolve("response.json").toFile(),
					json.createObjectNode().put("status", "refused").put("reason", "The fixture stopped"));
			};

			try
			{
				SessionRequestForwarder.serveRetainedTrial(SessionRequestForwarder.Route.RUN, claim, "test command",
					"claude", artifact, fixture.project, List.of("--resume", SESSION_ID, "--results", results.toString(),
						"--model", "model-a", "--effort", "effort-a"), new StringReader(""), output, fixture.requests,
					pause);
				throw new AssertionError("The fixture must end the request after startup");
			}
			catch (LaunchRefusedException e)
			{
				requireThat(e.getMessage(), "reason").isEqualTo("The fixture stopped");
			}
		}
	}

	/**
	 * Verifies that a new served launch at the exhausted session request limit is refused before publication.
	 *
	 * @throws IOException if the request limit fixture cannot be written
	 */
	@Test
	public void refusesLaunchAtExhaustedRequestLimit() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			// Requirement: R-I12
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"forward-launch-limit-")).toRealPath());
			Path artifact = Files.createDirectories(fixture.root.resolve("artifact"));
			Path results = fixture.root.resolve("results");
			Files.writeString(fixture.root.resolve("session/responses/limits.json"), """
				{"format_version":1,"requests_allowed":1,"requests_used":1,"depth_remaining":1}
				""");

			try
			{
				SessionRequestForwarder.serve(SessionRequestForwarder.Route.SERVER, "test command", "harness-a", artifact,
					List.of("--project", fixture.project.toString(), "--results", results.toString(), "--model", "model-a",
						"--effort", "effort-a"), new StringReader(""), new StringWriter(), fixture.requests, () -> {});
				throw new AssertionError("The launch was not refused");
			}
			catch (LaunchRefusedException _)
			{
				try (Stream<Path> published = Files.list(fixture.requests))
				{
					requireThat(published.toList(), "publishedRequests").isEmpty();
				}
			}
		}
	}

	/**
	 * Replaces a turn's project and native transcript inputs for the next response.
	 *
	 * @param turnCopy the nested session snapshot the forwarder copies
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
	 * Verifies that a launch inside a nested session that names a file of extra permissions asks the parent for the
	 * file's writes and hosts, as the content that the parent's runner reads, and that a launch without the file asks
	 * for none.
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
	 * Verifies that a launch inside a nested session whose file of extra permissions is malformed, such as one that
	 * names a relative path, is refused with the reason before any request is written.
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
	 * Supplies the options that choose a session's credential, which the parent runner chooses inside a nested session.
	 *
	 * @return each option with its argument
	 */
	@DataProvider
	public Object[][] sourceHomeOptions()
	{
		return new Object[][]{{List.of("--home", "/home/alice")}, {List.of("--gateway-login-class", "api_key")}};
	}

	/**
	 * Verifies that a launch inside a nested session that names the source home or the gateway login class is refused
	 * with the refusal status before it packs a request: the nested session's runner, which runs with the user's
	 * permissions, would otherwise read files that the session chose.
	 *
	 * @param option the option and its argument
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "sourceHomeOptions")
	public void refusesSourceHomeInNestedSession(List<String> option) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			// Requirement: R-I14
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "forward-")).
				toRealPath());
			List<String> arguments = new ArrayList<>(List.of("--project", fixture.project.toString(), "--model",
				"model-a", "--effort", "effort-a"));
			arguments.addAll(option);
			StringWriter errors = new StringWriter();

			int status = HarnessRunnerOptions.refusingInvalidArguments(() -> fixture.forward("harness-a",
				arguments.toArray(String[]::new), new StringReader(RECORDS), new ByteArrayOutputStream(), "completed"),
				new PrintWriter(errors, true));

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains(option.getFirst()).contains("parent runner");
			try (Stream<Path> files = Files.list(fixture.requests))
			{
				requireThat(files.count(), "requests").isEqualTo(0);
			}
		}
	}

	/**
	 * Verifies that a launch outside a nested session sends its source home and gateway login class to the session's
	 * request server inside its request.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void sendsSourceHomeToRequestServer() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			// Requirement: R-I14
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "forward-")).
				toRealPath());
			Path home = Files.createDirectories(fixture.root.resolve("source-home"));

			int status = fixture.forwardServed("harness-a", new String[]{"--project", fixture.project.toString(),
				"--model", "model-a", "--effort", "effort-a", "--home", home.toString(), "--gateway-login-class",
				"subscription"}, new StringReader(RECORDS), new ByteArrayOutputStream(), "completed");

			requireThat(status, "status").isEqualTo(0);
			JsonNode request = JsonMapper.builder().build().readTree(fixture.entries.get("request.json"));
			requireThat(request.path("home").asString(""), "home").isEqualTo(home.toString());
			requireThat(request.path("gateway_login_class").asString(""), "gatewayLoginClass").
				isEqualTo("subscription");
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
	 * Verifies that a runner launch inside a nested session without {@code --model} or {@code --effort} is refused with
	 * a message that names the missing option and where to list the supported values, and requests no session.
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
	 * A nested session's requests directory with a fake parent runner that answers the first request.
	 */
	private static final class Fixture
	{
		static final String TRANSCRIPT = "{\"type\":\"started\"}\n{\"type\":\"completed\"}\n";
		static final String RECEIPT = "{\"id\":\"child\"}";
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
			this.requests = Files.createDirectories(root.resolve("session/requests"));
			Files.createDirectories(root.resolve("session/responses"));
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
		 * Forwards a launch to a session's request server, outside a nested session, while the fake server answers it.
		 *
		 * @param harness the launcher's harness
		 * @param args    the launcher's arguments
		 * @param input   the launcher's standard input
		 * @param output  the launcher's standard output
		 * @param outcome the response status
		 * @return the forwarder's exit status
		 * @throws IOException if forwarding fails
		 */
		int forwardServed(String harness, String[] args, StringReader input, ByteArrayOutputStream output,
			String outcome) throws IOException
		{
			int[] pauses = {0};
			return SessionRequestForwarder.forward(SessionRequestForwarder.Route.SERVER, harness, args, input, requests,
				root.resolve("results"), () ->
				{
					pauses[0] += 1;
					Path response = responseDirectory();
					if (pauses[0] == 1)
					{
						readRequest();
						Files.createDirectories(response);
						Files.writeString(response.resolve("transcript.jsonl"), TRANSCRIPT);
						JsonMapper json = JsonMapper.builder().build();
						json.writeValue(response.resolve("response.json").toFile(), json.createObjectNode().
							put("status", outcome).put("reason", "").
							put("transcript", response.resolve("transcript.jsonl").toString()));
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
				return root.resolve("session/responses").resolve(id);
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
