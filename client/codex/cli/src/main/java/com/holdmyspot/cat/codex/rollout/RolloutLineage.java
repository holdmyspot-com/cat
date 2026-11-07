package com.holdmyspot.cat.codex.rollout;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds the rollouts that together record a Codex thread's history.
 * <p>
 * When a thread is forked, Codex writes the fork's rollout with only the records that the fork itself produced. The
 * rollout's first record, {@code session_meta}, holds a {@code history_base} that names the rollout that the fork
 * inherited its earlier history from, and the {@code end_ordinal_exclusive} before which the fork inherited that
 * rollout's records. Each record of a paginated rollout carries an {@code ordinal} that numbers it within the thread's
 * whole history, so the records of a fork begin at the ordinal where its parent's inherited records end. A parent can
 * itself be a fork, and so on to a rollout with no {@code history_base}.
 * <p>
 * Codex keeps a rollout in {@code sessions/}, in a folder for the date it started, or in {@code archived_sessions/}
 * once its thread is archived, in a file named {@code rollout-<time>-<id>.jsonl}. The {@code id} is the rollout's own.
 * For a thread that was reverted it is the thread's identifier, an underscore and the rollout's identifier, and a
 * {@code history_base} refers to the rollout's. A rollout that Codex has compressed into a {@code .jsonl.zst} file is
 * not read.
 */
public final class RolloutLineage
{
	/**
	 * An end, as an ordinal or a byte offset, that nothing reaches: the thread's own rollout is left out of nothing,
	 * and Codex may record no byte offset for an ancestor.
	 */
	private static final long NO_END = Long.MAX_VALUE;

	/**
	 * The records of one rollout that belong to a thread's history.
	 *
	 * @param rollout the rollout file
	 * @param endOrdinalExclusive the ordinal of the first record of the rollout that the thread does not include, or
	 *   {@link Long#MAX_VALUE} when it includes every record
	 * @param endByteOffset the offset in the rollout just after the last record that the thread includes, or
	 *   {@link Long#MAX_VALUE} when it includes every record or Codex recorded no offset
	 */
	public record Segment(Path rollout, long endOrdinalExclusive, long endByteOffset)
	{
		/**
		 * Indicates whether a record lies beyond the part of the rollout that belongs to the thread's history. Ordinals
		 * only grow, so the first record that is beyond it ends that part.
		 *
		 * @param record the rollout's record
		 * @return {@code true} if the record has an {@code ordinal} that is not before {@code endOrdinalExclusive}
		 * @throws NullPointerException if {@code record} is null
		 */
		public boolean excludes(JsonNode record)
		{
			JsonNode ordinal = record.path("ordinal");
			return ordinal.isNumber() && ordinal.asLong(0) >= endOrdinalExclusive;
		}
	}

	/**
	 * Where a fork's history begins in its parent: the {@code history_base} of a rollout.
	 *
	 * @param rolloutId the identifier of the parent rollout
	 * @param endOrdinalExclusive the ordinal of the first record of the parent that the fork does not include
	 * @param endByteOffset the offset in the parent just after the last record that the fork includes, or
	 *   {@link Long#MAX_VALUE} if Codex recorded none
	 */
	private record HistoryBase(String rolloutId, long endOrdinalExclusive, long endByteOffset)
	{
	}

	/**
	 * Prevents instantiation.
	 */
	private RolloutLineage()
	{
	}

