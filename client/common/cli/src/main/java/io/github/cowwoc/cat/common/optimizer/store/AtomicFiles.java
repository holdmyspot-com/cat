package io.github.cowwoc.cat.common.optimizer.store;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import io.github.cowwoc.cat.common.PortableFiles;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Writes campaign files so that a crash leaves either the old or the new content.
 * <p>
 * A file is replaced by writing the content to a temporary file next to it, forcing that file to the storage device,
 * renaming it over the target and forcing the directory. Readers never read the temporary file, and the next
 * replacement of the same target overwrites a leftover one.
 */
final class AtomicFiles
{
	private static final String TEMPORARY_SUFFIX = ".tmp";

	/**
	 * Prevents instantiation.
	 */
	private AtomicFiles()
	{
	}

	/**
	 * Atomically replaces a file's content.
	 *
	 * @param target the file, whose directory exists
	 * @param content the new content
	 * @param afterStep receives each {@link WriteStep} after it completes; an exception that it throws stops the write
	 *   at that point, as a crash would
	 * @throws IOException if the file cannot be replaced
	 */
	static void replace(Path target, byte[] content, Consumer<WriteStep> afterStep) throws IOException
	{
		Path temporary = target.resolveSibling(target.getFileName() + TEMPORARY_SUFFIX);
		try
		{
			try (FileChannel channel = FileChannel.open(temporary, CREATE, TRUNCATE_EXISTING, WRITE))
			{
				ByteBuffer buffer = ByteBuffer.wrap(content);
				while (buffer.hasRemaining())
					channel.write(buffer);
				afterStep.accept(WriteStep.WRITE_TEMPORARY);
				channel.force(true);
				afterStep.accept(WriteStep.SYNC_TEMPORARY);
			}
			PortableFiles.replace(temporary, target);
		}
		catch (IOException e)
		{
			try
			{
				Files.deleteIfExists(temporary);
			}
			catch (IOException suppressed)
			{
				e.addSuppressed(suppressed);
			}
			throw e;
		}
		afterStep.accept(WriteStep.RENAME);
		force(target.getParent());
		afterStep.accept(WriteStep.SYNC_DIRECTORY);
	}

	/**
	 * Creates a directory and its missing ancestors, forcing each new entry to the storage device.
	 *
	 * @param directory the directory
	 * @throws IOException if a directory cannot be created or forced
	 */
	static void createDirectories(Path directory) throws IOException
	{
		if (Files.isDirectory(directory))
			return;
		Path parent = directory.getParent();
		createDirectories(parent);
		Files.createDirectory(directory);
		force(parent);
	}

	/**
	 * Forces a directory's entries to the storage device.
	 *
	 * @param directory the directory
	 * @throws IOException if the directory cannot be forced
	 */
	private static void force(Path directory) throws IOException
	{
		PortableFiles.forceDirectory(directory);
	}
}
