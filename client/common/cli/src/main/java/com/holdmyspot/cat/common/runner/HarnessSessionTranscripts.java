package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Locates the native session transcripts that a harness writes inside a nested session's isolated home, and copies them
 * into the run's {@code artifacts} directory so that they outlive the home.
 */
public final class HarnessSessionTranscripts
{
	private static final int COPY_BUFFER_SIZE = 8 * 1024;
	private static final int MAXIMUM_PATH_DEPTH = 128;
	private static final int MAXIMUM_PATH_COUNT = 100_000;
	private static final Set<OpenOption> READ_WITHOUT_FOLLOWING_LINKS = Set.of(StandardOpenOption.READ,
		LinkOption.NOFOLLOW_LINKS);

	private final Path source;
	private final Path destination;
	private final Predicate<String> fileName;

	/**
	 * Creates the location of a run's transcripts.
	 *
	 * @param source the directory in which the harness writes transcripts, at any depth
	 * @param destination the directory that receives the copies
	 * @param fileName accepts the name of each file that is a transcript
	 * @throws NullPointerException if any argument is null
	 */
	public HarnessSessionTranscripts(Path source, Path destination, Predicate<String> fileName)
	{
		requireThat(source, "source").isNotNull();
		requireThat(destination, "destination").isNotNull();
		requireThat(fileName, "fileName").isNotNull();
		this.source = source;
		this.destination = destination;
		this.fileName = fileName;
	}

	/**
	 * Returns the directory in which the harness writes transcripts.
	 *
	 * @return the directory
	 */
	public Path source()
	{
		return source;
	}

	/**
	 * Copies every transcript below the source into the destination, side by side under its own name. The destination
	 * is created even if nothing is copied. Links are not followed.
	 *
	 * @throws IOException if the transcripts cannot be listed or copied
	 */
	public void copy() throws IOException
	{
		Files.createDirectories(destination);
		if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
			return;
		List<Path> files;
		try (Stream<Path> walk = Files.walk(source))
		{
			files = walk.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).
				filter(file -> fileName.test(file.getFileName().toString())).
				toList();
		}
		for (Path file : files)
			Files.copy(file, destination.resolve(file.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * Copies every matching transcript's bytes through its last complete line into the destination. Each file is
	 * atomically replaced after its snapshot is complete; source links are not followed.
	 *
	 * @param replacer atomically replaces each completed snapshot
	 * @throws NullPointerException if {@code replacer} is null
	 * @throws IOException if source paths cannot be enumerated or read, or a snapshot cannot be written or replaced
	 */
	public void copyCompletedLines(FileReplacer replacer) throws IOException
	{
		requireThat(replacer, "replacer").isNotNull();
		Files.createDirectories(destination);
		BasicFileAttributes sourceAttributes;
		try
		{
			sourceAttributes = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		}
		catch (java.nio.file.NoSuchFileException _)
		{
			return;
		}
		catch (IOException e)
		{
			throw sourceFailure("read attributes for", source, e);
		}
		if (!sourceAttributes.isDirectory())
			throw new IOException("Transcript source is not a directory: " + source);

		Files.walkFileTree(source, new SimpleFileVisitor<>()
		{
			private int visitedPaths;

			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException
			{
				visit(directory);
				checkDepth(directory);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				visit(file);
				if (attributes.isRegularFile() && fileName.test(file.getFileName().toString()))
					copyCompletedFile(file, replacer);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException
			{
				throw sourceFailure("visit", file, failure);
			}

			private void visit(Path path) throws IOException
			{
				visitedPaths += 1;
				if (visitedPaths > MAXIMUM_PATH_COUNT)
					throw new IOException("Transcript source exceeds the path limit of " + MAXIMUM_PATH_COUNT +
						" entries: " + path);
			}

			private void checkDepth(Path directory) throws IOException
			{
				if (source.relativize(directory).getNameCount() > MAXIMUM_PATH_DEPTH)
					throw new IOException("Transcript source exceeds the path depth limit of " + MAXIMUM_PATH_DEPTH +
						": " + directory);
			}
		});
	}

	/**
	 * Publishes the complete-line prefix of one source transcript.
	 *
	 * @param file the source transcript
	 * @param replacer atomically replaces its destination
	 * @throws IOException if the source cannot be read or the snapshot cannot be published
	 */
	private void copyCompletedFile(Path file, FileReplacer replacer) throws IOException
	{
		Path target = destination.resolve(file.getFileName().toString());
		Path temporary = null;
		Throwable failure = null;
		try
		{
			temporary = Files.createTempFile(destination, "." + file.getFileName() + ".", ".tmp");
			long completeLength = 0;
			try (SeekableByteChannel input = Files.newByteChannel(file, READ_WITHOUT_FOLLOWING_LINKS);
				SeekableByteChannel output = Files.newByteChannel(temporary, Set.of(StandardOpenOption.WRITE,
					StandardOpenOption.TRUNCATE_EXISTING)))
			{
				long expectedSize = input.size();
				long remaining = expectedSize;
				long copied = 0;
				ByteBuffer buffer = ByteBuffer.allocate(COPY_BUFFER_SIZE);
				while (remaining > 0)
				{
					buffer.clear();
					buffer.limit((int) Math.min(buffer.capacity(), remaining));
					int count = input.read(buffer);
					if (count < 0)
						throw new IOException("Transcript ended before its captured size of " + expectedSize + " bytes: " +
							file);
					if (count == 0)
						continue;
					buffer.flip();
					while (buffer.hasRemaining())
						output.write(buffer);
					for (int index = 0; index < count; index += 1)
						if (buffer.get(index) == '\n')
							completeLength = copied + index + 1;
					copied += count;
					remaining -= count;
				}
				output.truncate(completeLength);
			}
			replacer.replace(temporary, target);
			temporary = null;
		}
		catch (IOException e)
		{
			failure = sourceFailure("copy transcript", file, e);
		}
		catch (RuntimeException | Error e)
		{
			failure = e;
		}
		if (temporary != null)
		{
			try
			{
				Files.deleteIfExists(temporary);
			}
			catch (IOException cleanupFailure)
			{
				if (failure == null)
					failure = sourceFailure("remove temporary snapshot", temporary, cleanupFailure);
				else
					failure.addSuppressed(cleanupFailure);
			}
		}
		if (failure instanceof IOException ioFailure)
			throw ioFailure;
		if (failure instanceof RuntimeException runtimeFailure)
			throw runtimeFailure;
		if (failure instanceof Error error)
			throw error;
	}

	/**
	 * Adds the source path to an I/O failure.
	 *
	 * @param action the failed operation
	 * @param path the source path
	 * @param failure the underlying failure
	 * @return a contextual I/O failure with the original cause
	 */
	private static IOException sourceFailure(String action, Path path, IOException failure)
	{
		return new IOException("Failed to " + action + " transcript at " + path, failure);
	}
}
