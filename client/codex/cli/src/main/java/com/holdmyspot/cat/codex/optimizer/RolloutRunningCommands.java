package com.holdmyspot.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads, from a Codex session's rollouts, the results that each agent received from a command that was still running.
 * <p>
 * Codex returns a tool call's result to the model once the call's yield time passes, even if a command that the call
 * started is still running; the command's exit status and remaining output then reach the app-server, but not the
 * model. Neither the app-server's command item nor the rollout names the call that started a command, so a command is
 * matched with a call by time and by its position in the rollout of the thread that ran it:
 * <ul>
 *   <li>A call is a {@code response_item} whose payload is a {@code function_call} or a {@code custom_tool_call}, and
 *   its result is the {@code function_call_output} or {@code custom_tool_call_output} with the same
 *   {@code call_id}. A call whose {@code call_id} is also the identifier of one of the owner's items, such as a
 *   child's start or a message to another agent, is an app-server item of its own rather than a call that runs
 *   commands, and is not matched with a command.</li>
 *   <li>The rollout's {@code event_msg} of type {@code item_completed} for a {@code CommandExecution} has the
 *   app-server item's identifier and the command's start time, {@code started_at_ms}.</li>
 *   <li>A command belongs to the most recent call made at or before its start. The agent received that call's result
 *   while the command was still running if the result was recorded at or after the command's start and before the
 *   command's {@code item_completed} event.</li>
 * </ul>
 * A command whose start time is not recorded, that no call precedes, or whose call has no result before its end, has
 * no result received while it was running. Commands that run in parallel calls of one agent cannot be told apart: a
 * command is matched with the most recent call before its start.
 */
final class RolloutRunningCommands
{
	/**
	 * Prevents instantiation.
	 */
	private RolloutRunningCommands()
	{
	}

	/**
	 * Reads the results that each agent received from a command that was still running. A line that is not a JSON
	 * object, or whose time cannot be read, is ignored, as is a file whose name does not end with {@code .jsonl} and a
	 * rollout whose first line names no thread.
	 *
	 * @param directory the directory that holds the session's rollouts
	 * @param json      the JSON mapper
	 * @return the text of each result that the agent received while the command was still running, in the order that
	 *         it received them, by the identifier of the command's app-server item; a command whose agent received no
	 *         such result is absent
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if {@code directory} is not a directory or a rollout cannot be read
	 */
	static Map<String, List<String>> read(Path directory, JsonMapper json) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		requireThat(json, "json").isNotNull();
		Map<String, List<String>> running = new HashMap<>();
		for (Path rollout : RolloutToolResults.list(directory))
			readRollout(rollout, json, running);
		return running;
	}

	/**
	 * Reads the results that a rollout's owner received from a command that was still running.
	 *
	 * @param rollout the rollout
	 * @param json    the JSON mapper
	 * @param running the destination, by the identifier of the command's app-server item
	 * @throws IOException if the rollout cannot be read
	 */
	private static void readRollout(Path rollout, JsonMapper json, Map<String, List<String>> running)
		throws IOException
	{
		List<Call> calls = new ArrayList<>();
		Map<String, Result> results = new HashMap<>();
		List<Command> commands = new ArrayList<>();
		Set<String> items = new HashSet<>();
		try (BufferedReader reader = Files.newBufferedReader(rollout))
		{
			String owner = parse(json, reader.readLine()).path("payload").path("id").asString("");
			if (owner.isBlank())
				return;
			int position = 0;
			String line = reader.readLine();
			while (line != null)
			{
				position += 1;
				JsonNode event = parse(json, line);
				JsonNode payload = event.path("payload");
				Instant time = parseTime(event.path("timestamp").asString(""));
				String callId = payload.path("call_id").asString("");
				switch (payload.path("type").asString(""))
				{
					case "function_call", "custom_tool_call" ->
					{
						if (time != null && !callId.isBlank())
							calls.add(new Call(callId, time));
					}
					case "function_call_output", "custom_tool_call_output" ->
					{
						if (time != null && !callId.isBlank())
						{
							String text = String.join("", RolloutToolResults.outputTexts(payload));
							results.putIfAbsent(callId, new Result(time, position, text));
						}
					}
					case "item_completed" ->
					{
						if (payload.path("thread_id").asString("").equals(owner))
						{
							JsonNode item = payload.path("item");
							String id = item.path("id").asString("");
							items.add(id);
							JsonNode started = payload.path("started_at_ms");
							if (item.path("type").asString("").equals("CommandExecution") && !id.isBlank() &&
								started.isIntegralNumber())
							{
								commands.add(new Command(id, Instant.ofEpochMilli(started.longValue()), position));
							}
						}
					}
					default ->
					{
					}
				}
				line = reader.readLine();
			}
		}
		calls.removeIf(call -> items.contains(call.id()));
		for (Command command : commands)
		{
			Call owning = null;
			for (Call call : calls)
			{
				if (!call.time().isAfter(command.started()) && (owning == null || !call.time().isBefore(owning.time())))
					owning = call;
			}
			if (owning == null)
				continue;
			Result result = results.get(owning.id());
			if (result != null && !result.time().isBefore(command.started()) && result.position() < command.ended())
				running.computeIfAbsent(command.id(), _ -> new ArrayList<>()).add(result.text());
		}
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
	 * Parses the time of a rollout line.
	 *
	 * @param timestamp the line's {@code timestamp}
	 * @return the time, or {@code null} if it cannot be read
	 */
	private static Instant parseTime(String timestamp)
	{
		try
		{
			return Instant.parse(timestamp);
		}
		catch (DateTimeParseException _)
		{
			return null;
		}
	}

	/**
	 * A tool call that the owner made.
	 *
	 * @param id   the call's identifier
	 * @param time when the call was recorded
	 */
	private record Call(String id, Instant time)
	{
	}

	/**
	 * The result that the owner received from a call.
	 *
	 * @param time     when the result was recorded
	 * @param position the result's line number in the rollout
	 * @param text     the result's text
	 */
	private record Result(Instant time, int position, String text)
	{
	}

	/**
	 * A command that the owner ran.
	 *
	 * @param id      the identifier of the command's app-server item
	 * @param started when the command started
	 * @param ended   the line number of the command's {@code item_completed} event in the rollout
	 */
	private record Command(String id, Instant started, int ended)
	{
	}
}
