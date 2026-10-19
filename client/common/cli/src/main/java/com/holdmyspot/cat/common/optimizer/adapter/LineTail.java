package com.holdmyspot.cat.common.optimizer.adapter;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the lines that a growing file gained since the previous read.
 * <p>
 * A line that its writer has not finished is left for the next read.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class LineTail
{
	private final Path file;
	private long offset;
	private final Object lock = new Object();

	/**
	 * Creates a tail that starts at the beginning of the file.
	 *
	 * @param file the file to read. It need not exist yet.
	 */
	LineTail(Path file)
	{
		this.file = file;
	}

	/**
	 * Returns the complete lines that were appended since the previous call.
	 *
	 * @return the lines without their terminators, empty if the file does not exist or gained no complete line
	 * @throws IOException if the file cannot be read
	 */
	List<String> readNewLines() throws IOException
	{
		synchronized (lock)
		{
			if (!Files.isRegularFile(file))
				return List.of();
			long size = Files.size(file);
			if (size < offset)
			{
				// The file was replaced by a shorter one, so none of it has been read.
				offset = 0;
			}
			if (size == offset)
				return List.of();
			byte[] bytes = new byte[Math.toIntExact(size - offset)];
			try (SeekableByteChannel channel = Files.newByteChannel(file))
			{
				channel.position(offset);
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining() && channel.read(buffer) >= 0)
				{
					// Keep reading until the requested range is complete.
				}
			}
			int end = bytes.length;
			while (end > 0 && bytes[end - 1] != '\n')
				--end;
			offset += end;
			List<String> lines = new ArrayList<>();
			int lineStart = 0;
			for (int i = 0; i < end; ++i)
			{
				if (bytes[i] != '\n')
					continue;
				lines.add(new String(bytes, lineStart, i - lineStart, UTF_8));
				lineStart = i + 1;
			}
			return lines;
		}
	}
}
