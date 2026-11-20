package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.StandardOpenOption.APPEND;

import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.runner.NestedSession;
import com.holdmyspot.cat.common.runner.NestedSessionManager;
import com.holdmyspot.cat.common.runner.ResultsCopy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.annotations.Test;

/**
 * Tests the turn-evidence and final results copy boundaries.
 */
public final class ResultsCopyTest
{
	/**
	 * Verifies that normal results exclude the runner's private recovery snapshot and its staging directory.
	 *
	 * @throws IOException if fixture files cannot be created or copied
	 */
	@Test
	public void excludesPrivateTrialSnapshot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("kept-private-copy-"));
			Path source = Files.createDirectory(root.resolve("source"));
			Path target = root.resolve("target");
			Files.writeString(Files.createDirectory(source.resolve("project")).resolve("file"), "results");
			Files.writeString(Files.createDirectory(source.resolve("retained-turn")).resolve("snapshot.json"), "{}");
			Files.createDirectory(source.resolve(".retained-turn-in-progress"));

			ResultsCopy.Result copied = ResultsCopy.copy(source, target);

			requireThat(copied.incomplete(), "incomplete").isEmpty();
			requireThat(Files.readString(target.resolve("project/file")), "project").isEqualTo("results");
			requireThat(Files.exists(target.resolve("retained-turn")), "retainedSnapshot").isFalse();
			requireThat(Files.exists(target.resolve(".retained-turn-in-progress")), "retainedStaging").isFalse();
		}
	}

	/**
	 * Creates the test fixture.
	 */
	public ResultsCopyTest()
	{
	}

	/**
	 * Verifies that final copies leave private slots with the runner and publish the requested sessions' evidence.
	 *
	 * @throws IOException if fixture files cannot be created or copied
	 */
	@Test
	public void resultsExcludePrivateSlots() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "private-slots-copy-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root.resolve("runtime"),
				Clock.systemUTC(), Yielding.fileReplacer()).withFixedSlotId("s-1");
			NestedSession session = manager.create("harness");
			Path source = session.directory();
			Path project = source.resolve("project");
			Files.writeString(Files.createDirectories(project.resolve("slots")).resolve("marker"), "project file");
			Files.writeString(Files.createDirectories(source.resolve("slots/allowed/1")).resolve("marker"), "grant");
			Files.writeString(Files.createDirectories(source.resolve("slots/d1/codex/s-1/home")).resolve("login"),
				"private file");
			Path response = Files.createDirectories(source.resolve("responses/request-id"));
			Files.writeString(response.resolve("response.json"), "response");
			Files.writeString(Files.createDirectories(response.resolve("results-copy/artifacts")).resolve("receipt.json"),
				"requested session evidence");
			manager.markCompleted(session);

			Path brokerCopy = root.resolve("broker-copy");
			Path requesterCopy = root.resolve("requester-copy");
			requireThat(ResultsCopy.copy(source, brokerCopy).incomplete(), "brokerCopy").isEmpty();
			requireThat(ResultsCopy.copy(source, requesterCopy, "kept-1", project).incomplete(), "requesterCopy").
				isEmpty();
			for (Path copy : List.of(brokerCopy, requesterCopy))
			{
				requireThat(Files.exists(copy.resolve("slots")), "privateSlots").isFalse();
				requireThat(Files.readString(copy.resolve("project/slots/marker")), "projectFile").isEqualTo("project file");
				requireThat(Files.readString(copy.resolve("responses/request-id/response.json")), "response").
					isEqualTo("response");
				Path receipt = copy.resolve("responses/request-id/results-copy/artifacts/receipt.json");
				requireThat(Files.readString(receipt), "receipt").isEqualTo("requested session evidence");
			}
			Path privateSlots = source.resolve("slots");
			if (PortableFiles.hasModes(privateSlots))
			{
				Set<java.nio.file.attribute.PosixFilePermission> permissions =
					Files.getPosixFilePermissions(privateSlots);
				try
				{
					denyReading(privateSlots);
					ResultsCopy.Result protectedCopy = ResultsCopy.copy(source, root.resolve("protected-copy"));
					requireThat(protectedCopy.incomplete(), "protectedCopy").isEmpty();
				}
				finally
				{
					Files.setPosixFilePermissions(privateSlots, permissions);
				}
			}
		}
	}

	/**
	 * Verifies that turn copies replace only project and transcript evidence while an unrelated transcript stays open.
	 *
	 * @throws IOException if fixture files cannot be created or copied
	 */
	@Test
	public void copiesOnlyTurnEvidenceAndProjects() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("kept-turn-copy-"));
			Path source = Files.createDirectories(root.resolve("source"));
			Path target = Files.createDirectories(root.resolve("target"));
			Files.writeString(Files.createDirectories(source.resolve("project")).resolve("marker"), "project-one");
			Files.writeString(Files.createDirectories(source.resolve("initial-project")).resolve("marker"), "initial-one");
			Files.writeString(Files.createDirectories(source.resolve("home")).resolve("login"), "secret");
			Path sourceTranscripts = Files.createDirectories(source.resolve("artifacts/session-transcripts/subagents"));
			Files.writeString(sourceTranscripts.getParent().resolve("rollout-root.jsonl"), "root-one\n");
			Files.writeString(sourceTranscripts.resolve("rollout-child.jsonl"), "child-one\n");
				Files.writeString(Files.createDirectories(source.resolve("artifacts")).
					resolve("transcript.jsonl"), "source-cat-transcript\n");
			Files.writeString(source.resolve("artifacts/receipt.json"), "receipt");
			Files.writeString(Files.createDirectories(target.resolve("project")).resolve("stale"), "stale");
			Files.writeString(Files.createDirectories(target.resolve("initial-project")).resolve("stale"), "stale");
				Files.writeString(Files.createDirectories(target.resolve("artifacts/session-transcripts")).
					resolve("stale.jsonl"), "stale");
			Files.writeString(target.resolve("artifacts/transcript.jsonl"), "open-cat-transcript");
			Files.writeString(target.resolve("artifacts/unrelated.json"), "unrelated");
			Files.writeString(Files.createDirectories(target.resolve("home")).resolve("login"), "target-secret");
			try (var openTranscript = Files.newBufferedWriter(target.resolve("artifacts/transcript.jsonl"), APPEND))
			{
				ResultsCopy.copyTurn(source, target);
				openTranscript.write("-still-open");
				openTranscript.flush();

				requireThat(Files.readString(target.resolve("project/marker")), "project").isEqualTo("project-one");
				requireThat(Files.readString(target.resolve("initial-project/marker")), "initialProject").
					isEqualTo("initial-one");
				requireThat(Files.readString(target.resolve("artifacts/session-transcripts/rollout-root.jsonl")),
					"rootTranscript").isEqualTo("root-one\n");
				requireThat(Files.readString(target.resolve("artifacts/session-transcripts/subagents/rollout-child.jsonl")),
					"childTranscript").isEqualTo("child-one\n");
				requireThat(Files.exists(target.resolve("project/stale")), "staleProject").isFalse();
				requireThat(Files.exists(target.resolve("artifacts/session-transcripts/stale.jsonl")), "staleTranscript").
					isFalse();
				requireThat(Files.readString(target.resolve("artifacts/transcript.jsonl")), "catTranscript").
					isEqualTo("open-cat-transcript-still-open");
				requireThat(Files.readString(target.resolve("artifacts/unrelated.json")), "unrelatedArtifact").
				isEqualTo("unrelated");
				requireThat(Files.readString(target.resolve("home/login")), "targetHome").isEqualTo("target-secret");

				Files.delete(sourceTranscripts.resolve("rollout-child.jsonl"));
				Files.writeString(sourceTranscripts.getParent().resolve("rollout-root.jsonl"), "root-two\n");
				ResultsCopy.copyTurn(source, target);
				requireThat(Files.readString(target.resolve("artifacts/session-transcripts/rollout-root.jsonl")),
					"refreshedRoot").isEqualTo("root-two\n");
				requireThat(Files.exists(target.resolve("artifacts/session-transcripts/subagents/rollout-child.jsonl")),
					"removedChild").isFalse();
			}
		}
	}

	/**
	 * Verifies that cleanup removes only turn copies and permits the subsequent full results copy.
	 *
	 * @throws IOException if fixture files cannot be created or copied
	 */
	@Test
	public void deletesOnlyTurnCopies() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("kept-turn-delete-"));
			Path source = Files.createDirectories(root.resolve("source"));
			Path target = Files.createDirectories(root.resolve("target"));
			Files.writeString(Files.createDirectories(source.resolve("project")).resolve("marker"), "final-project");
			Files.writeString(Files.createDirectories(source.resolve("initial-project")).resolve("marker"),
				"final-initial");
			Files.writeString(Files.createDirectories(source.resolve("artifacts")).resolve("result.jsonl"), "result\n");
			Files.writeString(Files.createDirectories(target.resolve("project")).resolve("marker"), "turn-project");
			Files.writeString(Files.createDirectories(target.resolve("initial-project")).resolve("marker"),
				"turn-initial");
				Files.writeString(Files.createDirectories(target.resolve("artifacts/session-transcripts")).
					resolve("rollout.jsonl"), "turn-transcript");
			Files.writeString(target.resolve("artifacts/transcript.jsonl"), "cat-open");
			Files.writeString(target.resolve("artifacts/unrelated.json"), "unrelated");
			try (var openTranscript = Files.newBufferedWriter(target.resolve("artifacts/transcript.jsonl"), APPEND))
			{
				ResultsCopy.deleteTurnCopies(target);
				openTranscript.write("-continued");
				openTranscript.flush();

				requireThat(Files.exists(target.resolve("project")), "projectRemoved").isFalse();
				requireThat(Files.exists(target.resolve("initial-project")), "initialRemoved").isFalse();
				requireThat(Files.exists(target.resolve("artifacts/session-transcripts")), "transcriptsRemoved").
					isFalse();
				requireThat(Files.isDirectory(target.resolve("artifacts")), "artifactsKept").isTrue();
				requireThat(Files.readString(target.resolve("artifacts/transcript.jsonl")), "catTranscript").
					isEqualTo("cat-open-continued");
				requireThat(Files.readString(target.resolve("artifacts/unrelated.json")), "unrelatedArtifact").
				isEqualTo("unrelated");

				ResultsCopy.Result result = ResultsCopy.copy(source, target);
				requireThat(result.incomplete(), "fullCopy").isEmpty();
				requireThat(Files.readString(target.resolve("project/marker")), "finalProject").
					isEqualTo("final-project");
				requireThat(Files.readString(target.resolve("artifacts/result.jsonl")), "finalArtifact").
					isEqualTo("result\n");
			}
		}
	}

	/**
	 * Verifies that a file of a session folder that cannot be read does not end the copy: the rest of the nested session,
	 * its project included, is copied, and the result names every file that could not be copied with the failure that
	 * stopped it.
	 *
	 * @throws IOException if fixture files cannot be created, protected or copied
	 */
	@Test
	public void unreadableFilesLeaveTheRestCopied() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("kept-unreadable-"));
			Path source = Files.createDirectories(root.resolve("source"));
			Path target = root.resolve("target");
			Files.writeString(Files.createDirectories(source.resolve("project")).resolve("marker"), "kept project");
			Files.writeString(Files.createDirectories(source.resolve("artifacts")).resolve("turn-1.events"), "events");
			Path evidence = Files.createDirectories(source.resolve("responses/request-1/inputs/read_only_evidence/1"));
			Path firstUnreadable = Files.writeString(evidence.resolve("bridge-transcript.jsonl"), "transcript");
			Path secondUnreadable = Files.writeString(source.resolve("artifacts/receipt.json"), "receipt");
			denyReading(firstUnreadable);
			denyReading(secondUnreadable);

			ResultsCopy.Result result = ResultsCopy.copy(source, target);

			requireThat(Files.readString(target.resolve("project/marker")), "project").isEqualTo("kept project");
			requireThat(Files.readString(target.resolve("artifacts/turn-1.events")), "events").isEqualTo("events");
			Optional<String> incomplete = result.incomplete();
			requireThat(incomplete, "incomplete").isPresent();
			requireThat(incomplete.get(), "incomplete").
				contains(target.resolve("responses/request-1/inputs/read_only_evidence/1/bridge-transcript.jsonl").
					toString()).
				contains(target.resolve("artifacts/receipt.json").toString()).
				contains("AccessDeniedException").
				contains("The results copy at " + target + " is incomplete.");
		}
	}

	/**
	 * Verifies that a session's pass over the nested sessions of its results folder never meets another session's results
	 * copy while that run is being copied, when a manifest that does not yet identify its directory, or is partly copied,
	 * would make the pass fail the session.
	 * <p>
	 * The case writes 800 real files into a session folder and copies it six times while a pruning thread runs against
	 * the kept results folder, which takes over a second. The test keeps that boundary because it asserts the real copy
	 * races.
	 *
	 * @throws IOException if fixture files cannot be created or copied
	 * @throws InterruptedException if the test thread is interrupted
	 */
	@Test
	public void copyNeverShowsAnotherIdentity() throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path slots = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "kept-slot-"));
			NestedSessionManager slot = new NestedSessionManager(jvm.fileLocks(), slots, Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedSlotId("s-1");
			NestedSession child = slot.create("harness");
			for (int i = 0; i < 800; ++i)
				Files.writeString(Files.createDirectories(child.directory().resolve("project/d" + (i % 40))).
					resolve("f" + i), "content");
			slot.markCompleted(child);
			Path kept = Files.createTempDirectory(jvm.temporaryDirectory(), "kept-root-");
			cleanup.add(kept);
			NestedSessionManager resultsCopies = new NestedSessionManager(jvm.fileLocks(), kept, Clock.systemUTC(),
				Yielding.fileReplacer());
			AtomicBoolean done = new AtomicBoolean();
			AtomicReference<IOException> seen = new AtomicReference<>();
			Thread pass = new Thread(() ->
			{
				while (!done.get())
				{
					try
					{
						resultsCopies.pruneTerminalRuntimes("harness");
					}
					catch (IOException e)
					{
						seen.compareAndSet(null, e);
						return;
					}
				}
			});
			pass.start();
			try
			{
				for (int round = 0; round < 6 && seen.get() == null; ++round)
				{
					String id = "kept-" + round;
					ResultsCopy.copy(child.directory(), kept.resolve("harness").resolve(id), id,
						child.directory().resolve("project"));
				}
			}
			finally
			{
				done.set(true);
				pass.join();
			}
			requireThat(seen.get(), "failureOfPass").isNull();
		}
	}

	/**
	 * Takes away the permission to read a file. A file system without POSIX permissions gets an access-control entry
	 * that denies the current user the reading instead. Either way, the file can still be deleted.
	 *
	 * @param file a file
	 * @throws IOException if the file's permissions cannot be changed
	 */
	private static void denyReading(Path file) throws IOException
	{
		if (PortableFiles.hasModes(file))
		{
			Files.setPosixFilePermissions(file, Set.of());
			return;
		}
		// The test created the file, so its owner is the user that runs the test
		UserPrincipal user = Files.getOwner(file);
		AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
		List<AclEntry> entries = new ArrayList<>(view.getAcl());
		entries.addFirst(AclEntry.newBuilder().
			setType(AclEntryType.DENY).
			setPrincipal(user).
			setPermissions(AclEntryPermission.READ_DATA).
			build());
		view.setAcl(entries);
	}
}
