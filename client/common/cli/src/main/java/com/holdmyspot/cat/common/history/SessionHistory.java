package com.holdmyspot.cat.common.history;

import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.secrets.SessionHistoryAccess;
import com.holdmyspot.cat.common.secrets.SessionHistorySecrets;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * Reads one explicitly selected JSONL session transcript without discovering or modifying session state.
 */
public final class SessionHistory
{
	static
	{
		SessionHistorySecrets.setAccess(new SessionHistoryAccess()
		{
			@Override
			public String execute(String[] args) throws IOException
			{
				return SessionHistory.execute(args);
			}

			@Override
			public String search(Path transcript, String phrase, int context) throws IOException
			{
				return SessionHistory.search(transcript, phrase, context);
			}
		});
	}

	private static final int DEFAULT_SUMMARY_RECORD_LIMIT = 1_000;

	/**
	 * Selects the bounded event metadata included in a history summary.
	 *
	 * @param since the inclusive earliest timestamp, or {@code null} when no lower time bound applies
	 * @param until the inclusive latest timestamp, or {@code null} when no upper time bound applies
	 * @param type the required event type, or {@code null} when all types apply
	 * @param limit the maximum selected records to summarize
	 */
	private record SummaryFilter(Instant since, Instant until, String type, int limit)
	{
	}

	/**
	 * Prevents instantiation.
	 */
	private SessionHistory()
	{
	}

	/**
	 * Runs a bounded session-history query.
	 *
	 * @param args the operation, transcript path, and operation-specific arguments
	 */
	public static void main(String[] args)
	{
		args = CommandLineArguments.recover(args);
		try
		{
			System.out.print(execute(args));
		}
		catch (IllegalArgumentException | IOException e)
		{
			System.err.println(e.getMessage());
			System.exit(1);
		}
	}

	/**
	 * Executes one session-history operation.
	 *
	 * @param args the operation, transcript path, and operation-specific arguments
	 * @return the deterministic text result
	 * @throws IOException if the transcript cannot be read or contains invalid JSONL
	 */
	static String execute(String[] args) throws IOException
	{
		if (args.length < 2)
			throw new IllegalArgumentException(usage());

		String operation = args[0];
		Path transcript = transcript(Path.of(args[1]));
		return execute(operation, transcript, Arrays.copyOfRange(args, 2, args.length));
	}

	/**
	 * Executes a session-history operation for a transcript selected by a harness-specific caller.
	 *
	 * @param transcript the selected transcript path
	 * @param args the operation and its operation-specific arguments
	 * @return the deterministic text result
	 * @throws IOException if the transcript cannot be read or contains invalid JSONL
	 */
	public static String execute(Path transcript, String[] args) throws IOException
	{
		if (args.length < 1)
			throw new IllegalArgumentException(usage());
		return execute(args[0], transcript(transcript), Arrays.copyOfRange(args, 1, args.length));
	}

	/**
	 * Executes one parsed session-history operation.
	 *
	 * @param operation the requested operation
	 * @param transcript the selected transcript
	 * @param arguments operation-specific arguments
	 * @return the deterministic text result
	 * @throws IOException if the transcript cannot be read or contains invalid JSONL
	 */
	private static String execute(String operation, Path transcript, String[] arguments) throws IOException
	{
		return switch (operation)
		{
			case "analyze" -> analyze(transcript);
			case "summary" -> summary(transcript, summaryFilter(arguments));
			case "errors" -> matching(transcript, "error", 0);
			case "file-history" -> requiredMatching(transcript, arguments, 0, 0);
			case "search" -> searchArguments(transcript, arguments);
			default -> throw new IllegalArgumentException(usage());
		};
	}

	/**
	 * Reports bounded event counts and timestamps without exposing transcript text.
	 *
	 * @param transcript the existing JSONL transcript to summarize
	 * @param filter the selected event metadata bounds
	 * @return counts, event types, and the selected timestamp range
	 * @throws IOException if the transcript cannot be read or contains invalid JSONL
	 */
	private static String summary(Path transcript, SummaryFilter filter) throws IOException
	{
		int selected = 0;
		Instant firstTimestamp = null;
		Instant lastTimestamp = null;
		boolean truncated = false;
		Map<String, Integer> types = new TreeMap<>();
		try (ToolScope scope = new ToolScope();
			Stream<String> lines = Files.lines(transcript))
		{
			Iterator<String> iterator = lines.iterator();
			while (iterator.hasNext())
			{
				String record = iterator.next();
				if (record.isBlank())
					continue;
				JsonNode node = scope.json().readTree(record);
				if (node == null || !node.isObject())
					throw new IOException("Session transcript record is not a JSON object: " + transcript);
				String type = node.path("type").asString("");
				Instant timestamp = timestamp(node, transcript, filter);
				if (!matchesSummaryFilter(type, timestamp, filter))
					continue;
				if (selected == filter.limit())
				{
					truncated = true;
					break;
				}
				++selected;
				if (type.isBlank())
					type = "untyped";
				types.merge(type, 1, Integer::sum);
				if (timestamp != null)
				{
					if (firstTimestamp == null)
						firstTimestamp = timestamp;
					lastTimestamp = timestamp;
				}
			}
		}
		StringJoiner typeCounts = new StringJoiner(",");
		for (Map.Entry<String, Integer> entry : types.entrySet())
			typeCounts.add(entry.getKey() + ":" + entry.getValue());
		StringJoiner result = new StringJoiner("\n", "", "\n");
		result.add("records=" + selected);
		result.add("types=" + typeCounts);
		result.add("truncated=" + truncated);
		if (firstTimestamp != null)
		{
			result.add("first_timestamp=" + firstTimestamp);
			result.add("last_timestamp=" + lastTimestamp);
		}
		return result.toString();
	}

