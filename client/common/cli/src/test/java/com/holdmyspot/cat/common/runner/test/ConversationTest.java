package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.Conversation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests {@link Conversation}.
 */
public final class ConversationTest
{
	/**
	 * Creates the test fixture.
	 */
	public ConversationTest()
	{
	}

	/**
	 * Verifies that an {@code end} waits for the turn in progress, and that an {@code interrupt} appended after the
	 * {@code end} still reaches the child, so that the asking session can stop a turn that does not finish.
	 *
	 * @throws IOException if the conversation's files cannot be written
	 */
	@Test(timeOut = 10_000)
	public void endWaitsForTurnButPassesInterrupt() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path folder = cleanup.add(Files.createTempDirectory("cat-conversation-")).resolve("conversation");
			try (Conversation conversation = Conversation.open(folder))
			{
				Path toChild = folder.resolve(Conversation.TO_CHILD_FILE);
				Files.writeString(toChild, "{\"turn\": \"first\"}\n{\"end\": true}\n", StandardOpenOption.APPEND);

				requireThat(conversation.poll(), "turn").
					isEqualTo(Optional.of("{\"type\":\"turn\",\"prompt\":\"first\"}"));
				requireThat(conversation.poll(), "whileTurnRuns").isEqualTo(Optional.empty());
				requireThat(conversation.ended(), "endedWhileTurnRuns").isFalse();

				Files.writeString(toChild, "{\"turn\": \"ignored\"}\nnot json\n{\"interrupt\": true}\n",
					StandardOpenOption.APPEND);

				requireThat(conversation.poll(), "interrupt").isEqualTo(Optional.of("{\"type\":\"interrupt\"}"));
				requireThat(conversation.poll(), "afterInterrupt").isEqualTo(Optional.empty());

				conversation.childRecord("{\"type\":\"turn_failed\",\"error\":\"interrupted\"}");

				requireThat(conversation.poll(), "complete").isEqualTo(Optional.of("{\"type\":\"complete\"}"));
				requireThat(conversation.ended(), "ended").isTrue();
				requireThat(Files.readString(folder.resolve(Conversation.FROM_CHILD_FILE)), "fromChild").
					doesNotContain("refused");
			}
		}
	}

	/**
	 * Verifies that an {@code end} that follows no turn in progress is delivered at once.
	 *
	 * @throws IOException if the conversation's files cannot be written
	 */
	@Test(timeOut = 10_000)
	public void endWithoutTurnCompletesAtOnce() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path folder = cleanup.add(Files.createTempDirectory("cat-conversation-")).resolve("conversation");
			try (Conversation conversation = Conversation.open(folder))
			{
				Files.writeString(folder.resolve(Conversation.TO_CHILD_FILE), "{\"end\": true}\n",
					StandardOpenOption.APPEND);

				requireThat(conversation.poll(), "complete").isEqualTo(Optional.of("{\"type\":\"complete\"}"));
				requireThat(conversation.ended(), "ended").isTrue();
			}
		}
	}

	/**
	 * Verifies that the harness's events go to {@code events.jsonl} as they arrive, before the {@code turn_end} line of
	 * their turn appears in {@code from-child.jsonl}, and that every other record, including a line that is not JSON,
	 * stays in {@code from-child.jsonl} in the order received.
	 *
	 * @throws IOException if the conversation's files cannot be written
	 */
	@Test(timeOut = 10_000)
	public void harnessEventsGoToTheirOwnFile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path folder = cleanup.add(Files.createTempDirectory("cat-conversation-")).resolve("conversation");
			Path fromChild = folder.resolve(Conversation.FROM_CHILD_FILE);
			Path events = folder.resolve(Conversation.EVENTS_FILE);
			try (Conversation conversation = Conversation.open(folder))
			{
				requireThat(Files.readString(events), "emptyEvents").isEmpty();
				String eventOne = "{\"type\":\"harness_event\",\"run_id\":\"r\",\"event\":\"one\"}";
				String eventTwo = "{\"type\":\"harness_event\",\"run_id\":\"r\",\"event\":\"two\"}";
				String waiting = "{\"type\":\"waiting\",\"run_id\":\"r\",\"quiet_seconds\":30}";

				conversation.childRecord("{\"type\":\"started\",\"run_id\":\"r\"}");
				conversation.childRecord(eventOne);
				conversation.childRecord(waiting);
				conversation.childRecord(eventTwo);
				conversation.childRecord("not json");
				requireThat(Files.readAllLines(events), "events").isEqualTo(List.of(eventOne, eventTwo));
				requireThat(Files.readAllLines(fromChild), "beforeTurnEnd").isEqualTo(List.of(
					"{\"type\":\"started\",\"run_id\":\"r\"}", waiting, "not json"));

				String completed = "{\"type\":\"turn_completed\",\"assistant_response\":\"done\"}";
				conversation.childRecord(completed);

				requireThat(Files.readAllLines(events), "eventsAtTurnEnd").isEqualTo(List.of(eventOne, eventTwo));
				requireThat(Files.readAllLines(fromChild), "fromChild").isEqualTo(List.of(
					"{\"type\":\"started\",\"run_id\":\"r\"}", waiting, "not json", completed,
					"{\"turn_end\":1,\"status\":\"completed\"}"));
				conversation.end("completed");
				requireThat(Files.readAllLines(fromChild).getLast(), "last").isEqualTo("{\"ended\":\"completed\"}");
				requireThat(Files.readAllLines(events), "eventsAtEnd").size().isEqualTo(2);
			}
		}
	}
}
