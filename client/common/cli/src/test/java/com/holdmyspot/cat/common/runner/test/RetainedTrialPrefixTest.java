package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.RetainedTrialPrefix;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests the evidence a claimed retained trial supplies to its recovering caller.
 */
public final class RetainedTrialPrefixTest
{
	private static final String CHECKPOINT = "turn-checkpoint.json";
	private static final String PROJECT_FILE = "project/observed.txt";
	private static final String INITIAL_FILE = "initial-project/original.txt";
	private static final String NATIVE_ROOT = "thread.jsonl";
	private static final String TRANSCRIPT_FILE = "artifacts/session-transcripts/" + NATIVE_ROOT;
	private static final String REPLY = """
		{"type":"turn_completed","exit_code":0,"error":"","assistant_response":"reply %d","assistant_messages":[]}
		""";

	/**
	 * Creates the test.
	 */
	public RetainedTrialPrefixTest()
	{
	}

	/**
	 * Export replays every completed turn and uses project and transcript files from the last turn's private snapshot.
	 *
	 * @throws IOException if fixture or prefix files cannot be accessed
	 */
	@Test
	public void exportsOnlyTheCompletedPrefix() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("retained-trial-prefix"));
			Path session = Files.createDirectory(root.resolve("session"));
			Path response = Files.createDirectory(root.resolve("response"));
			createSessionFiles(session);
			for (int turn = 1; turn <= 2; ++turn)
			{
				Files.writeString(session.resolve(PROJECT_FILE), "observed at turn " + turn);
				Files.writeString(session.resolve(TRANSCRIPT_FILE), "native at turn " + turn + "\n");
				writeTurn(session, turn);
				Path snapshot = RetainedTrialPrefix.capture(session, turn, Optional.of(NATIVE_ROOT));
				requireThat(snapshot, "snapshot").isEqualTo(session.resolve("retained-turn"), "privateTurnCopy");
			}
			Files.writeString(session.resolve(PROJECT_FILE), "change after observer");
			Files.writeString(session.resolve(TRANSCRIPT_FILE), "native after observer\n");
			writeTurn(session, 3);
			writeCheckpoint(session, 2, "ready");

			Path exported = RetainedTrialPrefix.publish(session, response, 2);
			RetainedTrialPrefix prefix = RetainedTrialPrefix.open(response, 2);
			requireThat(prefix.directory(), "prefixDirectory").isEqualTo(exported, "exported");
			requireThat(exported, "exported").isEqualTo(response.resolve("retained-prefix"), "responseFolder");
			requireThat(prefix.completedTurns(), "completedTurns").isEqualTo(2);
			requireThat(Files.readString(exported.resolve(PROJECT_FILE)), "project").isEqualTo("observed at turn 2");
			requireThat(Files.readString(exported.resolve(INITIAL_FILE)), "initialProject").isEqualTo("initial");
			requireThat(Files.readString(exported.resolve(TRANSCRIPT_FILE)), "transcript").isEqualTo("native at turn 2\n");
			requireThat(Files.readString(exported.resolve(CHECKPOINT)), "checkpoint").
				isEqualTo(Files.readString(session.resolve(CHECKPOINT)), "sourceCheckpoint");
			for (String path : new String[]{"home", "requests", "artifacts/private.dat", "artifacts/turn-3.events",
				"artifacts/turn-3.reply.json"})
			{
				requireThat(Files.exists(exported.resolve(path)), path).isFalse();
			}
			for (int turn = 1; turn <= prefix.completedTurns(); ++turn)
			{
				try (BufferedReader events = prefix.openEvents(turn);
					InputStream reply = prefix.openReply(turn))
				{
					requireThat(events.readLine(), "event").isEqualTo("event " + turn);
					requireThat(events.readLine(), "endOfEvents").isNull();
					requireThat(new String(reply.readAllBytes(), StandardCharsets.UTF_8), "reply").
						isEqualTo(REPLY.formatted(turn), "originalReply");
				}
			}
		}
	}

	/**
	 * A launch interrupted before its first turn publishes a zero checkpoint without a project snapshot.
	 *
	 * @throws IOException if fixture or prefix files cannot be accessed
	 */
	@Test
	public void publishesAnEmptyPrefix() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("retained-trial-prefix"));
			Path session = Files.createDirectory(root.resolve("session"));
			Path response = Files.createDirectory(root.resolve("response"));
			writeCheckpoint(session, 0, "ready");
			requireThat(RetainedTrialPrefix.readReadyTurns(session).orElse(-1), "checkpoint").isEqualTo(0);
			Path exported = RetainedTrialPrefix.publish(session, response, 0);

			requireThat(RetainedTrialPrefix.open(response, 0).completedTurns(), "completedTurns").isEqualTo(0);
			requireThat(Files.exists(exported.resolve("project")), "projectExists").isFalse();
		}
	}

	/**
	 * Supplies evidence defects that keep an interrupted trial from reopening.
	 *
	 * @return defect identifiers
	 */
	@DataProvider
	public Object[][] defects()
	{
		return new Object[][]{{"in_progress"}, {"wrong_turn"}, {"stale_snapshot"}, {"missing_events"},
			{"invalid_reply"}, {"missing_transcript"}, {"missing_project"}};
	}

	/**
	 * Publishing refuses mismatched checkpoints, stale snapshots and incomplete exact turn evidence.
	 *
	 * @param defect the change to the otherwise complete claimed prefix
	 * @throws IOException if the prefix is refused or a fixture file cannot be written
	 */
	@Test(dataProvider = "defects", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*retained trial prefix.*")
	public void refusesIncompletePrefix(String defect) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("retained-trial-prefix"));
			Path session = Files.createDirectory(root.resolve("session"));
			Path response = Files.createDirectory(root.resolve("response"));
			createSessionFiles(session);
			writeTurn(session, 1);
			Path snapshot = RetainedTrialPrefix.capture(session, 1, Optional.of(NATIVE_ROOT));
			writeCheckpoint(session, 1, "ready");
			switch (defect)
			{
				case "in_progress" -> writeCheckpoint(session, 1, "in_progress");
				case "wrong_turn" -> writeCheckpoint(session, 0, "ready");
				case "stale_snapshot" -> RetainedTrialPrefix.capture(session, 2, Optional.of(NATIVE_ROOT));
				case "missing_events" -> Files.delete(session.resolve("artifacts/turn-1.events"));
				case "invalid_reply" -> Files.writeString(session.resolve("artifacts/turn-1.reply.json"),
					REPLY.formatted(1) + "{}");
				case "missing_transcript" -> Files.delete(snapshot.resolve(TRANSCRIPT_FILE));
				case "missing_project" -> TemporaryDirectories.deleteRecursively(snapshot.resolve("initial-project"));
				default -> throw new AssertionError(defect);
			}
			RetainedTrialPrefix.publish(session, response, 1);
		}
	}

	/**
	 * An empty root transcript cannot be replaced by a valid child transcript.
	 *
	 * @throws IOException if the snapshot is refused or a fixture file cannot be written
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*requires.*native transcript.*")
	public void refusesAnEmptyRequiredTranscript() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path session = cleanup.add(Files.createTempDirectory("retained-trial-session"));
			createSessionFiles(session);
			Files.writeString(session.resolve(TRANSCRIPT_FILE), "");
			Files.writeString(session.resolve("artifacts/session-transcripts/child.jsonl"), "child evidence\n");
			RetainedTrialPrefix.capture(session, 1, Optional.of(NATIVE_ROOT));
		}
	}

	/**
	 * Creates project, transcript and unrelated files in the private nested session.
	 *
	 * @param session the private nested session
	 * @throws IOException if a fixture file cannot be written
	 */
	private static void createSessionFiles(Path session) throws IOException
	{
		Files.createDirectories(session.resolve("project"));
		Files.createDirectories(session.resolve("initial-project"));
		Files.createDirectories(session.resolve("artifacts/session-transcripts"));
		Files.createDirectories(session.resolve("home"));
		Files.createDirectories(session.resolve("requests"));
		Files.writeString(session.resolve(PROJECT_FILE), "project");
		Files.writeString(session.resolve(INITIAL_FILE), "initial");
		Files.writeString(session.resolve(TRANSCRIPT_FILE), "native\n");
		Files.writeString(session.resolve("home/secret"), "secret");
		Files.writeString(session.resolve("requests/launch.tar"), "request");
		Files.writeString(session.resolve("artifacts/private.dat"), "unrelated");
	}

	/**
	 * Writes one turn's exact event and completion reply files.
	 *
	 * @param session the private nested session
	 * @param turn    the turn number, starting at one
	 * @throws IOException if a fixture file cannot be written
	 */
	private static void writeTurn(Path session, int turn) throws IOException
	{
		Files.writeString(session.resolve("artifacts/turn-" + turn + ".events"), "event " + turn + "\n");
		Files.writeString(session.resolve("artifacts/turn-" + turn + ".reply.json"), REPLY.formatted(turn));
	}

	/**
	 * Writes the runner's checkpoint for a modeled turn.
	 *
	 * @param session the private nested session
	 * @param turn    the turn number, or zero before the first turn
	 * @param state   the checkpoint's readiness
	 * @throws IOException if the checkpoint cannot be written
	 */
	private static void writeCheckpoint(Path session, int turn, String state) throws IOException
	{
		Files.writeString(session.resolve(CHECKPOINT), """
			{"format_version":1,"turn_number":%d,"state":"%s"}
			""".formatted(turn, state));
	}
}
