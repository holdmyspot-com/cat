package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.StandardOpenOption.APPEND;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.KeptRunCopy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;

/**
 * Tests the turn-evidence and final-run copy boundaries.
 */
public final class KeptRunCopyTest
{
	/**
	 * Creates the test fixture.
	 */
	public KeptRunCopyTest()
	{
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
				KeptRunCopy.copyTurn(source, target);
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
				KeptRunCopy.copyTurn(source, target);
				requireThat(Files.readString(target.resolve("artifacts/session-transcripts/rollout-root.jsonl")),
					"refreshedRoot").isEqualTo("root-two\n");
				requireThat(Files.exists(target.resolve("artifacts/session-transcripts/subagents/rollout-child.jsonl")),
					"removedChild").isFalse();
			}
		}
	}

	/**
	 * Verifies that cleanup removes only turn copies and permits the subsequent full kept-run copy.
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
				KeptRunCopy.deleteTurnCopies(target);
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

				KeptRunCopy.Result result = KeptRunCopy.copy(source, target);
				requireThat(result.incomplete(), "fullCopy").isEmpty();
				requireThat(Files.readString(target.resolve("project/marker")), "finalProject").
					isEqualTo("final-project");
				requireThat(Files.readString(target.resolve("artifacts/result.jsonl")), "finalArtifact").
					isEqualTo("result\n");
			}
		}
	}
}
