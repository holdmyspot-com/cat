package com.holdmyspot.cat.common.history;

import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Supplies the records of a session transcript to {@link SessionHistory}, so that a harness whose session log does
 * not hold the whole history of its session can supply the rest.
 * <p>
 * A harness whose transcript file holds all of its session's history passes {@code Files::lines}. Another, such as a
 * session that was forked from another and whose file holds only what the fork recorded itself, supplies the records of
 * every file that make up the history, oldest first.
 */
@FunctionalInterface
public interface TranscriptLines
{
	/**
	 * Returns the records of the history that a transcript belongs to, one record per line, oldest first.
	 *
	 * @param transcript the transcript file that the user selected, which holds the newest records
	 * @return the lines of the history. The caller closes the stream. A read failure that occurs while the stream is
	 *   consumed is thrown as an {@link java.io.UncheckedIOException}.
	 * @throws NullPointerException if {@code transcript} is null
	 * @throws IOException          if the transcript cannot be opened
	 */
	Stream<String> lines(Path transcript) throws IOException;
}
