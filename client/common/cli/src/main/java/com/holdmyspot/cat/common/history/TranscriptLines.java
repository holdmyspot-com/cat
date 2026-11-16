package com.holdmyspot.cat.common.history;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Supplies the records of a session transcript to {@link SessionHistory}, so that a harness whose session log does
 * not hold the whole history of its session can supply the rest.
 * <p>
 * A harness whose transcript file holds all of its session's history passes {@code TranscriptLines::readFileLines}.
 * Another, such as a session that was forked from another and whose file holds only what the fork recorded itself,
 * supplies the records of every file that make up the history, oldest first.
 */
@FunctionalInterface
public interface TranscriptLines
{
	/**
	 * Returns the records of the history that a transcript belongs to, oldest first, with each record's physical file
	 * and one-based line number.
	 *
	 * @param transcript the transcript file that the user selected, which holds the newest records
	 * @return the lines of the history. The caller closes the stream. A read failure that occurs while the stream is
	 *   consumed is thrown as an {@link UncheckedIOException}.
	 * @throws NullPointerException if {@code transcript} is null
	 * @throws IOException          if the transcript cannot be opened
	 */
	Stream<Line> lines(Path transcript) throws IOException;

	/**
	 * Reads one transcript file lazily, retaining its physical line number on every record.
	 *
	 * @param transcript the file to read
	 * @return its lines in file order. The caller closes the stream. A read failure during consumption is thrown as an
	 *   {@link UncheckedIOException}.
	 * @throws NullPointerException if {@code transcript} is null
	 * @throws IOException if the file cannot be opened
	 */
	static Stream<Line> readFileLines(Path transcript) throws IOException
	{
		Stream<String> fileLines = Files.lines(transcript);
		Iterator<String> source = fileLines.iterator();
		Iterator<Line> lines = new Iterator<>()
		{
			private long lineNumber;

			@Override
			public boolean hasNext()
			{
				return source.hasNext();
			}

			@Override
			public Line next()
			{
				if (!hasNext())
					throw new NoSuchElementException();
				++lineNumber;
				return new Line(transcript, lineNumber, source.next());
			}
		};
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(lines, Spliterator.ORDERED), false).
			onClose(fileLines::close);
	}

	/**
	 * A transcript line and its physical source location.
	 *
	 * @param transcript the file that contains the line
	 * @param lineNumber the one-based physical line number
	 * @param text the line's content
	 */
	record Line(Path transcript, long lineNumber, String text)
	{
		/**
		 * Creates a transcript line with a non-null source, text, and one-based physical line number.
		 *
		 * @param transcript the file that contains the line
		 * @param lineNumber the one-based physical line number
		 * @param text the line's content
		 * @throws NullPointerException     if {@code transcript} or {@code text} are null
		 * @throws IllegalArgumentException if {@code lineNumber} is less than one
		 */
		public Line
		{
			Objects.requireNonNull(transcript, "transcript may not be null");
			if (lineNumber < 1)
				throw new IllegalArgumentException("lineNumber must be positive");
			Objects.requireNonNull(text, "text may not be null");
		}
	}
}