	/**
	 * Lists the rollouts that hold a thread's history, oldest first. An ancestor whose rollout cannot be found, or whose
	 * first record cannot be read, ends the list at its end: the rollouts that it inherited from are left out, so a
	 * caller reads what exists.
	 *
	 * @param json the JSON mapper
	 * @param rollout the thread's own rollout file, which is always the last segment
	 * @return one segment for the rollout and for each ancestor that could be read, ending with the rollout's own
	 * @throws NullPointerException if {@code json} or {@code rollout} are null
	 * @throws IOException          if the thread's own rollout cannot be read
	 */
	public static List<Segment> resolve(JsonMapper json, Path rollout) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(rollout, "rollout").isNotNull();
		List<Segment> newestFirst = new ArrayList<>();
		newestFirst.add(new Segment(rollout, NO_END, NO_END));
		Set<Path> visited = new HashSet<>();
		visited.add(rollout.toAbsolutePath().normalize());
		Path child = rollout;
		HistoryBase base = readHistoryBase(json, rollout);
		try
		{
			while (base != null)
			{
				Path parent = findRollout(child, base.rolloutId());
				// A rollout that names itself or a descendant as its parent is malformed; stop rather than loop
				if (parent == null || !visited.add(parent.toAbsolutePath().normalize()))
					break;
				newestFirst.add(new Segment(parent, base.endOrdinalExclusive(), base.endByteOffset()));
				child = parent;
				base = readHistoryBase(json, parent);
			}
		}
		catch (IOException | UncheckedIOException _)
		{
			// The ancestors that could be read still supply their messages; the rest report as not found
		}
		Collections.reverse(newestFirst);
		return newestFirst;
	}

	/**
	 * Returns the records of a thread's whole history, one per line, oldest first: those of each ancestor rollout up to
	 * where its descendant inherited them, then the thread's own.
	 * <p>
	 * An ancestor that cannot be found or read is left out, as {@link #resolve} leaves it, and one that fails midway
	 * supplies the records read before the failure. A line of an ancestor rollout that is not JSON, or has no
	 * {@code ordinal}, is passed on.
	 *
	 * @param json the JSON mapper
	 * @param rollout the thread's own rollout file
	 * @return the lines. The caller closes the stream. A failure to read the thread's own rollout while the stream is
	 *   consumed is thrown as an {@link UncheckedIOException}.
	 * @throws NullPointerException if {@code json} or {@code rollout} are null
	 * @throws IOException          if the thread's own rollout cannot be read
	 */
	public static Stream<String> lines(JsonMapper json, Path rollout) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(rollout, "rollout").isNotNull();
		HistoryLines history = new HistoryLines(json, resolve(json, rollout));
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(history, Spliterator.ORDERED), false).
			onClose(history::close);
	}

	/**
	 * Reads the lines of a thread's history one at a time, opening one rollout at a time.
	 */
	private static final class HistoryLines implements Iterator<String>
	{
		private final JsonMapper json;
		private final Iterator<Segment> remaining;
		private Segment current;
		private BufferedReader reader;
		private String next;

		/**
		 * Creates the lines of a history.
		 *
		 * @param json the JSON mapper
		 * @param segments the rollouts that hold the history, oldest first, ending with the thread's own
		 */
		HistoryLines(JsonMapper json, List<Segment> segments)
		{
			this.json = json;
			this.remaining = segments.iterator();
		}

		@Override
		public boolean hasNext()
		{
			while (next == null)
			{
				if (reader == null && !openNext())
					return false;
				String line;
				try
				{
					line = reader.readLine();
				}
				catch (IOException e)
				{
					endSegment(e);
					continue;
				}
				if (line == null || isPastEnd(line))
					close();
				else
					next = line;
			}
			return true;
		}

		@Override
		public String next()
		{
			if (!hasNext())
				throw new NoSuchElementException();
			String line = next;
			next = null;
			return line;
		}

		/**
		 * Opens the next rollout of the history.
		 *
		 * @return {@code false} if every rollout has been read
		 */
		private boolean openNext()
		{
			while (remaining.hasNext())
			{
				current = remaining.next();
				try
				{
					reader = Files.newBufferedReader(current.rollout(), UTF_8);
					return true;
				}
				catch (IOException e)
				{
					endSegment(e);
				}
			}
			return false;
		}

		/**
		 * Ends the rollout that failed. An ancestor's failure leaves out the rest of that ancestor.
		 *
		 * @param failure the failure
		 * @throws UncheckedIOException if the failed rollout is the thread's own, which is the last
		 */
		private void endSegment(IOException failure)
		{
			if (!remaining.hasNext())
				throw new UncheckedIOException(failure);
			close();
		}

		/**
		 * Indicates whether a line lies beyond the part of the current rollout that belongs to the history.
		 *
		 * @param line the line
		 * @return {@code true} if the line's {@code ordinal} is not before the end of that part
		 */
		private boolean isPastEnd(String line)
		{
			if (!line.contains("\"ordinal\""))
				return false;
			try
			{
				return current.excludes(json.readTree(line));
			}
			catch (JacksonException _)
			{
				return false;
			}
		}

		/**
		 * Closes the rollout that is being read.
		 */
		void close()
		{
			next = null;
			if (reader == null)
				return;
			try
			{
				reader.close();
			}
			catch (IOException _)
			{
				// Everything that the rollout holds was read or is being left out
			}
			finally
			{
				reader = null;
			}
		}
	}

	/**
	 * Reads the {@code history_base} that a rollout's first record holds.
	 *
	 * @param json the JSON mapper
	 * @param rollout the rollout file
	 * @return the {@code history_base}, or {@code null} if the first record is not a {@code session_meta} that holds a
	 *   complete one, as for a thread that is not a fork
	 * @throws IOException if the rollout cannot be read
	 */
	private static HistoryBase readHistoryBase(JsonMapper json, Path rollout) throws IOException
	{
		String firstRecord;
		try (BufferedReader reader = Files.newBufferedReader(rollout, UTF_8))
		{
			firstRecord = reader.readLine();
		}
		if (firstRecord == null || !firstRecord.contains("\"history_base\""))
			return null;
		JsonNode base;
		try
		{
			base = json.readTree(firstRecord).path("payload").path("history_base");
		}
		catch (JacksonException _)
		{
			return null;
		}
		String rolloutId = base.path("thread_id").asString("");
		JsonNode end = base.path("end_ordinal_exclusive");
		if (rolloutId.isEmpty() || !end.isNumber())
			return null;
		// Without an offset, a reader that bounds its read by bytes counts them back from the parent's end
		long endByteOffset = base.path("end_byte_offset").asLong(NO_END);
		return new HistoryBase(rolloutId, end.asLong(0), endByteOffset);
	}

	/**
	 * Finds the file of a rollout in the Codex home that holds another rollout.
	 *
	 * @param sibling a rollout file under {@code sessions/} or {@code archived_sessions/}, which locates the Codex home
	 * @param rolloutId the identifier of the rollout to find
	 * @return the rollout file, preferring {@code sessions/} over {@code archived_sessions/}, or {@code null} if
	 *   {@code sibling} is under neither or no file there is named for {@code rolloutId}
	 * @throws IOException if a directory cannot be listed
	 */
	private static Path findRollout(Path sibling, String rolloutId) throws IOException
	{
		Path home = codexHome(sibling.toAbsolutePath());
		if (home == null)
			return null;
		for (String directory : List.of("sessions", "archived_sessions"))
		{
			Path root = home.resolve(directory);
			if (!Files.isDirectory(root))
				continue;
			try (Stream<Path> files = Files.walk(root))
			{
				Path found = files.filter(Files::isRegularFile).
					filter(file -> isRolloutFile(file.getFileName().toString(), rolloutId)).
					findFirst().orElse(null);
				if (found != null)
					return found;
			}
		}
		return null;
	}

	/**
	 * Returns the Codex home that holds a rollout.
	 *
	 * @param rollout the rollout file
	 * @return the parent of the nearest enclosing {@code sessions} or {@code archived_sessions} directory, or
	 *   {@code null} if the rollout is under neither
	 */
	private static Path codexHome(Path rollout)
	{
		for (Path directory = rollout.getParent(); directory != null; directory = directory.getParent())
		{
			Path name = directory.getFileName();
			if (name == null)
				continue;
			String directoryName = name.toString();
			if (directoryName.equals("sessions") || directoryName.equals("archived_sessions"))
				return directory.getParent();
		}
		return null;
	}

	/**
	 * Indicates whether a file name is that of a given rollout's log.
	 *
	 * @param fileName the file name
	 * @param rolloutId the identifier of the rollout
	 * @return {@code true} if the name is {@code rollout-<time>-<id>.jsonl}, where {@code <id>} is the rollout's
	 *   identifier or ends with an underscore and that identifier
	 */
	private static boolean isRolloutFile(String fileName, String rolloutId)
	{
		boolean isRollout = fileName.startsWith("rollout-");
		boolean isOrdinaryName = fileName.endsWith("-" + rolloutId + ".jsonl");
		boolean isRevertedName = fileName.endsWith("_" + rolloutId + ".jsonl");
		return isRollout && (isOrdinaryName || isRevertedName);
	}
}
