package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Locates the native session transcripts that a harness writes inside a run's isolated home, and copies them into the
 * run's {@code artifacts} directory so that they outlive the home.
 */
public final class HarnessSessionTranscripts
{
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
}
