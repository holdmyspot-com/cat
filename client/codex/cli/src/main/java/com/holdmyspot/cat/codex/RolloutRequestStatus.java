package com.holdmyspot.cat.codex;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reports, from a Codex rollout, whether a later turn answered each user message that the latest compaction kept.
 * <p>
 * When Codex compacts a thread, it keeps the user's messages but drops every reply, so a kept message looks like a
 * request that nobody answered. The rollout still records every reply. The rollout records when each message was sent,
 * the final answer of its turn, and whether the turn completed, was aborted, or ended without completing when a later
 * turn started; whether the answer finished the requested work stays the reader's judgement from the answer's text.
 * A message whose own turn has no answer also lists the turns that completed after it, because one of them may have
 * finished its work.
 * <p>
 * A kept message matches a message the rollout recorded before the compaction when their texts are equal, when the
 * kept text is the start of the recorded one, or when the kept text is the start and end of the recorded one around
 * Codex's truncation marker. A message that the user sends again after the compaction is a new request and does not
 * match a kept one.
 * <p>
 * A thread that was forked has a rollout that holds only the fork's own records. The report reads the records of the
 * rollouts that the fork inherited its history from, as far as {@link RolloutLineage} finds them, before the fork's
 * own. A kept message whose rollout cannot be found is reported as not found in the session log.
 */
// WORKAROUND: https://github.com/openai/codex/issues/42611
// Codex replays an already-answered kept user message as a new turn after compaction. Remove this class, and the
// SessionStart section and get-history operation that use it, once Codex marks kept messages as already handled.
final class RolloutRequestStatus
{
	/**
	 * The longest excerpt of a message or answer that a report quotes, in characters.
	 */
	private static final int EXCERPT_LENGTH = 120;
	/**
	 * The heading of the context section that lists the kept user messages' outcomes.
	 */
	static final String KEPT_SECTION_HEADING = "# Kept User Messages";
	/**
	 * Codex's marker for text that it cut from the middle of a kept message, with the line breaks that join it to the
	 * kept parts.
	 */
	private static final Pattern TRUNCATION_MARKER = Pattern.compile("\\n?…\\d+ (?:tokens|chars) truncated…\\n?");
	/**
	 * The start of the developer message that Codex writes when the user interrupts a turn. Codex writes it even when it
	 * writes no {@code turn_aborted} event for the turn.
	 */
	private static final String ABORT_NOTICE_PREFIX = "<turn_aborted";
	/**
	 * The starts of the user-role text that Codex adds to a conversation rather than the user typing it.
	 */
	private static final List<String> INJECTED_TEXT_PREFIXES = List.of("<environment_context", "<codex_internal_context",
		"<goal_context", "# AGENTS.md instructions", "<user_instructions", "<user_shell_command", ABORT_NOTICE_PREFIX,
		"<subagent_notification", "<agent_message_board_notification", "<skill", "<recommended_plugins",
		"<model_instructions", "<user_verification_notice", "<image_resize_notice", "<current_time");
	/**
	 * The JSON strings that each record this class reads holds, one of which every such rollout line contains whatever
	 * spacing its JSON has.
	 */
	private static final List<String> RECORD_MARKERS = List.of("\"user\"", "\"final_answer\"", "\"task_started\"",
		"\"task_complete\"", "\"turn_aborted\"", "\"compacted\"", ABORT_NOTICE_PREFIX + ">");

	private final List<UserMessage> userMessages;
	private final Map<String, TurnOutcome> outcomesByTurn;
	private final Compaction latestCompaction;

	/**
	 * Creates a status report.
	 *
	 * @param userMessages the user messages the rollout recorded, in rollout order
	 * @param outcomesByTurn the answers and ends of the rollout's turns, by turn ID
	 * @param latestCompaction the rollout's latest compaction, or {@code null} if it has none
	 */
	private RolloutRequestStatus(List<UserMessage> userMessages, Map<String, TurnOutcome> outcomesByTurn,
		Compaction latestCompaction)
	{
		this.userMessages = userMessages;
		this.outcomesByTurn = outcomesByTurn;
		this.latestCompaction = latestCompaction;
	}

