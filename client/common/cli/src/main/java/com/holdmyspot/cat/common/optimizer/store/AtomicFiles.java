package com.holdmyspot.cat.common.optimizer.store;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.PortableFiles;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Writes campaign files so that a crash leaves either the old or the new content.
 * <p>
 * A file is replaced by writing the content to a temporary file next to it, forcing that file to the storage device,
 * renaming it over the target and forcing the directory. Readers never read the temporary file, and the next
 * replacement of the same target overwrites a leftover one. A file that is written once is linked to its target
 * instead, so that the first writer's content stays.
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
	 * @param replacer replaces the file with the temporary file
	 * @param target the file, whose directory exists
	 * @param content the new content
	 * @param afterStep receives each {@link WriteStep} after it completes; an exception that it throws stops the write
	 *   at that point, as a crash would
	 * @throws IOException if the file cannot be replaced
	 */
	static void replace(FileReplacer replacer, Path target, byte[] content, Consumer<WriteStep> afterStep)
		throws IOException
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
			replacer.replace(temporary, target);
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
	 * Creates a file unless it exists, so that a crash leaves either no file or the complete content. The content is
	 * written to a temporary file of its own and forced to the storage device, then linked to the target, which fails
	 * if another writer created the target first.
	 *
	 * @param target the file
	 * @param content the file's content
	 * @param afterStep receives each step after it completes; an exception that it throws stops the write at that
	 *   point, as a crash would
	 * @return {@code true} if this call created the file, {@code false} if it existed
	 * @throws IOException if the file cannot be written
	 */
	static boolean createOnce(Path target, byte[] content, Consumer<WriteStep> afterStep) throws IOException
	{
		Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", TEMPORARY_SUFFIX);
		try
		{
			try (FileChannel channel = FileChannel.open(temporary, WRITE))
			{
				ByteBuffer buffer = ByteBuffer.wrap(content);
				while (buffer.hasRemaining())
					channel.write(buffer);
				afterStep.accept(WriteStep.WRITE_TEMPORARY);
				channel.force(true);
				afterStep.accept(WriteStep.SYNC_TEMPORARY);
			}
			try
			{
				Files.createLink(target, temporary);
			}
			catch (FileAlreadyExistsException _)
			{
				return false;
			}
			catch (UnsupportedOperationException _)
			{
				// Without hard links, a move that does not replace the target still refuses an existing file
				try
				{
					Files.move(temporary, target);
				}
				catch (FileAlreadyExistsException _)
				{
					return false;
				}
			}
			afterStep.accept(WriteStep.RENAME);
			force(target.getParent());
			afterStep.accept(WriteStep.SYNC_DIRECTORY);
			return true;
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
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
