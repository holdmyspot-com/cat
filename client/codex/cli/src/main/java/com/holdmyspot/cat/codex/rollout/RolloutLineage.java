package com.holdmyspot.cat.codex.rollout;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.history.TranscriptLines.Line;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
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
	 * @param lineNumber the physical line that declares the base
	 */
	private record HistoryBase(String rolloutId, long endOrdinalExclusive, long endByteOffset, long lineNumber)
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
	 * Resolves every rollout required to reconstruct a thread's history, refusing an incomplete or invalid lineage.
	 *
	 * @param json the JSON mapper
	 * @param rollout the thread's own rollout file
	 * @return every segment, oldest first, ending with the thread's own rollout
	 * @throws NullPointerException if {@code json} or {@code rollout} are null
	 * @throws IOException if any required rollout or lineage record is missing, unreadable, corrupt, or cyclic
	 */
	public static List<Segment> resolveRequired(JsonMapper json, Path rollout) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(rollout, "rollout").isNotNull();
		List<Segment> newestFirst = new ArrayList<>();
		newestFirst.add(new Segment(rollout, NO_END, NO_END));
		Set<Path> visited = new HashSet<>();
		visited.add(rollout.toAbsolutePath().normalize());
		Path child = rollout;
		HistoryBase base = readHistoryBaseRequired(json, rollout);
		while (base != null)
		{
			Path parent;
			try
			{
				parent = findRollout(child, base.rolloutId());
			}
			catch (IOException e)
			{
				throw new IOException(child + ":" + base.lineNumber() + ": cannot locate required parent " +
					base.rolloutId(), e);
			}
			if (parent == null)
				throw new IOException(child + ":" + base.lineNumber() + ": required parent rollout " +
					base.rolloutId() + " was not found");
			if (base.endByteOffset() != NO_END)
			{
				long size;
				try
				{
					size = Files.size(parent);
				}
				catch (IOException e)
				{
					throw new IOException(child + ":" + base.lineNumber() + ": cannot inspect required parent " + parent,
						e);
				}
				if (base.endByteOffset() > size)
					throw new IOException(child + ":" + base.lineNumber() + ": parent cutoff exceeds rollout size " + parent,
						new EOFException("expected byte offset " + base.endByteOffset() + " but file has " + size + " bytes"));
			}
			if (!visited.add(parent.toAbsolutePath().normalize()))
				throw new IOException(child + ":" + base.lineNumber() + ": required parent lineage contains a cycle at " +
					parent);
			newestFirst.add(new Segment(parent, base.endOrdinalExclusive(), base.endByteOffset()));
			child = parent;
			base = readHistoryBaseRequired(json, parent);
		}
		Collections.reverse(newestFirst);
		return newestFirst;
	}

	/**
	 * Returns the records of a thread's whole history, oldest first, each with its physical rollout and line number:
	 * those of each ancestor rollout up to
	 * where its descendant inherited them, then the thread's own.
	 * <p>
	 * An ancestor that cannot be found or read is left out, as {@link #resolve} leaves it, and one that fails midway
	 * supplies the records read before the failure. A line of an ancestor rollout that is not JSON, or has no
	 * {@code ordinal}, is passed on.
	 *
	 * @param json the JSON mapper
	 * @param rollout the thread's own rollout file
	 * @return the lines and their physical source locations. The caller closes the stream. A failure to read the thread's
	 *   own rollout while the stream is consumed is thrown as an {@link UncheckedIOException}.
	 * @throws NullPointerException if {@code json} or {@code rollout} are null
	 * @throws IOException          if the thread's own rollout cannot be read
	 */
	public static Stream<Line> lines(JsonMapper json, Path rollout) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(rollout, "rollout").isNotNull();
		HistoryLines history = new HistoryLines(json, resolve(json, rollout));
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(history, Spliterator.ORDERED), false).
				onClose(history::close);
	}

	/**
	 * Reads a complete required history, rejecting malformed included records and unfinished included final lines.
	 * The legacy {@link #lines} operation retains its best-effort partial-history behavior.
	 *
	 * @param json the JSON mapper
	 * @param rollout the thread's own rollout file
	 * @return the complete included records with physical source locations; the caller closes the stream
	 * @throws NullPointerException if {@code json} or {@code rollout} are null
	 * @throws IOException if required lineage metadata cannot be reconstructed
	 */
	public static Stream<Line> linesRequired(JsonMapper json, Path rollout) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(rollout, "rollout").isNotNull();
		RequiredHistoryLines history = new RequiredHistoryLines(json, resolveRequired(json, rollout));
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(history, Spliterator.ORDERED), false).
			onClose(history::close);
	}

	/**
	 * Reads each required rollout from its beginning, checking the byte cutoff before parsing excluded records.
	 */
	private static final class RequiredHistoryLines implements Iterator<Line>
	{
		private final JsonMapper json;
		private final Iterator<Segment> remaining;
		private Segment current;
		private InputStream input;
		private long bytePosition;
		private long lineNumber;
		private Line next;

		/**
		 * Creates a reader for all required segments.
		 *
		 * @param json the mapper used to validate each included record
		 * @param segments the required segments, oldest first
		 */
		RequiredHistoryLines(JsonMapper json, List<Segment> segments)
		{
			this.json = json;
			remaining = segments.iterator();
		}

		@Override
		public boolean hasNext()
		{
			while (next == null)
			{
				if (input == null && !openNext())
					return false;
				try
				{
					String text = readLine();
					if (text == null)
					{
						close();
						continue;
					}
					if (text.isBlank())
						continue;
					JsonNode record;
					try
					{
						record = json.readTree(text);
					}
					catch (JacksonException e)
					{
						throw failure("record is not valid JSON", e);
					}
					if (!record.isObject())
						throw failure("record must be a JSON object", null);
					if (current.excludes(record))
					{
						close();
						continue;
					}
					next = new Line(current.rollout(), lineNumber, text);
				}
				catch (IOException e)
				{
					IOException failure = e;
					if (e.getMessage() == null || !e.getMessage().startsWith(current.rollout().toString() + ":"))
						failure = new IOException(location(lineNumber + 1) + ": cannot read required rollout", e);
					throw new UncheckedIOException(failure);
				}
			}
			return true;
		}

		@Override
		public Line next()
		{
			if (!hasNext())
				throw new NoSuchElementException();
			Line line = next;
			next = null;
			return line;
		}

		/**
		 * Opens the next full segment.
		 *
		 * @return whether a segment was opened
		 */
		private boolean openNext()
		{
			if (!remaining.hasNext())
				return false;
			current = remaining.next();
			lineNumber = 0;
			bytePosition = 0;
			try
			{
				input = new BufferedInputStream(Files.newInputStream(current.rollout()));
				return true;
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(location(1) + ": cannot read required rollout", e);
			}
		}

		/**
		 * Reads one LF-terminated UTF-8 physical line, bounded by the segment's authoritative byte cutoff.
		 *
		 * @return the line, or {@code null} at the included end of the segment
		 * @throws IOException if the included line is unfinished or invalid UTF-8
		 */
		private String readLine() throws IOException
		{
			if (current.endByteOffset() != NO_END && bytePosition >= current.endByteOffset())
				return null;
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			boolean terminated = false;
			while (current.endByteOffset() == NO_END || bytePosition < current.endByteOffset())
			{
				int value = input.read();
				if (value < 0)
					break;
				++bytePosition;
				if (value == '\n')
				{
					terminated = true;
					break;
				}
				bytes.write(value);
			}
			if (bytes.size() == 0 && !terminated)
				return null;
			++lineNumber;
			if (!terminated)
				throw failure("included final record is not LF-terminated", null);
			byte[] content = bytes.toByteArray();
			int length = content.length;
			if (length > 0 && content[length - 1] == '\r')
				--length;
			try
			{
				return UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).
					onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content, 0, length)).toString();
			}
			catch (java.nio.charset.CharacterCodingException e)
			{
				throw failure("included record is not valid UTF-8", e);
			}
		}

		/**
		 * Creates a source-located required-history failure.
		 *
		 * @param reason the failure
		 * @param cause the cause, or {@code null} if none
		 * @return the failure
		 */
		private IOException failure(String reason, Throwable cause)
		{
			String message = location(Math.max(1, lineNumber)) + ": " + reason;
			if (cause == null)
				return new IOException(message);
			return new IOException(message, cause);
		}

		/**
		 * Returns the physical source location.
		 *
		 * @param line the one-based physical line
		 * @return the location
		 */
		private String location(long line)
		{
			return current.rollout() + ":" + line;
		}

		/**
		 * Closes the current segment.
		 */
		void close()
		{
			if (input == null)
				return;
			try
			{
				input.close();
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(location(Math.max(1, lineNumber)) + ": cannot close required rollout", e);
			}
			finally
			{
				input = null;
			}
		}
	}

	/**
	 * Reads the lines of a thread's history one at a time, opening one rollout at a time.
	 */
	private static final class HistoryLines implements Iterator<Line>
	{
		private final JsonMapper json;
		private final Iterator<Segment> remaining;
		private Segment current;
		private BufferedReader reader;
		private long lineNumber;
		private Line next;

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
				if (line == null)
					close();
				else
				{
					++lineNumber;
					if (isPastEnd(line))
						close();
					else
						next = new Line(current.rollout(), lineNumber, line);
				}
			}
			return true;
		}

		@Override
		public Line next()
		{
			if (!hasNext())
				throw new NoSuchElementException();
			Line line = next;
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
				lineNumber = 0;
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
		return new HistoryBase(rolloutId, end.asLong(0), endByteOffset, 1);
	}

	/**
	 * Strictly reads the first nonblank session metadata record of a required rollout.
	 *
	 * @param json the JSON mapper
	 * @param rollout the rollout
	 * @return the inherited history base, or {@code null} when the valid metadata omits it
	 * @throws IOException if the metadata or its present history base is invalid
	 */
	private static HistoryBase readHistoryBaseRequired(JsonMapper json, Path rollout) throws IOException
	{
		try (InputStream input = new BufferedInputStream(Files.newInputStream(rollout)))
		{
			long lineNumber = 0;
			for (;;)
			{
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				int value;
				for (;;)
				{
					value = input.read();
					if (value < 0 || value == '\n')
						break;
					bytes.write(value);
				}
				if (value < 0 && bytes.size() == 0)
					throw new IOException(rollout + ":" + (lineNumber + 1) + ": required session metadata is missing");
				++lineNumber;
				if (value < 0)
					throw new IOException(rollout + ":" + lineNumber + ": included final record is not LF-terminated");
				byte[] content = bytes.toByteArray();
				int length = content.length;
				if (length > 0 && content[length - 1] == '\r')
					--length;
				String text;
				try
				{
					text = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).
						onUnmappableCharacter(CodingErrorAction.REPORT).
						decode(ByteBuffer.wrap(content, 0, length)).toString();
				}
				catch (java.nio.charset.CharacterCodingException e)
				{
					throw new IOException(rollout + ":" + lineNumber + ": metadata is not valid UTF-8", e);
				}
				if (text.isBlank())
					continue;
				JsonNode record;
				try
				{
					record = json.readTree(text);
				}
				catch (JacksonException e)
				{
					throw new IOException(rollout + ":" + lineNumber + ": session metadata is not valid JSON", e);
				}
				if (!record.isObject() || !record.path("type").isString() ||
					!record.path("type").asString().equals("session_meta") || !record.path("payload").isObject())
					throw new IOException(rollout + ":" + lineNumber + ": required session metadata has an invalid shape");
				JsonNode payload = record.get("payload");
				if (!payload.has("history_base"))
					return null;
				JsonNode base = payload.get("history_base");
				if (!base.isObject())
					throw new IOException(rollout + ":" + lineNumber + ": history_base must be an object");
				JsonNode threadId = base.get("thread_id");
				JsonNode ordinal = base.get("end_ordinal_exclusive");
				if (threadId == null || !threadId.isString() || threadId.asString().isBlank())
					throw new IOException(rollout + ":" + lineNumber + ": history_base.thread_id must be a nonblank string");
				if (ordinal == null || !ordinal.isIntegralNumber() || ordinal.asLong(-1) < 0)
					throw new IOException(rollout + ":" + lineNumber +
						": history_base.end_ordinal_exclusive must be a nonnegative number");
				JsonNode byteOffset = base.get("end_byte_offset");
				if (byteOffset != null && (!byteOffset.isIntegralNumber() || byteOffset.asLong(-1) < 0))
					throw new IOException(rollout + ":" + lineNumber +
						": history_base.end_byte_offset must be a nonnegative number when present");
				long endByteOffset = NO_END;
				if (byteOffset != null)
					endByteOffset = byteOffset.asLong();
				return new HistoryBase(threadId.asString(), ordinal.asLong(), endByteOffset, lineNumber);
			}
		}
		catch (IOException e)
		{
			if (e.getMessage() != null && e.getMessage().startsWith(rollout.toString() + ":"))
				throw e;
			throw new IOException(rollout + ":1: cannot read required session metadata", e);
		}
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
