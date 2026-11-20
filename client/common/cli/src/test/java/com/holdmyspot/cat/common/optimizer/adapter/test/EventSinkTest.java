package com.holdmyspot.cat.common.optimizer.adapter.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.adapter.EventSink;
import com.holdmyspot.cat.common.optimizer.adapter.Message;
import com.holdmyspot.cat.common.optimizer.adapter.TurnCompleted;
import com.holdmyspot.cat.common.secrets.EventSinkSecrets;

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
	 * @throws IOException if the session folder cannot be created
	 */
	// Requirement: R-E14
	@Test
	public void ignoresWaitingRecord() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path harnessDirectory = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-event-sink-"));
			Files.createDirectories(harnessDirectory.resolve("r").resolve("artifacts"));
			BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
			try (EventSink sink = EventSinkSecrets.getEventSinkAccess().newEventSink(JSON, new TestSessions.Events(JSON),
				directories -> new TestSessions.Sources(JSON, directories), harnessDirectory, Map.of(), messages))
			{
				sink.write("""
					{"type":"started"}
					{"type":"session_id","session_id":"r"}
					{"type":"waiting","quiet_seconds":30}
					{"type":"turn_completed","assistant_response":"done","assistant_messages":["done"]}
					""");
			}

			List<Message> received = new ArrayList<>(messages);
			requireThat(received, "received").isEqualTo(List.of(new Message.Started(), new Message.Identified("r"),
				new TurnCompleted(List.of("done"))));
			Path transcript = harnessDirectory.resolve("r").resolve("artifacts").
				resolve(EventSinkSecrets.getEventSinkAccess().transcript());
			requireThat(Files.readString(transcript, UTF_8), "transcript").isEmpty();
		}
	}

	/**
	 * Verifies the bridge's ordered messages reach the session driver even when the final-response field differs.
	 *
	 * @throws IOException if the session folder cannot be created
	 */
	// Requirement: R-C12
	@Test
	public void forwardsEveryAssistantMessage() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path harnessDirectory = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(), "cat-event-sink-"));
			Files.createDirectories(harnessDirectory.resolve("r").resolve("artifacts"));
			BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
			try (EventSink sink = EventSinkSecrets.getEventSinkAccess().newEventSink(JSON, new TestSessions.Events(JSON),
				directories -> new TestSessions.Sources(JSON, directories), harnessDirectory, Map.of(), messages))
			{
				sink.write("""
					{"type":"started"}
					{"type":"session_id","session_id":"r"}
					{"type":"turn_completed","assistant_response":"final-only","assistant_messages":["interim","done"]}
					""");
			}

			requireThat(messages.remove(), "started").isEqualTo(new Message.Started());
			requireThat(messages.remove(), "identified").isEqualTo(new Message.Identified("r"));
			requireThat(messages.remove(), "completed").
				isEqualTo(new TurnCompleted(List.of("interim", "done")));
		}
	}

	/**
	 * Verifies that the bridge's report of a failed turn reaches the session driver with the bridge's reason.
	 *
	 * @throws IOException if the session folder cannot be created
	 */
	@Test
	public void forwardsTurnFailure() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path harnessDirectory = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(), "cat-event-sink-"));
			Files.createDirectories(harnessDirectory.resolve("r").resolve("artifacts"));
			BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
			try (EventSink sink = EventSinkSecrets.getEventSinkAccess().newEventSink(JSON, new TestSessions.Events(JSON),
				directories -> new TestSessions.Sources(JSON, directories), harnessDirectory, Map.of(), messages))
			{
				sink.write("""
					{"type":"started"}
					{"type":"session_id","session_id":"r"}
					{"type":"turn_failed","error":"the harness exited"}
					""");
			}

			requireThat(messages.remove(), "started").isEqualTo(new Message.Started());
			requireThat(messages.remove(), "identified").isEqualTo(new Message.Identified("r"));
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
		BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
		try (EventSink sink = EventSinkSecrets.getEventSinkAccess().newEventSink(JSON, new TestSessions.Events(JSON),
			directories -> new TestSessions.Sources(JSON, directories), Path.of("results"), Map.of(), messages))
		{
			sink.write("{\"type\":\"turn_completed\",\"assistant_messages\":" + value + "}\n");
		}
		catch (IOException e)
		{
			requireThat(messages, "messages").isEmpty();
			throw e;
		}
	}

	/**
	 * Verifies that a {@code harness_event} record, which the bridge writes for each raw event line that the
	 * harness prints, reaches the retained event transcript.
	 *
	 * @throws IOException if the session folder cannot be created
	 */
	// Requirement: R-E14
	@Test
	public void retainsHarnessEventInTranscript() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path harnessDirectory = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-event-sink-"));
			Files.createDirectories(harnessDirectory.resolve("r").resolve("artifacts"));
			BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
			try (EventSink sink = EventSinkSecrets.getEventSinkAccess().newEventSink(JSON, new TestSessions.Events(JSON),
				directories -> new TestSessions.Sources(JSON, directories), harnessDirectory, Map.of(), messages))
			{
				sink.write("""
					{"type":"started"}
					{"type":"session_id","session_id":"r"}
					{"type":"harness_event","event":"{\\"type\\":\\"system\\"}"}
					""");
			}

			Path transcript = harnessDirectory.resolve("r").resolve("artifacts").
				resolve(EventSinkSecrets.getEventSinkAccess().transcript());
			requireThat(Files.readString(transcript, UTF_8), "transcript").isEqualTo("{\"type\":\"system\"}\n");
		}
	}

	/**
	 * Verifies that the harness events that arrive before the runner reports the session's ID, such as the event that
	 * reports it, reach the transcript in the directory that the ID names.
	 *
	 * @throws IOException if the session folder cannot be created
	 */
	@Test
	public void keepsEventsBeforeSessionId() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path harnessDirectory = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-event-sink-"));
			Files.createDirectories(harnessDirectory.resolve("s1").resolve("artifacts"));
			BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
			try (EventSink sink = EventSinkSecrets.getEventSinkAccess().newEventSink(JSON, new TestSessions.Events(JSON),
				directories -> new TestSessions.Sources(JSON, directories), harnessDirectory, Map.of(), messages))
			{
				sink.write("""
					{"type":"started"}
					{"type":"harness_event","event":"{\\"type\\":\\"init\\"}"}
					{"type":"session_id","session_id":"s1"}
					{"type":"harness_event","event":"{\\"type\\":\\"system\\"}"}
					""");
			}

			requireThat(new ArrayList<>(messages), "messages").
				isEqualTo(List.of(new Message.Started(), new Message.Identified("s1")));
			Path transcript = harnessDirectory.resolve("s1").resolve("artifacts").
				resolve(EventSinkSecrets.getEventSinkAccess().transcript());
			requireThat(Files.readString(transcript, UTF_8), "transcript").
				isEqualTo("{\"type\":\"init\"}\n{\"type\":\"system\"}\n");
		}
	}
}
