package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Reads what a conversation's child answered in one of its turns, from the conversation's
 * {@value Conversation#FROM_CHILD_FILE}.
 */
public final class ConversationReply
{
	/**
	 * The exit status while the turn has not ended.
	 */
	public static final int NOT_ENDED = 75;
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Prints the reply of a turn, or why the turn failed.
	 * <p>
	 * A turn that completed prints its {@code assistant_response}, followed by the harness's exit status and standard
	 * error when the status is not {@code 0} or the standard error is not empty. A turn that failed prints
	 * {@code turn <n> failed: <reason>}. A turn that has not ended prints {@code turn <n> has not ended}, and a
	 * conversation that ended without the turn's {@code turn_end} line prints that it ended.
	 *
	 * @param folder     the conversation's folder
	 * @param turn       the turn's number, which {@code 1} gives to the first turn
	 * @param statusOnly {@code true} to print nothing and only return the status
	 * @param output     receives the text
	 * @return {@code 0} if the turn completed, {@value #NOT_ENDED} if it has not ended, or {@code 1} if it failed or the
	 *         conversation ended without it
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code turn} is not positive
	 * @throws IOException              if the conversation's file cannot be read
	 */
	public static int print(Path folder, int turn, boolean statusOnly, PrintWriter output) throws IOException
	{
		requireThat(folder, "folder").isNotNull();
		requireThat(turn, "turn").isPositive();
		requireThat(output, "output").isNotNull();
		List<String> lines = Files.readAllLines(folder.resolve(Conversation.FROM_CHILD_FILE));
		JsonNode last = null;
		String ended = null;
		boolean turnEnded = false;
		for (String line : lines)
		{
			JsonNode record;
			try
			{
				record = JSON.readTree(line);
			}
			catch (JacksonException _)
			{
				// The writer has not finished this line, and the next check reads it whole
				continue;
			}
			String type = record.path("type").asString("");
			if (type.equals("turn_completed") || type.equals("turn_failed"))
				last = record;
			else if (record.path("turn_end").asInt(0) == turn)
			{
				turnEnded = true;
				break;
			}
			else if (record.has("ended"))
				ended = record.path("ended").asString("");
		}
		String text;
		int status;
		if (!turnEnded)
		{
			if (ended == null)
			{
				text = "turn " + turn + " has not ended";
				status = NOT_ENDED;
			}
			else
			{
				text = "the conversation ended as " + ended + " before turn " + turn + " ended";
				status = 1;
			}
		}
		else if (last == null || last.path("type").asString("").equals("turn_failed"))
		{
			String reason = "";
			if (last != null)
				reason = last.path("error").asString("");
			text = "turn " + turn + " failed: " + reason;
			status = 1;
		}
		else
		{
			StringBuilder reply = new StringBuilder(last.path("assistant_response").asString(""));
			int exitCode = last.path("exit_code").asInt(0);
			String error = last.path("error").asString("");
			if (exitCode != 0 || !error.isEmpty())
				reply.append("\nexit_code: ").append(exitCode).append("\nerror: ").append(error);
			text = reply.toString();
			status = 0;
		}
		if (!statusOnly)
			output.println(text);
		return status;
	}

	/**
	 * Prevents construction.
	 */
	private ConversationReply()
	{
	}
}
