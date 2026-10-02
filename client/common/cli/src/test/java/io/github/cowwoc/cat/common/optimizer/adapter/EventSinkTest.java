package io.github.cowwoc.cat.common.optimizer.adapter;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

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
			Path harnessDirectory = cleanup.add(Files.createTempDirectory("cat-event-sink-"));
			Files.createDirectories(harnessDirectory.resolve("r").resolve("artifacts"));
			BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
			try (EventSink sink = new EventSink(JSON, new TestSessions.Events(JSON),
				directories -> new TestSessions.Sources(JSON, directories), harnessDirectory, Map.of(), messages))
			{
				sink.write("""
					{"type":"started","runId":"r"}
					{"type":"waiting","runId":"r","quietSeconds":30}
					{"type":"turn-completed","assistant_response":"done"}
					""");
			}

			List<Message> received = new ArrayList<>(messages);
			requireThat(received, "received").isEqualTo(List.of(new Message.Started("r"),
				new Message.TurnCompleted("done")));
			Path transcript = harnessDirectory.resolve("r").resolve("artifacts").resolve(EventSink.TRANSCRIPT);
			requireThat(Files.readString(transcript, UTF_8), "transcript").isEmpty();
		}
	}

	/**
	 * Verifies that a {@code harness-event} record, which the bridge writes for each raw event line that the
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
			Path harnessDirectory = cleanup.add(Files.createTempDirectory("cat-event-sink-"));
			Files.createDirectories(harnessDirectory.resolve("r").resolve("artifacts"));
			BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
			try (EventSink sink = new EventSink(JSON, new TestSessions.Events(JSON),
				directories -> new TestSessions.Sources(JSON, directories), harnessDirectory, Map.of(), messages))
			{
				sink.write("""
					{"type":"started","runId":"r"}
					{"type":"harness-event","event":"{\\"type\\":\\"system\\"}"}
					""");
			}

			Path transcript = harnessDirectory.resolve("r").resolve("artifacts").resolve(EventSink.TRANSCRIPT);
			requireThat(Files.readString(transcript, UTF_8), "transcript").isEqualTo("{\"type\":\"system\"}\n");
		}
	}
}