	/**
	 * Parses the time, event-type, and result-size boundaries for a history summary.
	 *
	 * @param args the summary-only options
	 * @return the validated filter
	 */
	private static SummaryFilter summaryFilter(String[] args)
	{
		Instant since = null;
		Instant until = null;
		String type = null;
		int limit = DEFAULT_SUMMARY_RECORD_LIMIT;
		boolean limitSet = false;
		for (int index = 0; index < args.length; index += 2)
		{
			if (index + 1 == args.length)
				throw new IllegalArgumentException(usage());
			String option = args[index];
			String value = args[index + 1];
			switch (option)
			{
				case "--since" ->
				{
					if (since != null)
						throw new IllegalArgumentException("Summary time lower bound is repeated");
					since = instant(value, "Summary time lower bound");
				}
				case "--until" ->
				{
					if (until != null)
						throw new IllegalArgumentException("Summary time upper bound is repeated");
					until = instant(value, "Summary time upper bound");
				}
				case "--type" ->
				{
					if (type != null || value.isBlank())
						throw new IllegalArgumentException("Summary event type must be specified once and be non-blank");
					type = value;
				}
				case "--limit" ->
				{
					if (limitSet)
						throw new IllegalArgumentException("Summary record limit is repeated");
					limit = positiveInteger(value, "Summary record limit");
					limitSet = true;
				}
				default -> throw new IllegalArgumentException(usage());
			}
		}
		if (since != null && until != null && since.isAfter(until))
			throw new IllegalArgumentException("Summary time lower bound must not be after its upper bound");
		return new SummaryFilter(since, until, type, limit);
	}

	/**
	 * Parses one required positive integer option value.
	 *
	 * @param value the supplied value
	 * @param description the user-visible option purpose
	 * @return the parsed positive value
	 */
	private static int positiveInteger(String value, String description)
	{
		try
		{
			int result = Integer.parseInt(value);
			if (result < 1)
				throw new IllegalArgumentException(description + " must be a positive integer");
			return result;
		}
		catch (NumberFormatException e)
		{
			throw new IllegalArgumentException(description + " must be a positive integer", e);
		}
	}

	/**
	 * Parses one ISO-8601 timestamp option value.
	 *
	 * @param value the supplied timestamp
	 * @param description the user-visible option purpose
	 * @return the parsed instant
	 */
	private static Instant instant(String value, String description)
	{
		try
		{
			return Instant.parse(value);
		}
		catch (DateTimeParseException e)
		{
			throw new IllegalArgumentException(description + " must be an ISO-8601 instant", e);
		}
	}

	/**
	 * Reads one record timestamp when the selected filter needs it.
	 *
	 * @param node the parsed JSON record
	 * @param transcript the source transcript
	 * @param filter the requested bounds
	 * @return the record timestamp, or {@code null} when time filtering is not requested and none is present
	 * @throws IOException if a requested time-bound record lacks a valid timestamp
	 */
	private static Instant timestamp(JsonNode node, Path transcript, SummaryFilter filter) throws IOException
	{
		JsonNode value = node.path("timestamp");
		if (value.isMissingNode() || !value.isString() || value.asString().isBlank())
		{
			if (filter.since() != null || filter.until() != null)
				throw new IOException("Time-bounded history summary found a record without timestamp: " + transcript);
			return null;
		}
		try
		{
			return Instant.parse(value.asString());
		}
		catch (DateTimeParseException e)
		{
			throw new IOException("History summary record has an invalid timestamp: " + transcript, e);
		}
	}

	/**
	 * Indicates whether one parsed event falls within the requested summary boundaries.
	 *
	 * @param type the event type from the record
	 * @param timestamp the parsed event timestamp, if present
	 * @param filter the requested boundaries
	 * @return whether the event belongs in the summary
	 */
	private static boolean matchesSummaryFilter(String type, Instant timestamp, SummaryFilter filter)
	{
		if (filter.type() != null && !filter.type().equals(type))
			return false;
		if (filter.since() != null && timestamp.isBefore(filter.since()))
			return false;
		return filter.until() == null || !timestamp.isAfter(filter.until());
	}

