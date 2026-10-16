package io.github.cowwoc.cat.common.optimizer.adapter.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.adapter.EventSink;
import io.github.cowwoc.cat.common.optimizer.adapter.Message;
import io.github.cowwoc.cat.common.optimizer.adapter.TurnCompleted;
import io.github.cowwoc.cat.common.secrets.EventSinkSecrets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link EventSink} against the records that the runner bridge writes.
 */
public final class EventSinkTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public EventSinkTest()
	{
	}

	/**
	 * Verifies that a {@code waiting} record, which the bridge writes while a turn is quiet, neither reports progress
	 * nor reaches the harness event transcript.
	 *
	 * @throws IOException if the run directory cannot be created
	 */
	// Requirement: R-E14
	@Test
	public void ignoresWaitingRecord() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path harnessDirectory = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.work.temp"))),
				"cat-event-sink-"));
			Files.createDirectories(harnessDirectory.resolve("r").resolve("artifacts"));
			BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
			try (EventSink sink = EventSinkSecrets.access().newEventSink(JSON, new TestSessions.Events(JSON),
				directories -> new TestSessions.Sources(JSON, directories), harnessDirectory, Map.of(), messages))
			{
				sink.write("""
					{"type":"started","run_id":"r"}
					{"type":"waiting","run_id":"r","quiet_seconds":30}
					{"type":"turn_completed","assistant_response":"done","assistant_messages":["done"]}
					""");
			}

			List<Message> received = new ArrayList<>(messages);
			requireThat(received, "received").isEqualTo(List.of(new Message.Started("r"),
				new TurnCompleted(List.of("done"))));
			Path transcript = harnessDirectory.resolve("r").resolve("artifacts").
				resolve(EventSinkSecrets.access().transcript());
			requireThat(Files.readString(transcript, UTF_8), "transcript").isEmpty();
		}
	}

	/**
	 * Verifies the bridge's ordered messages reach the session driver even when the final-response field differs.
	 *
	 * @throws IOException if the run directory cannot be created
	 */
	// Requirement: R-C12
	@Test
	public void forwardsEveryAssistantMessage() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path harnessDirectory = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.work.temp"))), "cat-event-sink-"));
			Files.createDirectories(harnessDirectory.resolve("r").resolve("artifacts"));
			BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
			try (EventSink sink = EventSinkSecrets.access().newEventSink(JSON, new TestSessions.Events(JSON),
				directories -> new TestSessions.Sources(JSON, directories), harnessDirectory, Map.of(), messages))
			{
				sink.write("""
					{"type":"started","run_id":"r"}
					{"type":"turn_completed","assistant_response":"final-only","assistant_messages":["interim","done"]}
					""");
			}

			requireThat(messages.remove(), "started").isEqualTo(new Message.Started("r"));
			requireThat(messages.remove(), "completed").
				isEqualTo(new TurnCompleted(List.of("interim", "done")));
		}
	}

	/**
	 * Verifies that the bridge's report of a failed turn reaches the session driver with the bridge's reason.
	 *
	 * @throws IOException if the run directory cannot be created
	 */
	@Test
	public void forwardsTurnFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path harnessDirectory = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.work.temp"))), "cat-event-sink-"));
			Files.createDirectories(harnessDirectory.resolve("r").resolve("artifacts"));
			BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
			try (EventSink sink = EventSinkSecrets.access().newEventSink(JSON, new TestSessions.Events(JSON),
				directories -> new TestSessions.Sources(JSON, directories), harnessDirectory, Map.of(), messages))
			{
				sink.write("""
					{"type":"started","run_id":"r"}
					{"type":"turn_failed","run_id":"r","error":"the harness exited"}
					""");
			}

			requireThat(messages.remove(), "started").isEqualTo(new Message.Started("r"));
			requireThat(messages.remove(), "failed").isEqualTo(new Message.TurnFailed("the harness exited"));
		}
	}

	/**
	 * Returns invalid message lists that must not become completed reply evidence.
	 *
	 * @return the malformed JSON values
	 */
	@DataProvider
	public static Object[][] malformedMessages()
	{
		return new Object[][] {{"null"}, {"\"text\""}, {"{}"}, {"[1]"}, {"[null]"}};
	}

	/**
	 * Rejects a completion whose messages are not an array of strings before reporting completed evidence.
	 *
	 * @param value the malformed JSON message list
	 * @throws IOException if the completion has malformed messages
	 */
	// Requirement: R-C12
	@Test(dataProvider = "malformedMessages", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*assistant_messages.*")
	public void rejectsMalformedMessages(String value) throws IOException
	{
		try (EventSink sink = EventSinkSecrets.access().newEventSink(JSON, new TestSessions.Events(JSON),
			directories -> new TestSessions.Sources(JSON, directories), Path.of("runs"), Map.of(),
			new LinkedBlockingQueue<>()))
		{
			sink.write("{\"type\":\"turn_completed\",\"assistant_messages\":" + value + "}\n");
		}
	}

	/**
	 * Verifies that a {@code harness_event} record, which the bridge writes for each raw event line that the
	 * harness prints, reaches the retained event transcript.
	 *
	 * @throws IOException if the run directory cannot be created
	 */
	// Requirement: R-E14
	@Test
	public void retainsHarnessEventInTranscript() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path harnessDirectory = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.work.temp"))),
				"cat-event-sink-"));
			Files.createDirectories(harnessDirectory.resolve("r").resolve("artifacts"));
			BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
			try (EventSink sink = EventSinkSecrets.access().newEventSink(JSON, new TestSessions.Events(JSON),
				directories -> new TestSessions.Sources(JSON, directories), harnessDirectory, Map.of(), messages))
			{
				sink.write("""
					{"type":"started","run_id":"r"}
					{"type":"harness_event","event":"{\\"type\\":\\"system\\"}"}
					""");
			}

			Path transcript = harnessDirectory.resolve("r").resolve("artifacts").
				resolve(EventSinkSecrets.access().transcript());
			requireThat(Files.readString(transcript, UTF_8), "transcript").isEqualTo("{\"type\":\"system\"}\n");
		}
	}
}
