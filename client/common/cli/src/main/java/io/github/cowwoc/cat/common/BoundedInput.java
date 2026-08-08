package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a complete file or stream into bounded bytes or UTF-8 text.
 * <p>
 * Use when the caller needs all input in memory and must reject oversized content instead of streaming it.
 */
public final class BoundedInput
{
	/**
	 * Reads a UTF-8 file whose complete content is required by the caller.
	 *
	 * @param file the input file
	 * @param maximumBytes the largest supported encoded input
	 * @param description the reader-facing input description
	 * @return the decoded UTF-8 content
	 * @throws IOException if the file cannot be read or exceeds the supported size
	 */
	public static String readUtf8(Path file, int maximumBytes, String description) throws IOException
	{
		requireThat(file, "file").isNotNull();
		return readUtf8(Files.newInputStream(file), file, maximumBytes, description);
	}

	/**
	 * Reads and closes a UTF-8 stream whose complete content is required by the caller.
	 *
	 * @param input the input stream, which this method closes
	 * @param source the reader-facing source location
	 * @param maximumBytes the largest supported encoded input
	 * @param description the reader-facing input description
	 * @return the decoded UTF-8 content
	 * @throws IOException if the stream cannot be read or exceeds the supported size
	 */
	public static String readUtf8(InputStream input, Object source, int maximumBytes, String description)
		throws IOException
	{
		return new String(readBytes(input, source, maximumBytes, description), UTF_8);
	}

	/**
	 * Reads and closes a binary stream whose complete content is required by the caller.
	 *
	 * @param input the input stream, which this method closes
	 * @param source the reader-facing source location
	 * @param maximumBytes the largest supported input
	 * @param description the reader-facing input description
	 * @return the bounded input bytes
	 * @throws IOException if the stream cannot be read or exceeds the supported size
	 */
	public static byte[] readBytes(InputStream input, Object source, int maximumBytes, String description)
		throws IOException
	{
		requireThat(input, "input").isNotNull();
		requireThat(source, "source").isNotNull();
		requireThat(maximumBytes, "maximumBytes").isPositive();
		requireThat(description, "description").isNotBlank();
		try (input)
		{
			byte[] content = input.readNBytes(maximumBytes + 1);
			if (content.length > maximumBytes)
			{
				throw new IOException(description + " exceeds the maximum supported size of " + maximumBytes +
					" bytes: " + source);
			}
			return content;
		}
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private BoundedInput()
	{
	}
}
