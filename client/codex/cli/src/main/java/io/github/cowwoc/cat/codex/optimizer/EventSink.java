package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The runner's response stream. It reads each response line as the runner writes it.
 * <p>
 * A {@code native-event} response goes to the transcript, the event parser and the prompt-source observation. A
 * {@code started} or {@code turn-completed} response goes to the session driver.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class EventSink extends Writer
{
	/**
	 * The name of the retained native event archive, inside the run's {@code artifacts} directory.
	 */
	static final String TRANSCRIPT = "transcript.jsonl";
	private final JsonMapper json;
	private final NativeEvents events;
	private final Path harnessDirectory;
	private final Map<String, Path> placeholders;
	private final BlockingQueue<Message> messages;
	private final StringBuilder pending = new StringBuilder();
	private Path transcript;
	private PromptSources promptSources;

	/**
	 * Creates a sink.
	 *
	 * @param json the JSON mapper
	 * @param events the parser that absorbs every native event
	 * @param harnessDirectory the directory that holds the runner's run directories for this harness
	 * @param placeholders maps each placeholder to a directory whose staged location must not appear in the prompt
	 *   sources. The sink adds the run directory.
	 * @param messages where the sink reports the runner's progress
	 * @throws NullPointerException if any argument is null
	 */
	EventSink(JsonMapper json, NativeEvents events, Path harnessDirectory, Map<String, Path> placeholders,
		BlockingQueue<Message> messages)
	{
		requireThat(json, "json").isNotNull();
		requireThat(events, "events").isNotNull();
		requireThat(harnessDirectory, "harnessDirectory").isNotNull();
		requireThat(placeholders, "placeholders").isNotNull();
		requireThat(messages, "messages").isNotNull();
		this.json = json;
		this.events = events;
		this.harnessDirectory = harnessDirectory;
		this.placeholders = Map.copyOf(placeholders);
		this.messages = messages;
	}

	/**
	 * Returns the prompt sources observed so far.
	 *
	 * @return the value, or {@code none} if no run has started
	 */
	String promptSources()
	{
		synchronized (pending)
		{
			if (promptSources == null)
				return "none";
			return promptSources.value();
		}
	}

	@Override
	public void write(char[] buffer, int offset, int length) throws IOException
	{
		synchronized (pending)
		{
			pending.append(buffer, offset, length);
			int end = pending.indexOf("\n");
			while (end >= 0)
			{
				String line = pending.substring(0, end);
				pending.delete(0, end + 1);
				accept(line);
				end = pending.indexOf("\n");
			}
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

	/**
	 * Handles one complete response line.
	 *
	 * @param line the line
	 * @throws IOException if the transcript cannot be written
	 */
	private void accept(String line) throws IOException
	{
		JsonNode response;
		try
		{
			response = json.readTree(line);
		}
		catch (JacksonException _)
		{
			return;
		}
		switch (response.path("type").asString(""))
		{
			case "started" -> start(response.path("runId").asString(""));
			case "native-event" ->
			{
				String event = response.path("event").asString("");
				events.accept(event);
				if (promptSources != null)
					promptSources.accept(event);
				if (transcript != null)
					Files.writeString(transcript, event + "\n", UTF_8, CREATE, APPEND);
			}
			case "turn-completed" ->
				messages.add(new Message.TurnCompleted(response.path("assistant_response").asString("")));
			default ->
			{
			}
		}
	}

	/**
	 * Begins observing a run.
	 *
	 * @param runId the identifier of the run directory
	 * @throws IOException if the transcript cannot be created
	 */
	private void start(String runId) throws IOException
	{
		Path runDirectory = harnessDirectory.resolve(runId);
		Map<String, Path> directories = new HashMap<>(placeholders);
		directories.put("run", runDirectory);
		promptSources = new PromptSources(json, directories);
		transcript = runDirectory.resolve("artifacts").resolve(TRANSCRIPT);
		Files.writeString(transcript, "", UTF_8, CREATE, APPEND);
		messages.add(new Message.Started(runId));
	}
}
