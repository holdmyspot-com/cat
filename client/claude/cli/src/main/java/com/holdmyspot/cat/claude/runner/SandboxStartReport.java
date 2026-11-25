package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds Claude Code's report that its command sandbox failed to start in the lines of a session's standard output.
 * <p>
 * When Claude Code cannot start its sandbox, it reports the failure as the result of the session's first Bash call and
 * runs every later call without the sandbox, although the session's settings forbid unsandboxed commands. The report
 * is one sentence, {@code Sandbox is enabled but failed to initialize: <reason>. Sandboxing is disabled for the rest of
 * this session; restart to retry.}, whatever the reason: a bridge socket that cannot be created and a socket that
 * cannot be listened on, which the recordings under {@code runner/sandbox-start} show, give the same frame. The report
 * is an error result that holds nothing else, so a command that prints the sentence, or a failed command whose output
 * contains it, is not mistaken for the report. Only the session's first Bash result can be the report.
 * <p>
 * After the report, this object records whether a Bash result followed it, which shows that a command ran after the
 * sandbox was disabled.
 * <p>
 * <b>Thread safety:</b> This class is thread-safe.
 */
public final class SandboxStartReport
{
	/**
	 * The report's frame, with any reason.
	 */
	private static final Pattern REPORT = Pattern.compile("Sandbox is enabled but failed to initialize: .+\\. " +
		"Sandboxing is disabled for the rest of this session; restart to retry\\.", Pattern.DOTALL);
	private final JsonMapper json;
	/**
	 * Guards every field below.
	 */
	private final Object lock = new Object();
	/**
	 * The identifiers of the Bash calls that have no result yet.
	 */
	private final Set<String> pendingBashCalls = new HashSet<>();
	/**
	 * The report, or an empty string until the session's first Bash result is the report.
	 */
	private String report = "";
	/**
	 * Indicates whether a Bash result has followed the report.
	 */
	private boolean bashResultFollowed;
	/**
	 * Indicates whether the session's first Bash result was not the report, so that no later result can be.
	 */
	private boolean firstResultWasOrdinary;

	/**
	 * Creates the state of a session that has printed no Bash result.
	 *
	 * @param json parses Claude's output lines
	 * @throws NullPointerException if {@code json} is null
	 */
	public SandboxStartReport(JsonMapper json)
	{
		requireThat(json, "json").isNotNull();
		this.json = json;
	}

	/**
	 * Records one output line.
	 *
	 * @param line a line of Claude's standard output
	 * @return the report if this line is the result of the session's first Bash call and holds it, otherwise none
	 */
	public Optional<String> accept(String line)
	{
		assert line != null;
		synchronized (lock)
		{
			if (firstResultWasOrdinary || (!line.contains("tool_use") && !line.contains("tool_result")))
				return Optional.empty();
			JsonNode event;
			try
			{
				event = json.readTree(line);
			}
			catch (JacksonException _)
			{
				// A diagnostic line that is not protocol JSON holds no tool call.
				return Optional.empty();
			}
			String type = event.path("type").asString("");
			for (JsonNode block : event.path("message").path("content"))
			{
				if (type.equals("assistant") && block.path("type").asString("").equals("tool_use") &&
					block.path("name").asString("").equals("Bash"))
				{
					pendingBashCalls.add(block.path("id").asString(""));
				}
				else if (type.equals("user") && block.path("type").asString("").equals("tool_result") &&
					pendingBashCalls.remove(block.path("tool_use_id").asString("")))
				{
					return acceptBashResult(block);
				}
			}
			return Optional.empty();
		}
	}

	/**
	 * Records the result of a Bash call.
	 *
	 * @param result the {@code tool_result} block
	 * @return the report if the result holds it, otherwise none
	 */
	private Optional<String> acceptBashResult(JsonNode result)
	{
		assert result != null;
		if (!report.isEmpty())
		{
			bashResultFollowed = true;
			return Optional.empty();
		}
		String text = resultText(result.path("content")).strip();
		if (result.path("is_error").asBoolean(false) && REPORT.matcher(text).matches())
		{
			report = text;
			return Optional.of(text);
		}
		firstResultWasOrdinary = true;
		pendingBashCalls.clear();
		return Optional.empty();
	}

	/**
	 * Returns the text of a result's content, which Claude prints either as a string or as a list of blocks.
	 *
	 * @param content the {@code content} of a {@code tool_result} block
	 * @return the text, with the text blocks of a list joined by newlines
	 */
	private static String resultText(JsonNode content)
	{
		assert content != null;
		if (content.isString())
			return content.asString();
		StringJoiner text = new StringJoiner("\n");
		for (JsonNode block : content)
		{
			if (block.path("type").asString("").equals("text"))
				text.add(block.path("text").asString(""));
		}
		return text.toString();
	}

	/**
	 * Indicates whether a Bash result followed the report.
	 *
	 * @return {@code true} if a Bash result was recorded after the report; {@code false} if there was no report or no
	 *   Bash result followed it
	 */
	public boolean bashResultFollowed()
	{
		synchronized (lock)
		{
			return bashResultFollowed;
		}
	}
}
