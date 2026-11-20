package com.holdmyspot.cat.common.optimizer.adapter;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;

import com.holdmyspot.cat.common.secrets.EventSinkAccess;
import com.holdmyspot.cat.common.secrets.EventSinkSecrets;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.function.Function;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The runner's response stream. It reads each response line as the runner writes it.
 * <p>
 * A {@code harness_event} response goes to the transcript, the event parser and the prompt-source observation. A
 * {@code started}, {@code turn_completed} or {@code turn_failed} response goes to the session driver.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class EventSink extends Writer
{
	static
	{
		EventSinkSecrets.setEventSinkAccess(new EventSinkAccess()
		{
			@Override
			public String transcript()
			{
				return TRANSCRIPT;
			}

			@Override
			public EventSink newEventSink(JsonMapper json, HarnessEvents events,
				Function<Map<String, Path>, PromptSources> newPromptSources, Path harnessDirectory,
				Map<String, Path> placeholders, BlockingQueue<Message> messages)
			{
				return new EventSink(json, events, newPromptSources, harnessDirectory, placeholders, messages);
			}
		});
	}

	/**
	 * The name of the retained harness event archive, inside the nested session's {@code artifacts} directory.
	 */
	static final String TRANSCRIPT = "transcript.jsonl";
	private final JsonMapper json;
	private final HarnessEvents events;
	private final Function<Map<String, Path>, PromptSources> newPromptSources;
	private final Path harnessDirectory;
	private final Map<String, Path> placeholders;
	private final BlockingQueue<Message> messages;
	private final StringBuilder pending = new StringBuilder();
	/**
	 * The harness events that arrived before the runner reported the session's identifier, in arrival order.
	 */
	private final List<String> early = new ArrayList<>();
	/**
	 * The open transcript of the current session, or {@code null} before the runner reports the session's identifier
	 * and after the sink closes. It stays
	 * open because a session writes thousands of events, and opening the file for each one takes about a millisecond
	 * on Windows.
	 */
	private OutputStream transcript;
	private PromptSources promptSources;

	/**
	 * Creates a sink.
	 *
	 * @param json the JSON mapper
	 * @param events the parser that absorbs every harness event
	 * @param newPromptSources creates the prompt-source observation of a nested session from the directories whose staged
	 *   locations must not appear in its value
	 * @param harnessDirectory the directory that holds the runner's session folders for this harness
	 * @param placeholders maps each placeholder to a directory whose staged location must not appear in the prompt
	 *   sources. The sink adds the session folder.
	 * @param messages where the sink reports the runner's progress
	 * @throws NullPointerException if any argument is null
	 */
	EventSink(JsonMapper json, HarnessEvents events, Function<Map<String, Path>, PromptSources> newPromptSources,
		Path harnessDirectory, Map<String, Path> placeholders, BlockingQueue<Message> messages)
	{
		requireThat(json, "json").isNotNull();
		requireThat(events, "events").isNotNull();
		requireThat(newPromptSources, "newPromptSources").isNotNull();
		requireThat(harnessDirectory, "harnessDirectory").isNotNull();
		requireThat(placeholders, "placeholders").isNotNull();
		requireThat(messages, "messages").isNotNull();
		this.json = json;
		this.events = events;
		this.newPromptSources = newPromptSources;
		this.harnessDirectory = harnessDirectory;
		this.placeholders = Map.copyOf(placeholders);
		this.messages = messages;
	}

	/**
	 * Returns the prompt sources observed so far.
	 *
	 * @return the value, or {@code none} if no nested session has started
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

	/**
	 * Closes the transcript.
	 *
	 * @throws IOException if the transcript cannot be closed
	 */
	@Override
	public void close() throws IOException
	{
		synchronized (pending)
		{
			if (transcript != null)
			{
				transcript.close();
				transcript = null;
			}
		}
	}

	/**
	 * Handles one complete response line.
	 *
	 * @param line the line
	 * @throws IOException if the transcript cannot be written or the completed message list is malformed
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
			case "started" -> messages.add(new Message.Started());
			case "session_id" -> start(response.path("session_id").asString(""), false);
			case "retained_session_id" -> start(response.path("session_id").asString(""), true);
			case "retained_turn_start" -> events.startTurn();
			case "harness_event" ->
			{
				String event = response.path("event").asString("");
				events.accept(event);
				if (transcript == null)
					early.add(event);
				else
					record(event);
			}
			case "turn_completed" ->
			{
				JsonNode values = response.path("assistant_messages");
				if (!values.isArray())
					throw new IOException("Runner turn_completed requires assistant_messages to be an array of strings");
				List<String> assistantMessages = new ArrayList<>();
				for (JsonNode message : values)
				{
					if (!message.isString())
						throw new IOException("Runner assistant_messages must contain only strings");
					assistantMessages.add(message.asString());
				}
				messages.add(new TurnCompleted(assistantMessages));
			}
			case "turn_failed" -> messages.add(new Message.TurnFailed(response.path("error").asString("")));
			default ->
			{
			}
		}
	}

	/**
	 * Records a harness event in the session's prompt sources and transcript.
	 *
	 * @param event the event
	 * @throws IOException if the transcript cannot be written
	 */
	private void record(String event) throws IOException
	{
		if (promptSources != null)
			promptSources.accept(event);
		transcript.write((event + "\n").getBytes(UTF_8));
	}

	/**
	 * Begins observing a session once the runner reports the identifier that the harness gave it, which names the
	 * session's directory, and records the harness events that arrived before it.
	 *
	 * @param sessionId the identifier
	 * @param replaying whether the authenticated retained prefix will replace any surviving partial transcript
	 * @throws IOException if the transcript cannot be created or written
	 */
	private void start(String sessionId, boolean replaying) throws IOException
	{
		Path sessionDirectory = harnessDirectory.resolve(sessionId);
		Map<String, Path> directories = new HashMap<>(placeholders);
		directories.put("session", sessionDirectory);
		promptSources = newPromptSources.apply(directories);
		if (transcript != null)
			transcript.close();
		Path artifacts = Files.createDirectories(sessionDirectory.resolve("artifacts"));
		if (replaying)
			transcript = Files.newOutputStream(artifacts.resolve(TRANSCRIPT), CREATE, TRUNCATE_EXISTING);
		else
			transcript = Files.newOutputStream(artifacts.resolve(TRANSCRIPT), CREATE, APPEND);
		for (String event : early)
			record(event);
		early.clear();
		messages.add(new Message.Identified(sessionId));
	}
}