	/**
	 * Summarizes the valid JSON records in a transcript.
	 *
	 * @param transcript the existing JSONL transcript to summarize
	 * @return the record count and encountered event types
	 * @throws IOException if the transcript cannot be parsed
	 */
	static String analyze(Path transcript) throws IOException
	{
		List<String> records = records(transcript);
		List<String> types = new ArrayList<>();
		try (ToolScope scope = new ToolScope())
		{
			for (String record : records)
			{
				JsonNode node = scope.json().readTree(record);
				String type = node.path("type").asString("");
				if (!type.isBlank() && !types.contains(type))
					types.add(type);
			}
		}
		return "records=" + records.size() + "\ntypes=" + String.join(",", types) + "\n";
	}

	/**
	 * Finds records containing a phrase and adjacent records.
	 *
	 * @param transcript the existing JSONL transcript to search
	 * @param phrase the case-insensitive phrase to find
	 * @param context the number of adjacent records retained on each side
	 * @return the matching JSONL records in source order
	 * @throws IOException if the transcript cannot be read or parsed
	 */
	static String search(Path transcript, String phrase, int context) throws IOException
	{
		if (phrase.isBlank() || context < 0)
			throw new IllegalArgumentException("Search phrase must be non-blank and context must be non-negative");
		return matching(transcript, phrase, context);
	}

	/**
	 * Parses the optional search-context argument and runs the search.
	 *
	 * @param transcript the existing JSONL transcript to search
	 * @param args the complete command arguments
	 * @return the matching records in source order
	 * @throws IOException if the transcript cannot be read or parsed
	 */
	private static String searchArguments(Path transcript, String[] args) throws IOException
	{
		if (args.length != 1 && args.length != 3)
			throw new IllegalArgumentException(usage());
		int context = 0;
		if (args.length == 3)
		{
			if (!"--context".equals(args[1]))
				throw new IllegalArgumentException(usage());
			try
			{
				context = Integer.parseInt(args[2]);
			}
			catch (NumberFormatException e)
			{
				throw new IllegalArgumentException("Context must be a non-negative integer", e);
			}
		}
		return search(transcript, args[0], context);
	}

	/**
	 * Applies an operation whose only argument is a required search phrase.
	 *
	 * @param transcript the existing JSONL transcript to search
	 * @param args the complete command arguments
	 * @param valueIndex the position of the required phrase
	 * @param context the neighboring records to include
	 * @return the matching records
	 * @throws IOException if the transcript cannot be read or parsed
	 */
	private static String requiredMatching(Path transcript, String[] args, int valueIndex, int context)
		throws IOException
	{
		if (args.length != valueIndex + 1)
			throw new IllegalArgumentException(usage());
		return search(transcript, args[valueIndex], context);
	}

	/**
	 * Verifies that a command-selected transcript is a regular file.
	 *
	 * @param candidate the caller-selected transcript path
	 * @return the normalized regular-file path
	 */
	private static Path transcript(Path candidate)
	{
		Path normalized = candidate.toAbsolutePath().normalize();
		if (!Files.isRegularFile(normalized))
			throw new IllegalArgumentException("Session transcript is not a regular file: " + normalized);
		return normalized;
	}

	/**
	 * Selects matching records and their requested neighboring records.
	 *
	 * @param transcript the existing JSONL transcript to search
	 * @param phrase the case-insensitive phrase to find
	 * @param context the number of adjacent records retained on each side
	 * @return the matching records in source order
	 * @throws IOException if the transcript cannot be read or parsed
	 */
	private static String matching(Path transcript, String phrase, int context) throws IOException
	{
		List<String> records = records(transcript);
		String needle = phrase.toLowerCase(Locale.ROOT);
		boolean[] selected = new boolean[records.size()];
		for (int index = 0; index < records.size(); ++index)
		{
			if (!records.get(index).toLowerCase(Locale.ROOT).contains(needle))
				continue;
			for (int adjacent = Math.max(0, index - context); adjacent <= Math.min(records.size() - 1,
				index + context); ++adjacent)
				selected[adjacent] = true;
		}
		StringBuilder result = new StringBuilder();
		for (int index = 0; index < records.size(); ++index)
		{
			if (selected[index])
				result.append(records.get(index)).append('\n');
		}
		return result.toString();
	}

	/**
	 * Reads and validates every non-blank JSONL record in a transcript.
	 *
	 * @param transcript the existing JSONL transcript to read
	 * @return the non-blank records in source order
	 * @throws IOException if the transcript cannot be read or parsed
	 */
	private static List<String> records(Path transcript) throws IOException
	{
		List<String> result = Files.readAllLines(transcript).stream().filter(line -> !line.isBlank()).toList();
		try (ToolScope scope = new ToolScope())
		{
			for (String record : result)
				scope.json().readTree(record);
		}
		return result;
	}

	/**
	 * Returns the supported command forms.
	 *
	 * @return the command usage text
	 */
	private static String usage()
	{
		return "Usage: session-analyzer analyze <transcript.jsonl>\n" +
			"       session-analyzer summary <transcript.jsonl> [--since <ISO-8601>] [--until <ISO-8601>] " +
			"[--type <event-type>] [--limit <count>]\n" +
			"       session-analyzer search <transcript.jsonl> <phrase> [--context N]\n" +
			"       session-analyzer errors <transcript.jsonl>\n" +
			"       session-analyzer file-history <transcript.jsonl> <path-fragment>";
	}
}
