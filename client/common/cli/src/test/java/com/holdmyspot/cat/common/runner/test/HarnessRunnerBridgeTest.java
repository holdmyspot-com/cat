package com.holdmyspot.cat.common.runner.test;


import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.runner.Conversation;
import com.holdmyspot.cat.common.runner.HarnessProcess;
import com.holdmyspot.cat.common.runner.HarnessProcessResult;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunReceipt;
import com.holdmyspot.cat.common.runner.HarnessRunSession;
import com.holdmyspot.cat.common.runner.HarnessRunnerBridge;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessSessionTranscripts;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.TurnMode;
import com.holdmyspot.cat.common.runner.fakes.test.ManualTicker;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedProcess;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedProcess.InterruptedTurn;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

/**
 * Tests {@link HarnessRunnerBridge}.
 */
public final class HarnessRunnerBridgeTest
{
	/**
	 * The harness session that an interrupted run records.
	 */
	private static final String RESUMED_SESSION = "5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90";
	/**
	 * Creates the test fixture.
	 */
	public HarnessRunnerBridgeTest()
	{
	}

	/**
	 * Verifies two turns share one live harness process until explicit completion.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void keepsOneHarnessProcessForMultipleTurns() throws IOException
	{
		List<String> received = new CopyOnWriteArrayList<>();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("one", "harness event"),
				Turn.replies("two", "harness event")), received);
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
				"{\"type\":\"turn\",\"prompt\":\"second\"}\n{\"type\":\"complete\"}\n"))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			StringWriter output = new StringWriter();

			HarnessRun result = HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process,
				new TestHarnessRunnerScope()), input, output);

			requireThat(received, "received").isEqualTo(List.of("first", "second"));
			requireThat(process.isAlive(), "alive").isFalse();
			requireThat(result, "result").isEqualTo(run);
			requireThat(output.toString(), "output").contains("\"type\":\"started\"").
				contains("\"type\":\"harness_event\"").contains("\"type\":\"turn_completed\"").
				contains("\"type\":\"session_id\"").contains("\"type\":\"completed\"");
			requireThat(output.toString().lines().count(), "lineCount").isEqualTo(7L);
			requireThat(output.toString(), "output").doesNotContain("\"events\":");
		}
	}

	/**
	 * Verifies the harness-neutral transcript retains the exact final assistant response for evaluator consumption.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void recordsFinalAssistantResponse() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("result with an injected \"quote\"")),
				new ArrayList<>());
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"task\"}\n" +
				"{\"type\":\"complete\"}\n"))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			StringWriter output = new StringWriter();

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), input,
				output);

			requireThat(output.toString(), "output").contains(
				"\"assistant_response\":\"result with an injected \\\"quote\\\"\"");
		}
	}

	/**
	 * Verifies the bridge retains every assistant message in order and keeps the final response distinct.
	 * The supplied list models the text-event order recorded in each harness's assistant-message fixtures.
	 *
	 * @throws IOException if the bridge fails
	 */
	// Requirement: R-C12
	@Test
	public void recordsEveryAssistantMessage() throws IOException
	{
		try (TestHarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies(List.of("interim with a \"quote\"",
				"done"))), new ArrayList<>());
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"task\"}\n" +
				"{\"type\":\"complete\"}\n"))
		{
			Path directory = cleanup.add(Files.createTempDirectory(
				scope.temporaryDirectory(), "cat-run-"));
			HarnessRun run = new HarnessRun("harness", "run-1", directory);
			StringWriter output = new StringWriter();

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, scope), input,
				output);

			JsonMapper json = JsonMapper.builder().build();
			JsonNode completed = output.toString().lines().map(json::readTree).
				filter(event -> event.path("type").asString().equals("turn_completed")).findFirst().orElseThrow();
			requireThat(completed.path("assistant_messages").size(), "messageCount").isEqualTo(2);
			requireThat(completed.path("assistant_messages").get(0).asString(), "interim").
				isEqualTo("interim with a \"quote\"");
			requireThat(completed.path("assistant_messages").get(1).asString(), "final").isEqualTo("done");
			requireThat(completed.path("assistant_response").asString(), "assistantResponse").isEqualTo("done");
		}
	}

	/**
	 * Verifies that a completed bridge run is kept, completed, for its results to be copied to the results folder.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void keepsCompletedManagedRun() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(), new ArrayList<>());
			StringReader input = new StringReader("{\"type\":\"complete\"}\n"))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
				input, new StringWriter());

			requireThat(Files.exists(run.directory()), "runDirectory").isTrue();
			requireThat(Files.readString(run.directory().resolve("session.json")), "manifest").
				contains("\"state\": \"completed\"");
		}
	}

	/**
	 * Verifies a bridge can retain its JSONL transcript without a harness-specific output path.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void writesRetainedTranscript() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("done")), new ArrayList<>());
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
				"{\"type\":\"complete\"}\n"))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			Path transcript = cleanup.add(Files.createTempFile("cat-bridge-", ".jsonl"));

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), input,
				transcript);

			requireThat(Files.readString(transcript), "transcript").contains("\"type\":\"started\"").
				contains("\"type\":\"completed\"");
		}
	}

	/**
	 * Verifies that a bridge writes its transcript to the output file that the runner's options name, rather than to
	 * the caller's stream, and a receipt that names the retained project and that transcript.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void writesRequestedTranscriptAndReceipt() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("done")), new ArrayList<>());
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
				"{\"type\":\"complete\"}\n"))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Path project = Files.createDirectories(run.directory().resolve("project"));
			Path evidence = cleanup.add(Files.createTempDirectory("cat-evidence-"));
			Path transcript = evidence.resolve("transcript.jsonl");
			Path receipt = evidence.resolve("receipt.json");
			HarnessRunnerOptions options = new HarnessRunnerOptions(root.resolve("artifact"), root, root, "model-a",
				"effort-a", transcript, receipt, false, false, 1,
				List.of(), List.of(), null, List.of(), 0, null, 0, null, null, null, false, null,
				null, null, null, false, null, null);
			StringWriter output = new StringWriter();
			JsonMapper json = JsonMapper.builder().build();

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
				options, json, Yielding.fileReplacer(), input, output);

			requireThat(output.toString(), "output").isEmpty();
			requireThat(Files.readString(transcript), "transcript").contains("\"type\":\"started\"").
				contains("\"type\":\"completed\"");
			requireThat(HarnessRunReceipt.read(json, receipt, project).retainedProject(), "retainedProject").
				isEqualTo(project);
			requireThat(HarnessRunReceipt.read(json, receipt, project).rawResult(), "rawResult").
				isEqualTo(run.directory().resolve("artifacts/bridge-transcript.jsonl"));
			requireThat(Files.readString(run.directory().resolve("artifacts/bridge-transcript.jsonl")), "retainedOutput").
				isEqualTo(Files.readString(transcript));
			Files.delete(transcript);
			requireThat(HarnessRunReceipt.read(json, receipt, project).rawResult(), "relocatedRawResult").
				isEqualTo(run.directory().resolve("artifacts/bridge-transcript.jsonl"));
		}
	}

	/**
	 * Verifies that a bridge whose options name a turn copy replaces the copy of the run's project and initial project
	 * at the end of each completed turn, before it writes the turn's {@code turn_completed} response.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void copiesProjectAtEndOfEachCompletedTurn() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("one", "harness event"),
				Turn.replies("two", "harness event")), new ArrayList<>());
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
				"{\"type\":\"turn\",\"prompt\":\"second\"}\n{\"type\":\"complete\"}\n"))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root, Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Path project = Files.createDirectories(run.directory().resolve("project"));
			Files.writeString(project.resolve("first.txt"), "first");
			manager.snapshotProject(run);
			Path turnCopy = root.resolve("turn-copy");
			HarnessRunnerOptions options = new HarnessRunnerOptions(root.resolve("artifact"), root, root, "model-a",
				"effort-a", null, null, false, false, 1, List.of(), List.of(), null, List.of(), 0, null, 0, null,
				null, null, false, null, null, null, turnCopy, false, null, null);
			List<String> copied = new ArrayList<>();
			try (Writer output = new Writer()
			{
				private final StringBuilder line = new StringBuilder();

				@Override
				public void write(char[] characters, int offset, int length) throws IOException
				{
					for (int index = offset; index < offset + length; ++index)
					{
						if (characters[index] != '\n')
						{
							line.append(characters[index]);
							continue;
						}
						if (line.indexOf("\"turn_completed\"") >= 0)
							turnEnded();
						line.setLength(0);
					}
				}

				/**
				 * Records what the copy holds when a turn's completion is written, and then changes the run's project
				 * as its next turn would.
				 *
				 * @throws IOException if the copy cannot be read or the project cannot be changed
				 */
				private void turnEnded() throws IOException
				{
					copied.add(Files.exists(turnCopy.resolve("project/first.txt")) + " " +
						Files.exists(turnCopy.resolve("project/second.txt")) + " " +
						Files.exists(turnCopy.resolve("initial-project/first.txt")) + " " +
						Files.exists(turnCopy.resolve("initial-project/second.txt")));
					Files.deleteIfExists(project.resolve("first.txt"));
					Files.writeString(project.resolve("second.txt"), "second");
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
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()),
					manager, options, JsonMapper.builder().build(), Yielding.fileReplacer(), input, output);
			}

			requireThat(copied, "copied").isEqualTo(List.of("true false true false", "false true true false"));
		}
	}

	/**
	 * Verifies that completed-turn responses follow copies of root and child native transcripts.
	 *
	 * @throws IOException if the bridge or transcript copy fails
	 */
	@Test
	public void copiesTranscriptsBeforeTurnCompletion() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
				"{\"type\":\"turn\",\"prompt\":\"second\"}\n{\"type\":\"complete\"}\n"))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root, Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Files.createDirectories(run.directory().resolve("project"));
			manager.snapshotProject(run);
			Path turnCopy = root.resolve("turn-copy");
			Path rootTranscript = run.sessionTranscripts().resolve("rollout-root.jsonl");
			Path childTranscript = run.sessionTranscripts().resolve("rollout-child.jsonl");
			List<String> copies = new ArrayList<>();
			try (ScriptedProcess process = new ScriptedProcess(List.of(
				Turn.replies("reply-1").withCheckpoint(printed ->
				{
					if (printed == 0)
						writeTranscriptSnapshot(rootTranscript, childTranscript, 1);
				}),
				Turn.replies("reply-2").withCheckpoint(printed ->
				{
					if (printed == 0)
						writeTranscriptSnapshot(rootTranscript, childTranscript, 2);
				})), new ArrayList<>()); Writer output = new Writer()
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
						if (line.indexOf("\"turn_completed\"") >= 0)
						{
							Path copiedRoot = turnCopy.resolve("artifacts/session-transcripts/rollout-root.jsonl");
							Path copiedChild = turnCopy.resolve("artifacts/session-transcripts/rollout-child.jsonl");
							copies.add(readCopyOrMissing(copiedRoot, "missing-root") +
								readCopyOrMissing(copiedChild, "missing-child"));
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
				HarnessRunnerOptions options = new HarnessRunnerOptions(root.resolve("artifact"), root, root, "model-a",
					"effort-a", null, null, false, false, 1, List.of(), List.of(), null, List.of(), 0, null, 0,
					null, null, null, false, null, null, null, turnCopy, false, null, null);
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
					options, JsonMapper.builder().build(), Yielding.fileReplacer(), input, output);
			}

				requireThat(copies, "copies").isEqualTo(List.of("root-1\nchild-1\n", "root-2\nchild-2\n"));
			}
		}

	/**
	 * Supplies changes that a session makes to the read-only copies that stand in for its project's symbolic links to
	 * targets outside the project, each with how the run's diagnostic names the changed path.
	 *
	 * @return the change and the named path with how it changed, where {@code <notes>} stands for the folder of the
	 *   copy that {@code notes.md} leads to and {@code <tree>} for the folder of the one that {@code tree} leads to
	 */
	@DataProvider
	public Object[][] linkCopyChanges()
	{
		return new Object[][]{
			{"edit_after_making_writable", "<notes> (content changed, made writable)"},
			{"make_writable", "<notes> (made writable)"},
			{"delete_and_recreate", "<tree>/a.md (content changed, made writable)"},
			{"replace_by_directory", "<tree>/a.md (replaced by a directory)"},
			{"add_to_directory", "<tree>/added.md (added)"},
			{"delete", "<tree>/a.md (removed)"}};
	}

	/**
	 * Verifies that a run whose session changes a read-only copy that stands in for a project link to a target outside
	 * the project fails: the bridge fails with a diagnostic that names the changed path and says that it is a copy of a
	 * link target outside the project that the session changed, the run's manifest records the failure, and no receipt
	 * is written.
	 *
	 * @param change   the session's change
	 * @param expected the changed path and how the diagnostic names its change, as {@link #linkCopyChanges()} gives it
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "linkCopyChanges")
	public void failsRunWhoseSessionChangedLinkCopy(String change, String expected) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedRun fixture = new LinkedRun(cleanup);
			String named = expected.replace("<notes>", fixture.copyName("notes.md")).
				replace("<tree>", fixture.copyName("tree"));
			Turn turn = Turn.replies("done").withCheckpoint(printed ->
			{
				if (printed == 0)
					changeLinkCopy(fixture.project(), change);
			});
			try (ScriptedProcess process = new ScriptedProcess(List.of(turn), new ArrayList<>());
				StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
					"{\"type\":\"complete\"}\n"))
			{
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(fixture.run(), process,
					new TestHarnessRunnerScope()), fixture.manager(), fixture.options(), JsonMapper.builder().build(),
					Yielding.fileReplacer(), input, new StringWriter());
				throw new AssertionError("The run whose session changed a copy completed");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "diagnostic").contains(named).
					contains("The session changed read-only copies of symbolic link targets outside the project");
			}
			requireThat(Files.readString(fixture.run().directory().resolve("session.json")), "manifest").
				contains("\"failed\"").contains(named);
			requireThat(Files.exists(fixture.receipt()), "receipt").isFalse();
		}
	}

	/**
	 * Writes the root and child transcript files for one turn's snapshot.
	 *
	 * @param rootTranscript  the root transcript file
	 * @param childTranscript the child transcript file
	 * @param snapshot        the snapshot number written to both files
	 * @throws IOException if either transcript file cannot be written
	 */
	private static void writeTranscriptSnapshot(Path rootTranscript, Path childTranscript, int snapshot)
		throws IOException
	{
		Files.createDirectories(rootTranscript.getParent());
		Files.createDirectories(childTranscript.getParent());
		Files.writeString(rootTranscript, "root-" + snapshot + "\n");
		Files.writeString(childTranscript, "child-" + snapshot + "\n");
	}

	/**
	 * Verifies that a run that fails for another reason after its session changed a read-only copy that stands in for
	 * a project link to a target outside the project names the changed copy as well as the other reason: here the
	 * harness exits during the turn.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void namesChangedLinkCopyOfFailedRun() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedRun fixture = new LinkedRun(cleanup);
			String changed = fixture.copyName("notes.md") + " (content changed, made writable)";
			Turn turn = Turn.exits(143, "partial harness event").withCheckpoint(printed ->
			{
				if (printed == 0)
					changeLinkCopy(fixture.project(), "edit_after_making_writable");
			});
			try (ScriptedProcess process = new ScriptedProcess(List.of(turn), new ArrayList<>());
				StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
					"{\"type\":\"complete\"}\n"))
			{
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(fixture.run(), process,
					new TestHarnessRunnerScope()), fixture.manager(), fixture.options(), JsonMapper.builder().build(),
					Yielding.fileReplacer(), input, new StringWriter());
				throw new AssertionError("The run whose harness exited completed");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "diagnostic").contains(changed).contains("exit status 143");
			}
			requireThat(Files.readString(fixture.run().directory().resolve("session.json")), "manifest").
				contains("\"failed\"").contains(changed);
			requireThat(Files.exists(fixture.receipt()), "receipt").isFalse();
		}
	}

	/**
	 * Verifies that a run whose session leaves the read-only copies of its project's links to targets outside the
	 * project unchanged completes and writes its receipt, although the session changed a file of the project that a
	 * link to a target inside the project leads to, and a file of the project's own.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void completesRunThatLeftLinkCopiesUnchanged() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			LinkedRun fixture = new LinkedRun(cleanup);
			Turn turn = Turn.replies("done").withCheckpoint(printed ->
			{
				if (printed == 0)
				{
					Files.writeString(fixture.project().resolve("docs/guide.md"), "edited through the link");
					Files.writeString(fixture.project().resolve("own.md"), "edited");
				}
			});
			try (ScriptedProcess process = new ScriptedProcess(List.of(turn), new ArrayList<>());
				StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
					"{\"type\":\"complete\"}\n"))
			{
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(fixture.run(), process,
					new TestHarnessRunnerScope()), fixture.manager(), fixture.options(), JsonMapper.builder().build(),
					Yielding.fileReplacer(), input, new StringWriter());
			}
			requireThat(Files.readString(fixture.project().resolve("shared/guide.md")), "guide").
				isEqualTo("edited through the link");
			requireThat(Files.readString(fixture.run().directory().resolve("session.json")), "manifest").
				contains("\"completed\"");
			requireThat(Files.exists(fixture.receipt()), "receipt").isTrue();
		}
	}

	/**
	 * Makes one change that a session may make to the read-only copies of its project's links to targets outside the
	 * project, first making writable what the change needs, as a session that undoes the protection would.
	 *
	 * @param project the run's project
	 * @param change  the change, as {@link #linkCopyChanges()} names it
	 * @throws IOException if the change fails
	 */
	private static void changeLinkCopy(Path project, String change) throws IOException
	{
		// The project's links lead to the copies, which the session changes where they are
		Path notes = project.resolve("notes.md").toRealPath();
		Path tree = project.resolve("tree").toRealPath();
		Path file = tree.resolve("a.md");
		switch (change)
		{
			case "edit_after_making_writable" ->
			{
				PortableFiles.clearReadOnly(notes);
				Files.writeString(notes, "changed");
			}
			case "make_writable" -> PortableFiles.clearReadOnly(notes);
			case "add_to_directory" ->
			{
				PortableFiles.clearReadOnly(tree);
				Files.writeString(tree.resolve("added.md"), "added");
			}
			case "delete_and_recreate", "replace_by_directory", "delete" ->
			{
				PortableFiles.clearReadOnly(tree);
				PortableFiles.clearReadOnly(file);
				Files.delete(file);
				if (change.equals("delete_and_recreate"))
					Files.writeString(file, "other");
				else if (change.equals("replace_by_directory"))
					Files.createDirectory(file);
			}
			default -> throw new AssertionError(change);
		}
	}

	/**
	 * A managed run whose project the runner copied from a project with symbolic links: {@code notes.md} and
	 * {@code tree} lead to a file and a directory outside the project, so the project's links lead to read-only copies
	 * of them beside it, and {@code docs/guide.md} leads to {@code shared/guide.md} inside it. Its options name a
	 * transcript and a receipt.
	 *
	 * @param manager the run's manager
	 * @param run     the run
	 * @param project the run's project
	 * @param options the runner options
	 * @param receipt the receipt that the options name
	 */
	private record LinkedRun(HarnessRunManager manager, HarnessRun run, Path project, HarnessRunnerOptions options,
		Path receipt)
	{
		/**
		 * Creates the run.
		 *
		 * @param cleanup deletes the run's files
		 * @throws IOException if fixture I/O fails
		 */
		LinkedRun(TemporaryDirectories cleanup) throws IOException
		{
			this(cleanup.add(Files.createTempDirectory("cat-linked-run-")));
		}

		/**
		 * Creates the run in a directory.
		 *
		 * @param root the directory
		 * @throws IOException if fixture I/O fails
		 */
		private LinkedRun(Path root) throws IOException
		{
			this(new HarnessRunManager(new FileLocks(), root.resolve("runs"), Clock.systemUTC(),
				Yielding.fileReplacer()), root);
		}

		/**
		 * Creates the run with a manager.
		 *
		 * @param manager the run's manager
		 * @param root    the directory
		 * @throws IOException if fixture I/O fails
		 */
		private LinkedRun(HarnessRunManager manager, Path root) throws IOException
		{
			this(manager, root, manager.create("harness"));
		}

		/**
		 * Creates the run's project.
		 *
		 * @param manager the run's manager
		 * @param root    the directory
		 * @param run     the run
		 * @throws IOException if fixture I/O fails
		 */
		private LinkedRun(HarnessRunManager manager, Path root, HarnessRun run) throws IOException
		{
			this(manager, run, copy(manager, root, run), new HarnessRunnerOptions(root.resolve("artifact"),
				root.resolve("runs"), root, "model-a", "effort-a", root.resolve("evidence/transcript.jsonl"),
				root.resolve("evidence/receipt.json"), false, false, 1, List.of(), List.of(), null, List.of(), 0, null,
				0, null, null, null, false, null, null, null, null, false, null, null), root.resolve("evidence/receipt.json"));
		}

		/**
		 * Returns the name that the run's record gives the copy that a link of the run's project leads to.
		 *
		 * @param link the link's path relative to the project
		 * @return the copy's path relative to the run's directory, with {@code /} between names
		 * @throws UncheckedIOException if the link cannot be resolved
		 */
		String copyName(String link)
		{
			try
			{
				Path relative = run.directory().toRealPath().relativize(project.resolve(link).toRealPath());
				return relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
		}

		/**
		 * Copies the source project into the run.
		 *
		 * @param manager the run's manager
		 * @param root    the directory
		 * @param run     the run
		 * @return the run's project
		 * @throws IOException if fixture I/O fails
		 */
		private static Path copy(HarnessRunManager manager, Path root, HarnessRun run) throws IOException
		{
			Path outside = Files.createDirectories(root.resolve("outside/tree"));
			Files.writeString(root.resolve("outside/notes.md"), "notes");
			Files.writeString(outside.resolve("a.md"), "a");
			Path source = Files.createDirectories(root.resolve("source"));
			Files.writeString(Files.createDirectory(source.resolve("shared")).resolve("guide.md"), "guide");
			Files.createSymbolicLink(Files.createDirectory(source.resolve("docs")).resolve("guide.md"),
				Path.of("../shared/guide.md"));
			Files.writeString(source.resolve("own.md"), "own");
			Files.createSymbolicLink(source.resolve("notes.md"), Path.of("../outside/notes.md"));
			Files.createSymbolicLink(source.resolve("tree"), Path.of("../outside/tree"));
			manager.copyProject(source, run);
			return run.directory().resolve("project");
		}
	}

	/**
	 * Verifies that a bridge given an input file serves the turns that the file holds, so that a caller that cannot
	 * pipe records to the runner's standard input can name them instead.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void servesTurnsFromInputFile() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		List<String> received = new CopyOnWriteArrayList<>();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("one"), Turn.replies("two")), received);
			StringReader standardInput = new StringReader(""))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Files.createDirectories(run.directory().resolve("project"));
			Path evidence = cleanup.add(Files.createTempDirectory("cat-evidence-"));
			Path input = Files.writeString(evidence.resolve("turns.jsonl"), "{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
				"{\"type\":\"turn\",\"prompt\":\"second\"}\n{\"type\":\"complete\"}\n");
			HarnessRunnerOptions options = new HarnessRunnerOptions(root.resolve("artifact"), root, root, "model-a",
				"effort-a", null, null, false, false, 1,
				List.of(), List.of(), null, List.of(), 0, null, 0, input, null, null, false, null,
				null, null, null, false, null, null);
			StringWriter output = new StringWriter();

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
				options, JsonMapper.builder().build(), Yielding.fileReplacer(), standardInput, output);

			requireThat(received, "received").isEqualTo(List.of("first", "second"));
			requireThat(output.toString(), "output").contains("\"type\":\"completed\"");
		}
	}

	/**
	 * Verifies that a runner that serves its session in its own process holds a conversation: the bridge serves the
	 * messages that the asking session appended to {@code to-child.jsonl} as its records, an interrupt ends only the
	 * turn in progress although the harness fails that turn, as Claude Code does, and {@code from-child.jsonl}
	 * receives the bridge's records, a {@code turn_end} line after each turn and an {@code ended} line last.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test(timeOut = 10_000)
	public void holdsConversationInOwnProcess() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		List<String> received = new CopyOnWriteArrayList<>();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.blocksUntilInterrupted(), Turn.replies("two")),
				received, InterruptedTurn.FAILS);
			StringReader standardInput = new StringReader(""))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Files.createDirectories(run.directory().resolve("project"));
			Path folder = Files.createDirectory(cleanup.add(Files.createTempDirectory("cat-asking-")).
				resolve("conversation"));
			// The asking session may write its messages before it starts the runner
			Files.writeString(folder.resolve("to-child.jsonl"), "{\"turn\": \"first\"}\n{\"interrupt\": true}\n" +
				"{\"turn\": \"second\"}\n{\"end\": true}\n");
			HarnessRunnerOptions options = HarnessRunnerOptions.parse(new String[]{"--artifact",
				root.resolve("artifact").toString(), "--results", root.toString(), "--model", "model-a",
				"--effort", "effort-a", "--conversation", folder.toString()}, "harness",
				HarnessTemporaryRoot.of(root));
			StringWriter output = new StringWriter();

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
				options, JsonMapper.builder().build(), Yielding.fileReplacer(), standardInput, output);

			requireThat(received, "received").isEqualTo(List.of("first", "second"));
			JsonMapper json = JsonMapper.builder().build();
			List<String> fromChild = new ArrayList<>();
			for (String line : Files.readAllLines(folder.resolve("from-child.jsonl")))
			{
				String type = json.readTree(line).path("type").asString("");
				if (type.isEmpty())
					fromChild.add(line);
				else if (List.of("interrupted", "turn_failed", "turn_completed", "completed").contains(type))
					fromChild.add(type);
			}
			requireThat(fromChild, "fromChild").isEqualTo(List.of("interrupted", "turn_failed",
				"{\"turn_end\":1,\"status\":\"interrupted\"}", "turn_completed",
				"{\"turn_end\":2,\"status\":\"completed\"}", "completed", "{\"ended\":\"completed\"}"));
			requireThat(output.toString(), "output").contains("\"assistant_response\":\"two\"");
		}
	}

	/**
	 * Verifies that a conversation's folder receives the harness's events in {@code events.jsonl}, whole and in order,
	 * and that {@code from-child.jsonl} holds none of them, only the records that a driving session needs, with each
	 * turn's {@code turn_end} line after the turn's events and its {@code turn_completed} record.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test(timeOut = 10_000)
	public void splitsHarnessEventsIntoOwnFile() throws IOException
	{
		List<String> received = new CopyOnWriteArrayList<>();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("one", "event a", "event b"),
				Turn.replies("two", "event c")), received);
			StringReader standardInput = new StringReader(""))
		{
			Path folder = askingFolder(cleanup,
				"{\"turn\": \"first\"}\n{\"turn\": \"second\"}\n{\"end\": true}\n");

			serveConversation(cleanup, folder, process, standardInput);

			JsonMapper json = JsonMapper.builder().build();
			List<String> events = new ArrayList<>();
			for (String line : Files.readAllLines(folder.resolve(Conversation.EVENTS_FILE)))
			{
				JsonNode record = json.readTree(line);
				requireThat(record.path("type").asString(), "type").isEqualTo("harness_event");
				events.add(record.path("event").asString());
			}
			requireThat(events, "events").isEqualTo(List.of("event a", "event b", "event c"));
			List<String> fromChild = new ArrayList<>();
			for (String line : Files.readAllLines(folder.resolve(Conversation.FROM_CHILD_FILE)))
			{
				String type = json.readTree(line).path("type").asString("");
				requireThat(type, "type").isNotEqualTo("harness_event");
				if (type.equals("turn_completed"))
					fromChild.add(type);
				else if (type.isEmpty())
					fromChild.add(line);
			}
			requireThat(fromChild, "fromChild").isEqualTo(List.of("turn_completed",
				"{\"turn_end\":1,\"status\":\"completed\"}", "turn_completed",
				"{\"turn_end\":2,\"status\":\"completed\"}", "{\"ended\":\"completed\"}"));
		}
	}

	/**
	 * Returns the turns that a harness fails while it keeps running, and the cause that each reports.
	 *
	 * @return pairs of a failing turn and the cause that the turn's failure names
	 */
	@DataProvider(name = "failedTurns")
	public Object[][] failedTurns()
	{
		return new Object[][]
			{
				// Codex reports the failed turn in a result that did not complete, and Claude Code throws
				{Turn.failsAndRuns("You've hit your weekly limit"), "You've hit your weekly limit"},
				{Turn.rejected(), "The harness rejected the turn"}
			};
	}

	/**
	 * Verifies that a conversation stays open after a turn fails while the harness keeps running, as when a usage limit
	 * rejects the turn: the turn ends with the status {@code failed} after a record that names the cause, the next turn
	 * that the asking session sends reaches the same harness session, and the conversation then ends as completed.
	 *
	 * @param failing the turn that the harness fails
	 * @param cause   the cause that the failure names
	 * @throws IOException if the bridge fails
	 */
	@Test(dataProvider = "failedTurns", timeOut = 10_000)
	public void continuesConversationAfterFailedTurn(Turn failing, String cause) throws IOException
	{
		List<String> received = new CopyOnWriteArrayList<>();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(failing, Turn.replies("two")), received);
			StringReader standardInput = new StringReader(""))
		{
			Path folder = askingFolder(cleanup,
				"{\"turn\": \"first\"}\n{\"turn\": \"second\"}\n{\"end\": true}\n");

			serveConversation(cleanup, folder, process, standardInput);

			requireThat(received, "received").isEqualTo(List.of("first", "second"));
			JsonMapper json = JsonMapper.builder().build();
			List<String> fromChild = new ArrayList<>();
			for (String line : Files.readAllLines(folder.resolve("from-child.jsonl")))
			{
				JsonNode record = json.readTree(line);
				String type = record.path("type").asString("");
				if (type.isEmpty())
					fromChild.add(line);
				else if (type.equals("turn_failed"))
				{
					requireThat(record.path("error").asString(), "error").contains(cause);
					fromChild.add(type);
				}
				else if (List.of("turn_completed", "completed").contains(type))
					fromChild.add(type);
			}
			requireThat(fromChild, "fromChild").isEqualTo(List.of("turn_failed",
				"{\"turn_end\":1,\"status\":\"failed\"}", "turn_completed", "{\"turn_end\":2,\"status\":\"completed\"}",
				"completed", "{\"ended\":\"completed\"}"));
		}
	}

	/**
	 * Verifies that ending a conversation whose last turn failed ends it as failed, with the failure of that turn, as
	 * it did before a conversation could continue after a failed turn.
	 *
	 * @throws IOException if the conversation's files cannot be read
	 */
	@Test(timeOut = 10_000)
	public void endsConversationWhoseLastTurnFailed() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.failsAndRuns("usage limit")),
				new CopyOnWriteArrayList<>());
			StringReader standardInput = new StringReader(""))
		{
			Path folder = askingFolder(cleanup, "{\"turn\": \"first\"}\n{\"end\": true}\n");
			try
			{
				serveConversation(cleanup, folder, process, standardInput);
				throw new AssertionError("The conversation ended as completed");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("usage limit");
			}

			List<String> lines = Files.readAllLines(folder.resolve("from-child.jsonl"));
			requireThat(lines.get(lines.size() - 2), "turnEnd").isEqualTo("{\"turn_end\":1,\"status\":\"failed\"}");
			requireThat(lines.getLast(), "last").isEqualTo("{\"ended\":\"failed\"}");
		}
	}

	/**
	 * Verifies that a conversation ends when the harness itself ends during a failed turn, so that no later turn
	 * reaches a harness that is gone.
	 *
	 * @throws IOException if the conversation's files cannot be read
	 */
	@Test(timeOut = 10_000)
	public void endsWhenHarnessEndsInFailedTurn() throws IOException
	{
		List<String> received = new CopyOnWriteArrayList<>();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.exits(143), Turn.replies("two")), received);
			StringReader standardInput = new StringReader(""))
		{
			Path folder = askingFolder(cleanup,
				"{\"turn\": \"first\"}\n{\"turn\": \"second\"}\n{\"end\": true}\n");
			try
			{
				serveConversation(cleanup, folder, process, standardInput);
				throw new AssertionError("The conversation ended as completed");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("exit status 143");
			}

			requireThat(received, "received").isEqualTo(List.of("first"));
			List<String> lines = Files.readAllLines(folder.resolve("from-child.jsonl"));
			requireThat(lines.getLast(), "last").isEqualTo("{\"ended\":\"failed\"}");
		}
	}

	/**
	 * Verifies that a bridge that serves the records of a requested conversation, which its runner marks with
	 * {@code --continue-after-failed-turn}, keeps serving after a turn fails while the harness keeps running, and that
	 * a bridge without the option still fails at the request that follows the failed turn.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test(timeOut = 10_000)
	public void continuesRequestedConversation() throws IOException
	{
		String records = "{\"type\":\"turn\",\"prompt\":\"first\"}\n{\"type\":\"turn\",\"prompt\":\"second\"}\n" +
			"{\"type\":\"complete\"}\n";
		for (boolean continues : List.of(true, false))
		{
			List<String> received = new CopyOnWriteArrayList<>();
			try (TemporaryDirectories cleanup = new TemporaryDirectories();
				ScriptedProcess process = new ScriptedProcess(List.of(Turn.failsAndRuns("usage limit"),
					Turn.replies("two")), received);
				StringReader input = new StringReader(records))
			{
				Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
				HarnessRunManager manager = new HarnessRunManager(new FileLocks(), root, Clock.systemUTC(),
					Yielding.fileReplacer());
				HarnessRun run = manager.create("harness");
				Files.createDirectories(run.directory().resolve("project"));
				List<String> arguments = new ArrayList<>(List.of("--artifact", root.resolve("artifact").toString(),
					"--results", root.toString(), "--model", "model-a", "--effort", "effort-a"));
				if (continues)
					arguments.add("--continue-after-failed-turn");
				HarnessRunnerOptions options = HarnessRunnerOptions.parse(arguments.toArray(String[]::new), "harness",
					HarnessTemporaryRoot.of(root));
				StringWriter output = new StringWriter();

				if (continues)
				{
					HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()),
						manager, options, JsonMapper.builder().build(), Yielding.fileReplacer(), input, output);

					requireThat(received, "received").isEqualTo(List.of("first", "second"));
					requireThat(output.toString(), "output").contains("\"type\":\"turn_failed\"").
						contains("\"assistant_response\":\"two\"").contains("\"type\":\"completed\"");
				}
				else
				{
					try
					{
						HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()),
							manager, options, JsonMapper.builder().build(), Yielding.fileReplacer(), input, output);
						throw new AssertionError("The bridge served the turn after the failed turn");
					}
					catch (IOException e)
					{
						requireThat(e.getMessage(), "message").contains("usage limit");
					}
					requireThat(received, "received").isEqualTo(List.of("first"));
				}
			}
		}
	}

	/**
	 * Returns the ways a bridge gets its records, with the purpose of the turns that it sends.
	 *
	 * @return the way, and the purpose
	 */
	@DataProvider
	public static Object[][] turnModes()
	{
		return new Object[][]{{BridgeRecords.CONVERSATION_FOLDER, TurnMode.CONVERSATION},
			{BridgeRecords.REQUESTED_CONVERSATION, TurnMode.CONVERSATION}, {BridgeRecords.INPUT, TurnMode.STANDARD}};
	}

	/**
	 * Where a bridge reads its records.
	 */
	private enum BridgeRecords
	{
		/**
		 * The messages that an asking session appended to a conversation's folder.
		 */
		CONVERSATION_FOLDER,
		/**
		 * The records that a request server writes for a requested conversation, whose runner got
		 * {@code --continue-after-failed-turn}.
		 */
		REQUESTED_CONVERSATION,
		/**
		 * Records of any other bridge, as a trial's.
		 */
		INPUT
	}

	/**
	 * Verifies that the turns of a conversation are sent to the harness as conversation turns, whether the asking
	 * session's messages come from a conversation's folder or from a requested conversation's runner, and that the
	 * turns of any other bridge are standard turns, whose replies a caller judges.
	 *
	 * @param records the way the bridge gets its records
	 * @param expected the purpose of the turns that the bridge sends
	 * @throws IOException if the bridge fails
	 */
	@Test(timeOut = 10_000, dataProvider = "turnModes")
	public void sendsTurnsForTheirPurpose(BridgeRecords records, TurnMode expected) throws IOException
	{
		String input = "";
		if (records != BridgeRecords.CONVERSATION_FOLDER)
		{
			input = "{\"type\":\"turn\",\"prompt\":\"first\"}\n{\"type\":\"turn\",\"prompt\":\"second\"}\n" +
				"{\"type\":\"complete\"}\n";
		}
		List<String> received = new CopyOnWriteArrayList<>();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("one"), Turn.replies("two")), received);
			StringReader standardInput = new StringReader(input))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(new FileLocks(), root, Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Files.createDirectories(run.directory().resolve("project"));
			List<String> arguments = new ArrayList<>(List.of("--artifact", root.resolve("artifact").toString(),
				"--results", root.toString(), "--model", "model-a", "--effort", "effort-a"));
			switch (records)
			{
				case CONVERSATION_FOLDER ->
				{
					Path folder = Files.createDirectory(cleanup.add(Files.createTempDirectory("cat-asking-")).
						resolve("conversation"));
					Files.writeString(folder.resolve("to-child.jsonl"),
						"{\"turn\": \"first\"}\n{\"turn\": \"second\"}\n{\"end\": true}\n");
					arguments.addAll(List.of("--conversation", folder.toString()));
				}
				case REQUESTED_CONVERSATION -> arguments.add("--continue-after-failed-turn");
				case INPUT ->
				{
				}
			}
			HarnessRunnerOptions options = HarnessRunnerOptions.parse(arguments.toArray(String[]::new), "harness",
				HarnessTemporaryRoot.of(root));

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
				options, JsonMapper.builder().build(), Yielding.fileReplacer(), standardInput, new StringWriter());

			requireThat(received, "received").isEqualTo(List.of("first", "second"));
			requireThat(process.turnModes(), "turnModes").isEqualTo(List.of(expected, expected));
		}
	}

	/**
	 * Verifies that an asking session that ends a conversation while a turn is in progress can still stop the turn:
	 * the {@code interrupt} that follows {@code end} ends the turn, and the conversation then ends. Otherwise a turn
	 * that never finishes keeps the launcher running, and nothing that the asking session appends can stop it.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test(timeOut = 10_000)
	public void interruptsTurnThatEndWaitsFor() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		List<String> received = new CopyOnWriteArrayList<>();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.blocksUntilInterrupted()), received,
				InterruptedTurn.FAILS);
			StringReader standardInput = new StringReader(""))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Files.createDirectories(run.directory().resolve("project"));
			Path folder = Files.createDirectory(cleanup.add(Files.createTempDirectory("cat-asking-")).
				resolve("conversation"));
			Files.writeString(folder.resolve("to-child.jsonl"), "{\"turn\": \"first\"}\n{\"end\": true}\n" +
				"{\"interrupt\": true}\n");
			HarnessRunnerOptions options = HarnessRunnerOptions.parse(new String[]{"--artifact",
				root.resolve("artifact").toString(), "--results", root.toString(), "--model", "model-a",
				"--effort", "effort-a", "--conversation", folder.toString()}, "harness",
				HarnessTemporaryRoot.of(root));

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
				options, JsonMapper.builder().build(), Yielding.fileReplacer(), standardInput, new StringWriter());

			requireThat(received, "received").isEqualTo(List.of("first"));
			JsonMapper json = JsonMapper.builder().build();
			List<String> fromChild = new ArrayList<>();
			for (String line : Files.readAllLines(folder.resolve("from-child.jsonl")))
			{
				String type = json.readTree(line).path("type").asString("");
				if (type.isEmpty())
					fromChild.add(line);
				else if (List.of("interrupted", "turn_failed", "completed").contains(type))
					fromChild.add(type);
			}
			requireThat(fromChild, "fromChild").isEqualTo(List.of("interrupted", "turn_failed",
				"{\"turn_end\":1,\"status\":\"interrupted\"}", "completed", "{\"ended\":\"completed\"}"));
		}
	}

	/**
	 * Verifies that a bridge writes one {@code session_transcript} record with the native transcript's path once the
	 * process names the session's transcript, not once per turn, and that the receipt lists the transcript copies that
	 * the session left in the run before the run's runtime files were pruned.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void recordsSessionTranscriptOnce() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-I7
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
				"{\"type\":\"turn\",\"prompt\":\"second\"}\n{\"type\":\"complete\"}\n"))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Files.createDirectories(run.directory().resolve("project"));
			Path nativeFile = run.directory().resolve("home/session-7.jsonl");
			Path evidence = cleanup.add(Files.createTempDirectory("cat-evidence-"));
			Path transcript = evidence.resolve("transcript.jsonl");
			Path receipt = evidence.resolve("receipt.json");
			HarnessRunnerOptions options = new HarnessRunnerOptions(root.resolve("artifact"), root, root, "model-a",
				"effort-a", transcript, receipt, true, false, 1,
				List.of(), List.of(), null, List.of(), 0, null, 0, null, null, null, false, null,
				null, null, null, false, null, null);
			JsonMapper json = JsonMapper.builder().build();
			// The harness writes the session's transcript in its home as it works on the session
			Files.createDirectories(nativeFile.getParent());
			Files.writeString(nativeFile, "{}\n");

			try (ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("one"), Turn.replies("two")),
				new ArrayList<>(), nativeFile, new HarnessSessionTranscripts(run.directory().resolve("home"),
				run.sessionTranscripts(), name -> name.endsWith(".jsonl"))))
			{
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()),
					manager, options, json, Yielding.fileReplacer(), input, new StringWriter());
			}

			List<String> records = Files.readAllLines(transcript).stream().
				filter(line -> line.contains("\"type\":\"session_transcript\"")).toList();
			requireThat(records, "records").size().isEqualTo(1);
			requireThat(records.getFirst(), "record").contains(nativeFile.toString().replace("\\", "\\\\"));
			requireThat(Files.exists(run.directory().resolve("home")), "homeExists").isFalse();
			requireThat(HarnessRunReceipt.read(json, receipt, run.directory().resolve("project")).
				sessionTranscripts(), "sessionTranscripts").
				isEqualTo(List.of(run.sessionTranscripts().resolve("session-7.jsonl")));
		}
	}

	/**
	 * Verifies that the bridge records the identifier that the harness gave its session in the run's manifest as soon as
	 * the harness reports it, and tells its client the identifier in one record.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void recordsHarnessSessionWhenReported() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
				"{\"type\":\"turn\",\"prompt\":\"second\"}\n{\"type\":\"complete\"}\n"))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root, Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Path evidence = cleanup.add(Files.createTempDirectory("cat-evidence-"));
			Path transcript = evidence.resolve("transcript.jsonl");
			HarnessRunnerOptions options = new HarnessRunnerOptions(root.resolve("artifact"), root, root, "model-a",
				"effort-a", transcript, null, false, false, 1,
				List.of(), List.of(), null, List.of(), 0, null, 0, null, null, null, false, null,
				null, null, null, false, null, null);
			JsonMapper json = JsonMapper.builder().build();
			AtomicReference<ScriptedProcess> harness = new AtomicReference<>();
			// The harness reports its session while it works on the first turn, as Claude Code does
			try (ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("one").withCheckpoint(
				_ -> harness.get().reportHarnessSession("5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90")),
				Turn.replies("two")), new ArrayList<>()))
			{
				harness.set(process);
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()),
					manager, options, json, Yielding.fileReplacer(), input, new StringWriter());
			}

			List<JsonNode> records = Files.readAllLines(transcript).stream().map(json::readTree).toList();
			List<String> types = records.stream().map(record -> record.path("type").asString()).
				filter(type -> !type.equals("harness_event")).toList();
			requireThat(types, "types").isEqualTo(List.of("started", "session_id", "turn_completed", "turn_completed",
				"completed"));
			JsonNode reported = records.stream().filter(record -> record.path("type").asString().equals("session_id")).
				findFirst().orElseThrow();
			requireThat(reported.path("session_id").asString(), "sessionId").
				isEqualTo("5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90");
			requireThat(manager.harnessSessionId(run), "recorded").
				isEqualTo(Optional.of("5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90"));
		}
	}

	/**
	 * Verifies that a run in a slot whose input ends before its {@code complete} record, because the server that
	 * feeds it ended, records no end and keeps its home, so that its harness session can continue.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void runInSlotOutlivesItsServer() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n"))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root, Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedRunId("s-1");
			HarnessRun run = manager.create("harness");
			Path evidence = cleanup.add(Files.createTempDirectory("cat-evidence-"));
			HarnessRunnerOptions options = new HarnessRunnerOptions(root.resolve("artifact"), root, root, "model-a",
				"effort-a", evidence.resolve("transcript.jsonl"), evidence.resolve("receipt.json"), false, false, 1,
				List.of(), List.of(), null, List.of(), 0, null, 0, null, null, null, true, null,
				null, null, null, false, "s-1", null);
			JsonMapper json = JsonMapper.builder().build();
			try (ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("one")), new ArrayList<>()))
			{
				manager.recordHarnessSession(run, "5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90");
				IOException failure = expectThrows(IOException.class, () -> HarnessRunnerBridge.serve(
					() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager, options, json,
					Yielding.fileReplacer(), input, new StringWriter()));
				requireThat(failure.getMessage(), "failure").contains("without a complete message");
			}

			String manifest = Files.readString(run.directory().resolve("session.json"));
			requireThat(manifest, "manifest").contains("\"state\": \"running\"").
				contains("\"harness_session_id\": \"5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90\"");
			requireThat(run.directory().resolve("home"), "home").isDirectory();
		}
	}

	/**
	 * Verifies that a bridge whose input ends while its turn runs returns only after the turn has stopped writing the
	 * run, so that the caller can remove the run once the bridge has returned. The turn's first write to the run is held
	 * until the bridge has stopped the turn and then either waits or has returned.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test(timeOut = 30_000)
	public void bridgeReturnsAfterItsTurnStopsWriting() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			TurnWriteGate gate = new TurnWriteGate(Thread.currentThread());
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root, Clock.systemUTC(),
				new FileReplacer(Platform.current(), Thread::yield, gate)).withFixedRunId("s-1");
			HarnessRun run = manager.create("harness");
			Path evidence = cleanup.add(Files.createTempDirectory("cat-evidence-"));
			HarnessRunnerOptions options = new HarnessRunnerOptions(root.resolve("artifact"), root, root, "model-a",
				"effort-a", evidence.resolve("transcript.jsonl"), evidence.resolve("receipt.json"), false, false, 1,
				List.of(), List.of(), null, List.of(), 0, null, 0, null, null, null, true, null,
				null, null, null, false, "s-1", null);
			JsonMapper json = JsonMapper.builder().build();
			try (ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("one")), new ArrayList<>());
				FailingReader input = FailingReader.ending("{\"type\":\"turn\",\"prompt\":\"first\"}\n",
					gate.held()))
			{
				manager.recordHarnessSession(run, "5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90");
				gate.arm();
				expectThrows(IOException.class, () -> HarnessRunnerBridge.serve(
					() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager, options, json,
					Yielding.fileReplacer(), input, new StringWriter()));
				requireThat(gate.returned(), "heldAfterReturn").isFalse();
			}
		}
	}

	/**
	 * Verifies that a runner that resumes a run says so in its {@code started} record, numbers its turns after the last
	 * turn that ended, and ends the run normally once its harness has reported the run's session.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void resumedRunContinuesItsTurns() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"again\"}\n" +
				"{\"type\":\"complete\"}\n"))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root, Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedRunId("s-1");
			HarnessRun run = manager.create("harness");
			manager.recordHarnessSession(run, RESUMED_SESSION);
			manager.writeTurnOutput(run, 1, new HarnessProcessResult(0, "first", ""));
			Path evidence = cleanup.add(Files.createTempDirectory("cat-evidence-"));
			Path transcript = evidence.resolve("transcript.jsonl");
			HarnessRunnerOptions options = new HarnessRunnerOptions(root.resolve("artifact"), root, root, "model-a",
				"effort-a", transcript, null, false, false, 1,
				List.of(), List.of(), null, List.of(), 0, null, 0, null, null, null, true, null,
				null, null, null, false, "s-1", RESUMED_SESSION);
			JsonMapper json = JsonMapper.builder().build();
			AtomicReference<ScriptedProcess> harness = new AtomicReference<>();
			try (ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("two").withCheckpoint(
				_ -> harness.get().reportHarnessSession(RESUMED_SESSION))), new ArrayList<>()))
			{
				harness.set(process);
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()),
					manager, options, json, Yielding.fileReplacer(), input, new StringWriter());
			}

			JsonNode started = json.readTree(Files.readAllLines(transcript).getFirst());
			requireThat(started.path("type").asString(), "type").isEqualTo("started");
			requireThat(started.path("resumed").asBoolean(), "resumed").isTrue();
			requireThat(manager.turnNumber(run), "turnNumber").isEqualTo(2);
			requireThat(Files.readString(run.directory().resolve("session.json")), "manifest").
				contains("\"state\": \"completed\"");
		}
	}

	/**
	 * Verifies that a runner whose harness continues another session than the run that it resumes records, or none,
	 * fails without ending the run, so that the run stays retained for a later launch.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void resumeOfAnotherSessionKeepsRun() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"again\"}\n" +
				"{\"type\":\"complete\"}\n"))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root, Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedRunId("s-1");
			HarnessRun run = manager.create("harness");
			manager.recordHarnessSession(run, RESUMED_SESSION);
			Path evidence = cleanup.add(Files.createTempDirectory("cat-evidence-"));
			HarnessRunnerOptions options = new HarnessRunnerOptions(root.resolve("artifact"), root, root, "model-a",
				"effort-a", evidence.resolve("transcript.jsonl"), null, false, false, 1,
				List.of(), List.of(), null, List.of(), 0, null, 0, null, null, null, true, null,
				null, null, null, false, "s-1", RESUMED_SESSION);
			JsonMapper json = JsonMapper.builder().build();
			AtomicReference<ScriptedProcess> harness = new AtomicReference<>();
			try (ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("two").withCheckpoint(
				_ -> harness.get().reportHarnessSession("0b9e8d7c-6a5f-4e3d-8c2b-1a0f9e8d7c6b"))), new ArrayList<>()))
			{
				harness.set(process);
				expectThrows(IOException.class, () -> HarnessRunnerBridge.serve(
					() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager, options, json,
					Yielding.fileReplacer(), input, new StringWriter()));
			}

			requireThat(Files.readString(run.directory().resolve("session.json")), "manifest").
				contains("\"state\": \"running\"").contains(RESUMED_SESSION);
		}
	}

	/**
	 * Verifies that a bridge whose options did not ask for session transcripts, and whose process therefore keeps none,
	 * writes no record for them and lists none in the receipt.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void leavesSessionTranscriptsOutUnlessAsked() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-I7
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("done")), new ArrayList<>());
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
				"{\"type\":\"complete\"}\n"))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Files.createDirectories(run.directory().resolve("project"));
			Path evidence = cleanup.add(Files.createTempDirectory("cat-evidence-"));
			Path transcript = evidence.resolve("transcript.jsonl");
			Path receipt = evidence.resolve("receipt.json");
			HarnessRunnerOptions options = new HarnessRunnerOptions(root.resolve("artifact"), root, root, "model-a",
				"effort-a", transcript, receipt, false, false, 1,
				List.of(), List.of(), null, List.of(), 0, null, 0, null, null, null, false, null,
				null, null, null, false, null, null);
			JsonMapper json = JsonMapper.builder().build();

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
				options, json, Yielding.fileReplacer(), input, new StringWriter());

			requireThat(Files.readString(transcript), "transcript").doesNotContain("session_transcript");
			requireThat(HarnessRunReceipt.read(json, receipt, run.directory().resolve("project")).
				sessionTranscripts(), "sessionTranscripts").isEmpty();
			requireThat(Files.exists(run.sessionTranscripts()), "directoryExists").isFalse();
		}
	}

	/**
	 * Verifies a bridge protocol failure retains an owned run with its failed state.
	 *
	 * @throws IOException if the bridge fails unexpectedly
	 */
	@Test
	public void retainsFailedManagedRun() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			try (ScriptedProcess process = new ScriptedProcess(List.of(), new ArrayList<>());
				StringReader input = new StringReader("{\"type\":\"unknown\"}\n"))
			{
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
					input, new StringWriter());
				throw new AssertionError("Expected the bridge protocol failure");
			}
			catch (IOException e)
			{
				requireThat(e, "failure").isNotNull();
			}

			requireThat(Files.exists(run.directory()), "runDirectory").isTrue();
			requireThat(Files.readString(run.directory().resolve("session.json")), "manifest").contains("\"failed\"");
		}
	}

	/**
	 * Verifies a harness process that exits before completing a turn records its exit status and leaves the owned run
	 * failed rather than allowing the bridge to complete it.
	 *
	 * @throws IOException if filesystem setup fails
	 */
	@Test
	public void recordsFailedHarnessTurn() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			try (ScriptedProcess process = new ScriptedProcess(List.of(Turn.exits(143, "partial harness event")),
				new ArrayList<>());
				StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
					"{\"type\":\"complete\"}\n"))
			{
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
					input, new StringWriter());
				throw new AssertionError("Expected the harness process failure");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("exit status 143");
			}

			String manifest = Files.readString(run.directory().resolve("session.json"));
			requireThat(manifest, "manifest").contains("\"state\": \"failed\"").
				contains("\"exit_code\": 143").contains("exit status 143");
		}
	}

	/**
	 * Verifies that the bridge reports a turn that fails, whether the harness ends before completing it or rejects it,
	 * as soon as the turn ends, without waiting for the client's next request. The client sends its next request only
	 * after the bridge has reported the failure, so a bridge that waits for that request fails the test's time limit.
	 *
	 * @throws IOException if filesystem setup fails
	 */
	@Test(timeOut = 10_000)
	public void reportsFailedTurnWhenItEnds() throws IOException
	{
		try (ScriptedProcess process = new ScriptedProcess(List.of(Turn.exits(143, "partial harness event")),
			new ArrayList<>()))
		{
			requireTurnFailureReported(process, "exit status 143");
		}
		try (ScriptedProcess process = new ScriptedProcess(List.of(Turn.rejected()), new ArrayList<>()))
		{
			requireTurnFailureReported(process, "The harness rejected the turn");
		}
	}

	/**
	 * Runs one turn that fails and verifies that the bridge reports the failure before the client's next request.
	 *
	 * @param process  the harness process, whose turn fails
	 * @param expected text that the failure's description contains
	 * @throws IOException if filesystem setup fails
	 */
	private static void requireTurnFailureReported(HarnessProcess process, String expected) throws IOException
	{
		CountDownLatch turnFailed = new CountDownLatch(1);
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			Reader input = new GatedReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n", turnFailed,
				"{\"type\":\"complete\"}\n", new CountDownLatch(1), "");
			Writer output = new SignalingWriter("\"type\":\"turn_failed\"", turnFailed, new CountDownLatch(0)))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			try
			{
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), input,
					output);
				throw new AssertionError("Expected the turn's failure");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains(expected);
			}
			JsonNode failure = null;
			JsonMapper json = JsonMapper.builder().build();
			for (String line : output.toString().lines().toList())
			{
				JsonNode record = json.readTree(line);
				if (record.path("type").asString().equals("turn_failed"))
					failure = record;
			}
			requireThat(failure, "failure").withContext(output, "output").isNotNull();
			requireThat(failure.path("error").asString(), "error").contains(expected);
		}
	}

	/**
	 * Verifies a clean harness exit without a terminal turn event is still recorded as a failed bridge turn.
	 *
	 * @throws IOException if filesystem setup fails
	 */
	@Test
	public void rejectsCleanExitBeforeTurnCompletion() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			try (ScriptedProcess process = new ScriptedProcess(List.of(Turn.exits(0, "partial harness event")),
				new ArrayList<>());
				StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
					"{\"type\":\"complete\"}\n"))
			{
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
					input, new StringWriter());
				throw new AssertionError("Expected the incomplete harness turn to fail");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("before completing the requested turn");
			}

			requireThat(Files.readString(run.directory().resolve("session.json")), "manifest").
				contains("\"state\": \"failed\"");
		}
	}

	/**
	 * Verifies that a failure of the bridge's input while a turn is still running stops that turn and closes the
	 * session, instead of waiting for a turn that may never end. The bridge runs on its own thread so that a bridge
	 * that waits forever fails the test instead of hanging it.
	 *
	 * @throws IOException if filesystem setup fails
	 * @throws InterruptedException if the test is interrupted while it waits
	 */
	@Test(timeOut = 30_000)
	public void stopsRunningTurnWhenInputFails() throws IOException, InterruptedException
	{
		CountDownLatch started = new CountDownLatch(1);
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.blocksUntilInterrupted().announcing(started)),
				new ArrayList<>());
			FailingReader input = new FailingReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n", started))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			CompletableFuture<HarnessRun> serving = new CompletableFuture<>();
			Thread.ofPlatform().daemon().name("bridge-under-test").start(() ->
			{
				try
				{
					serving.complete(HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process,
						new TestHarnessRunnerScope()), input, new StringWriter()));
				}
				catch (IOException | RuntimeException e)
				{
					serving.completeExceptionally(e);
				}
			});
			try
			{
				serving.get(5, TimeUnit.SECONDS);
				throw new AssertionError("Expected the input failure");
			}
			catch (ExecutionException e)
			{
				requireThat(e.getCause(), "cause").isInstanceOf(IOException.class);
				requireThat(e.getCause().getMessage(), "message").contains("input failed");
			}
			catch (TimeoutException e)
			{
				throw new AssertionError("The bridge did not stop the running turn after its input failed", e);
			}
			finally
			{
				// Ends the turn, so that a test that failed does not leave a thread waiting
				process.interrupt();
			}

			requireThat(process.isAlive(), "alive").isFalse();
		}
	}

	/**
	 * Verifies that a bridge whose input ends without {@code complete} while a turn is still running stops that turn
	 * and closes the session, so that a runner whose requester has gone away stops instead of serving a turn that no
	 * one reads. The bridge runs on its own thread so that a bridge that waits forever fails the test instead of
	 * hanging it.
	 *
	 * @throws IOException if filesystem setup fails
	 * @throws InterruptedException if the test is interrupted while it waits
	 */
	@Test(timeOut = 30_000)
	public void stopsRunningTurnWhenInputEnds() throws IOException, InterruptedException
	{
		CountDownLatch started = new CountDownLatch(1);
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.blocksUntilInterrupted().announcing(started)),
				new ArrayList<>());
			FailingReader input = FailingReader.ending("{\"type\":\"turn\",\"prompt\":\"first\"}\n",
				started))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			CompletableFuture<HarnessRun> serving = new CompletableFuture<>();
			Thread.ofPlatform().daemon().name("bridge-under-test").start(() ->
			{
				try
				{
					serving.complete(HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process,
						new TestHarnessRunnerScope()), input, new StringWriter()));
				}
				catch (IOException | RuntimeException e)
				{
					serving.completeExceptionally(e);
				}
			});
			try
			{
				serving.get(5, TimeUnit.SECONDS);
				throw new AssertionError("Expected the bridge to fail for its unfinished input");
			}
			catch (ExecutionException e)
			{
				requireThat(e.getCause(), "cause").isInstanceOf(IOException.class);
				requireThat(e.getCause().getMessage(), "message").contains("without a complete message");
			}
			catch (TimeoutException e)
			{
				throw new AssertionError("The bridge did not stop the running turn after its input ended", e);
			}
			finally
			{
				// Ends the turn, so that a test that failed does not leave a thread waiting
				process.interrupt();
			}

			requireThat(process.isAlive(), "alive").isFalse();
		}
	}

	/**
	 * Verifies a client can steer and interrupt an active turn before asking the bridge to complete the session. The
	 * client sends its controls once the harness has printed the turn's first line.
	 *
	 * @throws IOException if the bridge protocol fails
	 */
	@Test(timeOut = 10_000)
	public void acceptsActiveTurnControls() throws IOException
	{
		List<String> received = new CopyOnWriteArrayList<>();
		CountDownLatch printed = new CountDownLatch(1);
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.blocksUntilInterrupted("harness line").
				announcing(printed)), received);
			Reader input = new GatedReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n", printed,
				"{\"type\":\"steer\",\"prompt\":\"change course\"}\n{\"type\":\"interrupt\"}\n",
				new CountDownLatch(1), "{\"type\":\"complete\"}\n"))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			StringWriter output = new StringWriter();

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), input,
				output);

			requireThat(received, "received").isEqualTo(List.of("first", "change course"));
			requireThat(output.toString(), "output").contains("\"type\":\"steered\"").
				contains("\"type\":\"interrupted\"").contains("\"type\":\"turn_completed\"");
		}
	}

	/**
	 * Verifies that an interrupt ends only the turn in progress when the harness fails the interrupted turn, as Claude
	 * Code does: the bridge reports the turn's end, keeps the harness and serves the client's next turn.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test(timeOut = 10_000)
	public void servesTurnAfterInterruptedTurnFails() throws IOException
	{
		List<String> received = new CopyOnWriteArrayList<>();
		CountDownLatch printed = new CountDownLatch(1);
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.blocksUntilInterrupted("harness line").
				announcing(printed), Turn.replies("two")), received, InterruptedTurn.FAILS);
			Reader input = new GatedReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n", printed,
				"{\"type\":\"interrupt\"}\n", new CountDownLatch(1),
				"{\"type\":\"turn\",\"prompt\":\"second\"}\n{\"type\":\"complete\"}\n"))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			StringWriter output = new StringWriter();

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), input,
				output);

			requireThat(received, "received").isEqualTo(List.of("first", "second"));
			requireThat(output.toString(), "output").contains("\"type\":\"interrupted\"").
				contains("\"type\":\"turn_failed\"").contains("\"assistant_response\":\"two\"").
				contains("\"type\":\"completed\"");
		}
	}

	/**
	 * Verifies that an interrupt that the bridge handles after it started a turn but before the turn's thread sent the
	 * turn to the harness stops that turn once the harness receives it. A session sends a turn while it holds the
	 * process's monitor, so the test holds that monitor until the bridge has handled the interrupt, which keeps the
	 * turn's thread from sending the turn until then. The bridge runs on its own thread so that a bridge that waits for
	 * a turn that was never stopped fails the test instead of hanging it.
	 *
	 * @throws IOException          if filesystem setup fails
	 * @throws InterruptedException if the test is interrupted while it waits
	 */
	@Test(timeOut = 30_000)
	public void stopsTurnInterruptedBeforeSending() throws IOException, InterruptedException
	{
		CountDownLatch interruptHandled = new CountDownLatch(1);
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.blocksUntilInterrupted()), new ArrayList<>());
			Reader input = new GatedReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n", new CountDownLatch(0),
				"{\"type\":\"interrupt\"}\n", interruptHandled, "{\"type\":\"complete\"}\n"))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			StringWriter output = new StringWriter();
			CompletableFuture<HarnessRun> serving = new CompletableFuture<>();
			synchronized (process)
			{
				Thread.ofPlatform().daemon().name("bridge-under-test").start(() ->
				{
					try
					{
						serving.complete(HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process,
							new TestHarnessRunnerScope()), input, output));
					}
					catch (IOException | RuntimeException e)
					{
						serving.completeExceptionally(e);
					}
				});
				requireThat(interruptHandled.await(5, TimeUnit.SECONDS), "interruptHandled").isTrue();
			}
			try
			{
				serving.get(5, TimeUnit.SECONDS);
			}
			catch (ExecutionException e)
			{
				throw new AssertionError(e.getCause());
			}
			catch (TimeoutException e)
			{
				throw new AssertionError("The interrupt did not stop the turn that had not yet reached the harness", e);
			}
			finally
			{
				// Ends the turn, so that a test that failed does not leave a thread waiting
				process.interrupt();
			}

			requireThat(output.toString(), "output").contains("\"type\":\"interrupted\"").
				contains("\"type\":\"turn_completed\"").contains("\"type\":\"completed\"");
		}
	}

	/**
	 * Verifies an interrupt that reaches the bridge after its turn ended is ignored and the bridge keeps serving later
	 * requests. The bridge's turn thread is held right after it writes {@code turn_completed}
	 * until the bridge has handled the interrupt, so the interrupt reaches the bridge while the thread that ran the
	 * turn has not yet returned.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test(timeOut = 10_000)
	public void ignoresInterruptAfterTurnEnded() throws IOException
	{
		// Requirement: R-E10, R-E15
		CountDownLatch firstTurnCompleted = new CountDownLatch(1);
		CountDownLatch interruptHandled = new CountDownLatch(1);
		List<String> received = new CopyOnWriteArrayList<>();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("one"), Turn.replies("two")), received);
			Reader input = new GatedReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n", firstTurnCompleted,
				"{\"type\":\"interrupt\"}\n", interruptHandled,
				"{\"type\":\"turn\",\"prompt\":\"second\"}\n{\"type\":\"complete\"}\n");
			Writer output = new SignalingWriter("\"type\":\"turn_completed\"", firstTurnCompleted, interruptHandled))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), input,
				output);

			requireThat(received, "received").isEqualTo(List.of("first", "second"));
			requireThat(output.toString(), "output").doesNotContain("\"type\":\"interrupted\"").
				contains("\"type\":\"completed\"");
		}
	}

	/**
	 * Verifies that while a turn writes no record for 30 seconds, the bridge writes a {@code waiting} record and
	 * repeats it every 30 seconds; that any other record of the turn restarts that count; and that no {@code waiting}
	 * record is written before a turn, after {@code turn_completed}, or between turns. The test moves the clock and runs
	 * the heartbeat itself.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test(timeOut = 10_000)
	public void reportsQuietTurnEveryThirtySeconds() throws IOException
	{
		// Requirement: R-E14
		try (TestHarnessRunnerScope scope = new TestHarnessRunnerScope())
		{
			verifyQuietTurnReports(scope);
		}
	}

	/**
	 * Runs a quiet turn whose clock and heartbeat the scope supplies, and verifies the bridge's records.
	 *
	 * @param scope the scope of the run's process, whose clock and heartbeat the test moves
	 * @throws IOException if the bridge fails
	 */
	private static void verifyQuietTurnReports(TestHarnessRunnerScope scope) throws IOException
	{
		ManualClock clock = scope.clock();
		ManualTicker ticker = scope.heartbeatTicker();
		CountDownLatch turnCompleted = new CountDownLatch(1);
		Runnable quietBetweenTurns = () ->
		{
			clock.advance(Duration.ofSeconds(90));
			ticker.tick();
			ticker.tick();
		};
		// The turn stays quiet while the test moves the clock and runs the heartbeat, prints one harness event, stays
		// quiet again and then completes
		Turn.Checkpoint quietTurn = printed ->
		{
			if (printed == 0)
			{
				quiet(clock, ticker, 29);
				quiet(clock, ticker, 1);
			}
			quiet(clock, ticker, 29);
			quiet(clock, ticker, 1);
		};
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("done", "harness line").
				withCheckpoint(quietTurn)), new ArrayList<>());
			Reader input = new QuietReader(quietBetweenTurns, "{\"type\":\"turn\",\"prompt\":\"first\"}\n",
				turnCompleted, "{\"type\":\"complete\"}\n");
			Writer output = new SignalingWriter("\"type\":\"turn_completed\"", turnCompleted, new CountDownLatch(0)))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, scope), null, input, output);

			JsonMapper json = JsonMapper.builder().build();
			List<String> records = new ArrayList<>();
			for (String line : output.toString().lines().toList())
			{
				JsonNode record = json.readTree(line);
				String type = record.path("type").asString();
				if (type.equals("waiting"))
					records.add(type + " " + record.path("quiet_seconds").asLong());
				else
					records.add(type);
			}
			requireThat(records, "records").isEqualTo(List.of("started", "waiting 30", "waiting 60", "harness_event",
				"session_id", "waiting 30", "turn_completed", "completed"));
		}
	}

	/**
	 * Moves the clock forward and runs the heartbeat.
	 *
	 * @param clock   the bridge's clock
	 * @param ticker  the bridge's heartbeat
	 * @param seconds how far to move the clock
	 */
	private static void quiet(ManualClock clock, ManualTicker ticker, int seconds)
	{
		clock.advance(Duration.ofSeconds(seconds));
		ticker.tick();
	}

	/**
	 * Creates the folder of a conversation, whose messages the asking session wrote before the bridge starts.
	 *
	 * @param cleanup  removes the folder
	 * @param messages the lines that the asking session appended to {@code to-child.jsonl}
	 * @return the conversation's folder
	 * @throws IOException if the folder cannot be created
	 */
	private static Path askingFolder(TemporaryDirectories cleanup, String messages) throws IOException
	{
		Path folder = Files.createDirectory(cleanup.add(Files.createTempDirectory("cat-asking-")).
			resolve("conversation"));
		Files.writeString(folder.resolve("to-child.jsonl"), messages);
		return folder;
	}

	/**
	 * Serves a conversation in this process, as a runner that a request server or a run's runner starts does.
	 *
	 * @param cleanup       removes the run that the bridge creates
	 * @param folder        the conversation's folder
	 * @param process       the harness
	 * @param standardInput the bridge's standard input, which a conversation does not read
	 * @throws IOException if the bridge fails
	 */
	private static void serveConversation(TemporaryDirectories cleanup, Path folder, ScriptedProcess process,
		StringReader standardInput) throws IOException
	{
		Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
		HarnessRunManager manager = new HarnessRunManager(new FileLocks(), root, Clock.systemUTC(),
			Yielding.fileReplacer());
		HarnessRun run = manager.create("harness");
		Files.createDirectories(run.directory().resolve("project"));
		HarnessRunnerOptions options = HarnessRunnerOptions.parse(new String[]{"--artifact",
			root.resolve("artifact").toString(), "--results", root.toString(), "--model", "model-a",
			"--effort", "effort-a", "--conversation", folder.toString()}, "harness", HarnessTemporaryRoot.of(root));
		HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
			options, JsonMapper.builder().build(), Yielding.fileReplacer(), standardInput, new StringWriter());
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
	 * Client input that runs an action before its first request and again once the turn has completed, before it sends
	 * its last request.
	 */
	private static final class QuietReader extends Reader
	{
		private final Runnable quiet;
		private final StringReader turn;
		private final CountDownLatch turnCompleted;
		private final StringReader rest;
		private boolean started;
		private boolean turnExhausted;

		/**
		 * Creates the input.
		 *
		 * @param quiet         runs before the first request and once {@code turnCompleted} opens
		 * @param turn          the turn request
		 * @param turnCompleted opens once the bridge has written {@code turn_completed}
		 * @param rest          the requests sent after the turn completed
		 */
		private QuietReader(Runnable quiet, String turn, CountDownLatch turnCompleted, String rest)
		{
			this.quiet = quiet;
			this.turn = new StringReader(turn);
			this.turnCompleted = turnCompleted;
			this.rest = new StringReader(rest);
		}

		@Override
		public int read(char[] buffer, int offset, int length) throws IOException
		{
			if (!started)
			{
				started = true;
				quiet.run();
			}
			if (!turnExhausted)
			{
				int count = turn.read(buffer, offset, length);
				if (count != -1)
					return count;
				turnExhausted = true;
				try
				{
					turnCompleted.await();
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting for the turn to complete", e);
				}
				quiet.run();
			}
			return rest.read(buffer, offset, length);
		}

		@Override
		public void close()
		{
			turn.close();
			rest.close();
		}
	}

	/**
	 * Client input that sends a request only after an earlier bridge response has been written, and reports when the
	 * bridge has handled that request.
	 */
	private static final class GatedReader extends Reader
	{
		private final StringReader first;
		private final CountDownLatch gate;
		private final StringReader gated;
		private final CountDownLatch gatedHandled;
		private final StringReader rest;
		private boolean firstExhausted;
		private boolean gatedExhausted;

		/**
		 * Creates input that serves {@code first}, waits for {@code gate}, serves {@code gated}, then opens
		 * {@code gatedHandled} and serves {@code rest}.
		 *
		 * @param first the requests available immediately
		 * @param gate opens once the bridge has written the response that {@code gated} must follow
		 * @param gated one request line sent after {@code gate} opens
		 * @param gatedHandled opened when the bridge asks for input after {@code gated}, which it does only once it
		 *   has handled that request
		 * @param rest the requests after {@code gated}
		 */
		private GatedReader(String first, CountDownLatch gate, String gated, CountDownLatch gatedHandled, String rest)
		{
			this.first = new StringReader(first);
			this.gate = gate;
			this.gated = new StringReader(gated);
			this.gatedHandled = gatedHandled;
			this.rest = new StringReader(rest);
		}

		@Override
		public int read(char[] buffer, int offset, int length) throws IOException
		{
			if (!firstExhausted)
			{
				int count = first.read(buffer, offset, length);
				if (count != -1)
					return count;
				firstExhausted = true;
			}
			if (!gatedExhausted)
			{
				try
				{
					gate.await();
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting for the gated bridge input", e);
				}
				int count = gated.read(buffer, offset, length);
				if (count != -1)
					return count;
				gatedExhausted = true;
			}
			gatedHandled.countDown();
			return rest.read(buffer, offset, length);
		}

		@Override
		public void close()
		{
			first.close();
			gated.close();
			rest.close();
		}
	}

	/**
	 * Bridge output that opens a latch once a response containing a marker has been written, then holds the writing
	 * thread until another latch opens.
	 */
	private static final class SignalingWriter extends StringWriter
	{
		private final String marker;
		private final CountDownLatch written;
		private final CountDownLatch release;

		/**
		 * Creates output that signals {@code written} after the first response containing {@code marker}, and holds
		 * the thread that wrote it until {@code release} opens.
		 *
		 * @param marker the text that identifies the awaited response
		 * @param written opened when that response is flushed
		 * @param release opens when the thread that wrote the response may continue
		 */
		private SignalingWriter(String marker, CountDownLatch written, CountDownLatch release)
		{
			this.marker = marker;
			this.written = written;
			this.release = release;
		}

		@Override
		public void flush()
		{
			super.flush();
			if (written.getCount() == 0 || !toString().contains(marker))
				return;
			written.countDown();
			try
			{
				release.await();
			}
			catch (InterruptedException _)
			{
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Input that supplies one request and then fails, or ends, once a turn has started.
	 */
	private static final class FailingReader extends Reader
	{
		private final StringReader request;
		private final CountDownLatch turnStarted;
		private final boolean fails;

		/**
		 * Creates the input.
		 *
		 * @param request the request text to supply first
		 * @param turnStarted opened when the turn that the request starts is running
		 */
		private FailingReader(String request, CountDownLatch turnStarted)
		{
			this(request, turnStarted, true);
		}

		/**
		 * Creates the input.
		 *
		 * @param request     the request text to supply first
		 * @param turnStarted opened when the turn that the request starts is running
		 * @param fails       {@code true} to fail once the turn runs, {@code false} to end
		 */
		private FailingReader(String request, CountDownLatch turnStarted, boolean fails)
		{
			this.request = new StringReader(request);
			this.turnStarted = turnStarted;
			this.fails = fails;
		}

		/**
		 * Returns input that ends, instead of failing, once the turn that its request starts is running.
		 *
		 * @param request     the request text to supply first
		 * @param turnStarted opened when the turn that the request starts is running
		 * @return the input
		 */
		private static FailingReader ending(String request, CountDownLatch turnStarted)
		{
			return new FailingReader(request, turnStarted, false);
		}

		@Override
		public int read(char[] buffer, int offset, int length) throws IOException
		{
			int count = request.read(buffer, offset, length);
			if (count >= 0)
				return count;
			try
			{
				turnStarted.await();
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for the turn to start", e);
			}
			if (!fails)
				return -1;
			throw new IOException("input failed");
		}

		@Override
		public void close()
		{
			request.close();
		}
	}

	/**
	 * The clock of a file replacer, which holds the first replacement that a thread other than the bridge's makes once
	 * it is armed. The bridge's turn makes that replacement once it has started. The replacement waits until the bridge
	 * has interrupted the turn and the bridge's thread then waits, or until the bridge has returned.
	 */
	private static final class TurnWriteGate extends Clock
	{
		private final Thread bridge;
		private final CountDownLatch held = new CountDownLatch(1);
		private final AtomicReference<Hold> hold = new AtomicReference<>(Hold.UNARMED);

		/**
		 * Creates the clock.
		 *
		 * @param bridge the thread that runs the bridge
		 */
		private TurnWriteGate(Thread bridge)
		{
			this.bridge = bridge;
		}

		/**
		 * Makes the next replacement by another thread wait.
		 */
		private void arm()
		{
			hold.set(Hold.ARMED);
		}

		/**
		 * Returns the latch that opens once a replacement waits.
		 *
		 * @return the latch
		 */
		private CountDownLatch held()
		{
			return held;
		}

		/**
		 * Records that the bridge has returned, and lets the held replacement go ahead.
		 *
		 * @return {@code true} if the replacement was still held when the bridge returned
		 */
		private boolean returned()
		{
			return hold.compareAndSet(Hold.HELD, Hold.AFTER_RETURN);
		}

		@Override
		public ZoneId getZone()
		{
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public Instant instant()
		{
			if (Thread.currentThread() != bridge && hold.compareAndSet(Hold.ARMED, Hold.HELD))
				hold();
			return Instant.now();
		}

		/**
		 * Holds the calling thread until the bridge has interrupted it and then waits, or the bridge has returned.
		 */
		private void hold()
		{
			held.countDown();
			boolean interrupted = false;
			while (hold.get() == Hold.HELD)
			{
				if (Thread.interrupted())
					interrupted = true;
				Thread.State state = bridge.getState();
				if (interrupted && (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING))
					hold.compareAndSet(Hold.HELD, Hold.BEFORE_RETURN);
				Thread.onSpinWait();
			}
			if (interrupted)
				Thread.currentThread().interrupt();
		}

		/**
		 * The progress of the held replacement.
		 */
		private enum Hold
		{
			/**
			 * No replacement is held.
			 */
			UNARMED,
			/**
			 * The next replacement by another thread is held.
			 */
			ARMED,
			/**
			 * A replacement is held.
			 */
			HELD,
			/**
			 * The replacement went ahead while the bridge waited, before it returned.
			 */
			BEFORE_RETURN,
			/**
			 * The replacement went ahead only once the bridge had returned.
			 */
			AFTER_RETURN
		}
	}
}
