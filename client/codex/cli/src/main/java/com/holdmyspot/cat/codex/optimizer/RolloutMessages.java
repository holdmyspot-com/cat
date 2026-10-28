package com.holdmyspot.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the messages that each thread of a Codex session received from other agents, from the session's rollouts.
 * <p>
 * The app-server reports when a child starts and ends, but not what a child sent: Codex records that only in the
 * rollout of the thread that received it, as a {@code response_item} whose payload is an {@code agent_message} with an
 * {@code author}, a {@code recipient} and {@code content}. Its {@code input_text} items are plain text; an
 * {@code encrypted_content} item cannot be read. The first line of a rollout is its {@code session_meta}, whose
 * {@code id} names the thread that owns the rollout. A rollout also holds an {@code event_msg} of type
 * {@code item_completed} for each of the owner's items that the app-server reports, so the item that most recently
 * completed before a message places the message among the app-server's notifications.
 */
final class RolloutMessages
{
	/**
	 * The text that stands for content that Codex recorded only in encrypted form.
	 */
	private static final String ENCRYPTED = "[encrypted content, not recorded in readable form]";

	/**
	 * Prevents instantiation.
	 */
	private RolloutMessages()
	{
	}

	/**
	 * Reads the messages that each thread received. A line that is not a JSON object is ignored, as is a file whose
	 * name does not end with {@code .jsonl} and a rollout whose first line names no thread.
	 *
	 * @param directory the directory that holds the session's rollouts
	 * @param json      the JSON mapper
	 * @return the messages that each thread received in the order that it received them, by the thread's identifier
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if {@code directory} is not a directory or a rollout cannot be read
	 */
	static Map<String, List<RolloutMessage>> read(Path directory, JsonMapper json) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		requireThat(json, "json").isNotNull();
		Map<String, List<RolloutMessage>> messages = new HashMap<>();
		for (Path rollout : RolloutToolResults.list(directory))
		{
			try (BufferedReader reader = Files.newBufferedReader(rollout))
			{
				String owner = parse(json, reader.readLine()).path("payload").path("id").asString("");
				if (owner.isBlank())
					continue;
				List<RolloutMessage> received = new ArrayList<>();
				String anchor = "";
				String line = reader.readLine();
				while (line != null)
				{
					JsonNode payload = parse(json, line).path("payload");
					switch (payload.path("type").asString(""))
					{
						case "item_completed" ->
						{
							if (payload.path("thread_id").asString("").equals(owner))
								anchor = payload.path("item").path("id").asString(anchor);
						}
						case "agent_message" ->
						{
							String author = payload.path("author").asString("");
							if (!author.isBlank())
								received.add(new RolloutMessage(anchor, author, text(payload.path("content"))));
						}
						default ->
						{
						}
					}
					line = reader.readLine();
				}
				if (!received.isEmpty())
					messages.put(owner, received);
			}
		}
		return messages;
	}

	/**
	 * Parses a rollout line.
	 *
	 * @param json the JSON mapper
	 * @param line the line, or {@code null} at the end of the rollout
	 * @return the line's JSON value, or a missing node if the line is absent or is not JSON
	 */
	private static JsonNode parse(JsonMapper json, String line)
	{
		if (line == null)
			return json.missingNode();
		try
		{
			return json.readTree(line);
		}
		catch (JacksonException _)
		{
			return json.missingNode();
		}
	}

	/**
	 * Returns a message's text, standing in for each part that Codex recorded only in encrypted form.
	 *
	 * @param content the message's content items
	 * @return the text
	 */
	private static String text(JsonNode content)
	{
		StringBuilder text = new StringBuilder(256);
		for (JsonNode item : content)
		{
			switch (item.path("type").asString(""))
			{
				case "input_text" -> text.append(item.path("text").asString(""));
				case "encrypted_content" -> text.append(ENCRYPTED);
				default ->
				{
				}
			}
		}
		return text.toString();
	}

	/**
	 * A message that a thread received from another agent.
	 *
	 * @param anchor the identifier of the owner's item that completed most recently before the message, or an empty
	 *               string when none did
	 * @param author the task path of the agent that sent the message
	 * @param text   the message's text
	 */
	record RolloutMessage(String anchor, String author, String text)
	{
		/**
		 * Creates a message.
		 *
		 * @param anchor the identifier of the owner's item that completed most recently before the message, or an
		 *               empty string when none did
		 * @param author the task path of the agent that sent the message
		 * @param text   the message's text
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code author} is blank
		 */
		RolloutMessage
		{
			requireThat(anchor, "anchor").isNotNull();
			requireThat(author, "author").isNotBlank();
			requireThat(text, "text").isNotNull();
		}
	}
}