	/**
	 * Reads the user messages, answers, turn ends and latest compaction of a Codex rollout. When the rollout is a fork's,
	 * the records of the rollouts that it inherited its history from, up to where it inherited them, come first. Only
	 * lines that hold one of those records are parsed, so the rest of a large rollout costs one substring search per
	 * line.
	 *
	 * @param json the JSON mapper
	 * @param rollout the rollout JSONL file
	 * @return the rollout's request status
	 * @throws NullPointerException if {@code json} or {@code rollout} are null
	 * @throws IOException if the rollout cannot be read, or a line that holds one of those records is not JSON. A
	 *   rollout that the fork inherited from is read as far as it exists: one that cannot be found or read leaves its
	 *   messages unfound without failing this method
	 */
	static RolloutRequestStatus read(JsonMapper json, Path rollout) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(rollout, "rollout").isNotNull();
		List<RolloutLineage.Segment> segments = RolloutLineage.resolve(json, rollout);
		Reading reading = new Reading();
		for (RolloutLineage.Segment segment : segments)
		{
			try
			{
				reading.read(json, segment);
			}
			catch (IOException e)
			{
				if (segment.rollout().equals(rollout))
					throw e;
				// An inherited rollout that fails midway still supplies the records read before the failure
			}
		}
		return new RolloutRequestStatus(reading.userMessages, reading.outcomesByTurn, reading.latestCompaction);
	}

	/**
	 * Returns the context section that lists, for each user message that the latest compaction kept, when it was sent
	 * and whether a later turn answered it.
	 *
	 * @return the section, or an empty string if the rollout has no compaction or the compaction kept no typed message
	 */
	String keptSection()
	{
		if (latestCompaction == null || latestCompaction.keptTexts().isEmpty())
			return "";
		StringJoiner section = new StringJoiner("\n", "", "\n");
		section.add(KEPT_SECTION_HEADING);
		section.add("");
		section.add("Codex compacted this conversation at " + format(latestCompaction.time()) + ". The session log " +
			"records these outcomes for the user messages that the compaction kept, oldest first:");
		section.add("");
		// The compaction keeps the newest messages in order, so the newest kept text is the newest matching message,
		// and each earlier kept text is the newest matching message before the one that the next kept text matched
		List<String> keptTexts = latestCompaction.keptTexts();
		String[] entries = new String[keptTexts.size()];
		long before = latestCompaction.position();
		for (int i = keptTexts.size() - 1; i >= 0; --i)
		{
			String kept = keptTexts.get(i);
			UserMessage message = lastMatchBefore(kept, before);
			if (message == null)
				entries[i] = "- " + quote(kept) + ": not found in the session log";
			else
			{
				entries[i] = "- " + describe(message);
				before = message.position();
			}
		}
		for (String entry : entries)
			section.add(entry);
		return section.toString();
	}

	/**
	 * Returns the status of every message in the rollout whose text matches a given text, split at the latest
	 * compaction: the last matching message before it, which the compaction kept if it kept that text, and each
	 * matching message after it, which is a new request.
	 *
	 * @param text the message text, or its start
	 * @return the report, one line per message
	 * @throws NullPointerException if {@code text} is null
	 * @throws IllegalArgumentException if {@code text} is blank
	 */
	String status(String text)
	{
		requireThat(text, "text").isNotBlank();
		StringJoiner report = new StringJoiner("\n", "", "\n");
		long compactionPosition;
		if (latestCompaction == null)
		{
			report.add("compacted_at=none");
			compactionPosition = Long.MAX_VALUE;
		}
		else
		{
			report.add("compacted_at=" + format(latestCompaction.time()));
			compactionPosition = latestCompaction.position();
		}
		UserMessage before = lastMatchBefore(text, compactionPosition);
		if (before == null)
			report.add("before_compaction: not found in the session log");
		else
			report.add("before_compaction: " + describe(before));
		for (UserMessage message : userMessages)
		{
			if (message.position() > compactionPosition && matches(text, message.text()))
				report.add("after_compaction: " + describe(message));
		}
		return report.toString();
	}

	/**
	 * Returns the last typed user message before a position whose text a given text matches.
	 *
	 * @param text the kept or supplied text
	 * @param position the rollout line before which to look
	 * @return the message, or {@code null} if none matches
	 */
	private UserMessage lastMatchBefore(String text, long position)
	{
		UserMessage match = null;
		for (UserMessage message : userMessages)
		{
			if (message.position() >= position)
				break;
			if (matches(text, message.text()))
				match = message;
		}
		return match;
	}

	/**
	 * Describes when a message was sent and how its turn answered it.
	 *
	 * @param message the message
	 * @return the description
	 */
	private String describe(UserMessage message)
	{
		String sent = quote(message.text()) + " (sent " + format(message.time()) + "): ";
		TurnOutcome outcome = outcomesByTurn.getOrDefault(message.turnId(), new TurnOutcome());
		Answer answer = outcome.answerAfter(message.position());
		Event aborted = outcome.endAfter(outcome.aborted, message.position());
		Event completed = outcome.endAfter(outcome.completed, message.position());
		Event superseded = outcome.endAfter(outcome.superseded, message.position());
		String end;
		if (aborted != null)
			end = "turn aborted at " + format(aborted.time());
		else if (completed != null)
			end = "turn completed";
		else if (superseded != null)
			end = "turn ended without completing";
		else
			end = "turn not finished";
		if (answer == null)
			return sent + "no answer in its own turn, " + end + laterCompletedTurns(message);
		return sent + "answered at " + format(answer.time()) + ", " + end + ": " + quote(answer.text());
	}

	/**
	 * Lists the turns that completed after a message and are not its own turn, so that a reader can check whether one of
	 * them finished the requested work.
	 *
	 * @param message the message
	 * @return {@code "; later turns completed at "} followed by the completion times, oldest first, or an empty string if
	 *   no other turn completed after the message
	 */
	private String laterCompletedTurns(UserMessage message)
	{
		List<Event> completions = new ArrayList<>();
		for (Map.Entry<String, TurnOutcome> turn : outcomesByTurn.entrySet())
		{
			Event completed = turn.getValue().endAfter(turn.getValue().completed, message.position());
			if (completed != null && !turn.getKey().equals(message.turnId()))
				completions.add(completed);
		}
		if (completions.isEmpty())
			return "";
		completions.sort(Comparator.comparingLong(Event::position));
		StringJoiner times = new StringJoiner(", ", "; later turns completed at ", "");
		for (Event completed : completions)
			times.add(format(completed.time()));
		return times.toString();
	}

	/**
	 * Returns whether a kept or supplied text matches a recorded message's text: they are equal, the text is the start
	 * of the recorded one, or the text is the start and end of the recorded one around Codex's truncation marker.
	 *
	 * @param text the kept or supplied text
	 * @param recorded the recorded message's text
	 * @return {@code true} if they match
	 */
	private static boolean matches(String text, String recorded)
	{
		if (recorded.startsWith(text))
			return true;
		Matcher marker = TRUNCATION_MARKER.matcher(text);
		if (!marker.find())
			return false;
		String start = text.substring(0, marker.start());
		String end = text.substring(marker.end());
		return recorded.length() >= start.length() + end.length() && recorded.startsWith(start) &&
			recorded.endsWith(end);
	}

	/**
	 * Returns whether a rollout line can hold a record that this class reads.
	 *
	 * @param line the line
	 * @return {@code true} if the line holds one of the substrings of those records
	 */
	private static boolean holdsReadRecord(String line)
	{
		for (String marker : RECORD_MARKERS)
		{
			if (line.contains(marker))
				return true;
		}
		return false;
	}

	/**
	 * Returns the texts of the typed user messages that a compaction's replacement history kept.
	 *
	 * @param replacementHistory the compaction's {@code replacement_history}
	 * @return the texts, in history order
	 */
	private static List<String> keptTexts(JsonNode replacementHistory)
	{
		List<String> texts = new ArrayList<>();
		for (JsonNode item : replacementHistory)
		{
			if (item.path("type").asString("").equals("message") && item.path("role").asString("").equals("user") &&
				isTyped(item))
			{
				String text = text(item);
				if (!text.isBlank())
					texts.add(text);
			}
		}
		return texts;
	}

	/**
	 * Returns whether a user-role message holds text that the user typed rather than context that Codex added. Codex
	 * labels each content item's kind; a user's item is labelled {@code user.*} or {@code unknown}, or not at all in
	 * older rollouts, in which case the text's start shows whether Codex added it.
	 *
	 * @param message the message payload
	 * @return {@code true} if the user typed it
	 */
	private static boolean isTyped(JsonNode message)
	{
		JsonNode kinds = message.path("internal_chat_message_metadata_passthrough").path("content_item_kinds");
		if (kinds.isArray() && !kinds.isEmpty())
		{
			boolean userKind = false;
			for (JsonNode kind : kinds)
			{
				String value = kind.asString("");
				if (value.startsWith("user.") || value.equals("unknown") || value.isEmpty())
					userKind = true;
			}
			if (!userKind)
				return false;
		}
		String text = text(message).stripLeading();
		if (text.isEmpty())
			return false;
		for (String prefix : INJECTED_TEXT_PREFIXES)
		{
			if (text.startsWith(prefix))
				return false;
		}
		return true;
	}

	/**
	 * Returns a message's text: its text content items joined by line breaks, as Codex joins them.
	 *
	 * @param message the message payload
	 * @return the text
	 */
	private static String text(JsonNode message)
	{
		StringJoiner text = new StringJoiner("\n");
		for (JsonNode item : message.path("content"))
		{
			String value = item.path("text").asString("");
			if (!value.isEmpty())
				text.add(value);
		}
		return text.toString();
	}

	/**
	 * Returns the turn that a message belongs to.
	 *
	 * @param record the rollout record
	 * @param currentTurn the turn that the latest {@code task_started} event began
	 * @return the message's turn ID, or {@code currentTurn} if the message names none
	 */
	private static String turnId(JsonNode record, String currentTurn)
	{
		String turnId = record.path("payload").path("internal_chat_message_metadata_passthrough").path("turn_id").
			asString("");
		if (turnId.isEmpty())
			turnId = record.path("metadata").path("retained_source").path("id").path("turn_id").asString("");
		if (turnId.isEmpty())
			return currentTurn;
		return turnId;
	}

	/**
	 * Returns a record's time.
	 *
	 * @param record the rollout record
	 * @return its {@code timestamp}, or {@code null} if it has none or the value is not an ISO 8601 instant
	 */
	private static Instant timestamp(JsonNode record)
	{
		String value = record.path("timestamp").asString("");
		if (value.isEmpty())
			return null;
		try
		{
			return Instant.parse(value);
		}
		catch (DateTimeParseException _)
		{
			return null;
		}
	}

	/**
	 * Formats a time in UTC to the second.
	 *
	 * @param time the time, or {@code null}
	 * @return the ISO 8601 time, or {@code an unknown time} if {@code time} is null
	 */
	private static String format(Instant time)
	{
		if (time == null)
			return "an unknown time";
		return time.truncatedTo(ChronoUnit.SECONDS).toString();
	}

	/**
	 * Quotes the start of a text on one line.
	 *
	 * @param text the text
	 * @return the first {@value #EXCERPT_LENGTH} characters of the text, with each run of whitespace replaced by one
	 *   space, in double quotes, followed by an ellipsis if the text is longer
	 */
	private static String quote(String text)
	{
		String line = text.strip().replaceAll("\\s+", " ");
		if (line.length() <= EXCERPT_LENGTH)
			return "\"" + line + "\"";
		return "\"" + line.substring(0, EXCERPT_LENGTH) + "…\"";
	}

	/**
	 * A typed user message.
	 *
	 * @param position the rollout line that records it
	 * @param time when it was sent, or {@code null} if unknown
	 * @param text its text
	 * @param turnId the turn it belongs to
	 */
	private record UserMessage(long position, Instant time, String text, String turnId)
	{
	}

	/**
	 * A final answer.
	 *
	 * @param position the rollout line that records it
	 * @param time when it was given, or {@code null} if unknown
	 * @param text its text
	 */
	private record Answer(long position, Instant time, String text)
	{
	}

	/**
	 * The end of a turn.
	 *
	 * @param position the rollout line that records it
	 * @param time when the turn ended, or {@code null} if unknown
	 */
	private record Event(long position, Instant time)
	{
	}

	/**
	 * A compaction.
	 *
	 * @param position the rollout line that records it
	 * @param time when it happened, or {@code null} if unknown
	 * @param keptTexts the texts of the typed user messages that it kept, in history order
	 */
	private record Compaction(long position, Instant time, List<String> keptTexts)
	{
	}

	/**
	 * The answers and ends that the rollout records for one turn.
	 */
	private static final class TurnOutcome
	{
		private final List<Answer> finalAnswers = new ArrayList<>();
		private Answer lastAgentMessage;
		private Event completed;
		private Event aborted;
		private Event superseded;

		/**
		 * Returns the turn's last final answer after a message.
		 *
		 * @param position the message's rollout line
		 * @return the last final answer after it, the last agent message that the turn's completion records if the turn
		 *   has no final answer after it, or {@code null} if neither exists
		 */
		Answer answerAfter(long position)
		{
			Answer answer = null;
			for (Answer candidate : finalAnswers)
			{
				if (candidate.position() > position)
					answer = candidate;
			}
			if (answer == null && lastAgentMessage != null && lastAgentMessage.position() > position)
				return lastAgentMessage;
			return answer;
		}

		/**
		 * Returns a turn end if it follows a message.
		 *
		 * @param end the turn end, or {@code null}
		 * @param position the message's rollout line
		 * @return {@code end} if it follows the message, otherwise {@code null}
		 */
		Event endAfter(Event end, long position)
		{
			if (end == null || end.position() <= position)
				return null;
			return end;
		}
	}

	/**
	 * Collects the records of one or more rollouts of one thread's history, oldest rollout first.
	 */
	private static final class Reading
	{
		private final List<UserMessage> userMessages = new ArrayList<>();
		private final Map<String, TurnOutcome> outcomesByTurn = new HashMap<>();
		private Compaction latestCompaction;
		private String currentTurn = "";
		/**
		 * The number of lines read from every rollout so far, which orders the records of the whole history.
		 */
		private long position;

		/**
		 * Reads the records of one rollout of the history, after those of the rollouts read before it.
		 *
		 * @param json the JSON mapper
		 * @param segment the rollout, and the ordinal before which its records belong to the history
		 * @throws IOException if the rollout cannot be read, or a line that holds one of those records is not JSON
		 */
		void read(JsonMapper json, RolloutLineage.Segment segment) throws IOException
		{
			Path rollout = segment.rollout();
			long lineNumber = 0;
			try (BufferedReader reader = Files.newBufferedReader(rollout, UTF_8))
			{
				for (String line = reader.readLine(); line != null; line = reader.readLine())
				{
					++lineNumber;
					++position;
					if (!holdsReadRecord(line))
						continue;
					JsonNode record;
					try
					{
						record = json.readTree(line);
					}
					catch (JacksonException e)
					{
						throw new IOException("Line " + lineNumber + " of " + rollout + " is not JSON: " + e.getMessage(), e);
					}
					// Ordinals only grow, so the first record at or past the end ends the inherited part of the rollout
					JsonNode ordinal = record.path("ordinal");
					if (ordinal.isNumber() && ordinal.asLong(0) >= segment.endOrdinalExclusive())
						break;
					add(record);
				}
			}
		}

		/**
		 * Adds the user message, answer, turn end or compaction that a record holds, at the current position.
		 *
		 * @param record the rollout record
		 */
		private void add(JsonNode record)
		{
			Instant time = timestamp(record);
			JsonNode payload = record.path("payload");
			String recordType = record.path("type").asString("");
			String payloadType = payload.path("type").asString("");
			if (recordType.equals("compacted"))
			{
				latestCompaction = new Compaction(position, time, keptTexts(payload.path("replacement_history")));
				return;
			}
			if (recordType.equals("event_msg"))
			{
				String turnId = payload.path("turn_id").asString("");
				switch (payloadType)
				{
					case "task_started" ->
					{
						if (!currentTurn.isEmpty() && !turnId.isEmpty() && !turnId.equals(currentTurn))
						{
							TurnOutcome previous = outcomesByTurn.computeIfAbsent(currentTurn, _ -> new TurnOutcome());
							if (previous.superseded == null)
								previous.superseded = new Event(position, time);
						}
						currentTurn = turnId;
					}
					case "task_complete" ->
					{
						TurnOutcome outcome = outcomesByTurn.computeIfAbsent(turnId, _ -> new TurnOutcome());
						outcome.completed = new Event(position, time);
						String lastMessage = payload.path("last_agent_message").asString("");
						if (!lastMessage.isBlank())
							outcome.lastAgentMessage = new Answer(position, time, lastMessage);
					}
					case "turn_aborted" -> outcomesByTurn.computeIfAbsent(turnId, _ -> new TurnOutcome()).
						aborted = new Event(position, time);
					default ->
					{
					}
				}
				return;
			}
			if (!recordType.equals("response_item") || !payloadType.equals("message"))
				return;
			String turnId = turnId(record, currentTurn);
			String role = payload.path("role").asString("");
			if (role.equals("user") && isTyped(payload))
				userMessages.add(new UserMessage(position, time, text(payload), turnId));
			else if (role.equals("assistant") && payload.path("phase").asString("").equals("final_answer"))
			{
				outcomesByTurn.computeIfAbsent(turnId, _ -> new TurnOutcome()).finalAnswers.
					add(new Answer(position, time, text(payload)));
			}
			else if (role.equals("developer") && text(payload).stripLeading().startsWith(ABORT_NOTICE_PREFIX))
			{
				TurnOutcome outcome = outcomesByTurn.computeIfAbsent(turnId, _ -> new TurnOutcome());
				if (outcome.aborted == null)
					outcome.aborted = new Event(position, time);
			}
		}
	}
}
