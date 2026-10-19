package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Copies a user's CAT rules into an isolated harness home without following links.
 */
public final class UserRuleTree
{
	/**
	 * Copies an optional user-rule tree into an isolated location.
	 *
	 * @param source the user's rule root
	 *
	 * @param destination the owned isolated rule root
	 *
	 * @throws IOException if an existing source is not a real directory or contains a symbolic link
	 */
	public static void copy(Path source, Path destination) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(destination, "destination").isNotNull();
		if (!Files.exists(source, NOFOLLOW_LINKS))
			return;
		if (Files.isSymbolicLink(source) || !Files.isDirectory(source, NOFOLLOW_LINKS))
			throw new IOException("User rule root is not a real directory: " + source);
		Files.walkFileTree(source, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException
			{
				if (Files.isSymbolicLink(directory))
					throw new IOException("User rule directory is a symbolic link: " + source.relativize(directory));
				Files.createDirectories(destination.resolve(source.relativize(directory)));
				return CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, NOFOLLOW_LINKS))
					throw new IOException("User rule is not a regular file: " + source.relativize(file));
				Files.copy(file, destination.resolve(source.relativize(file)));
				return CONTINUE;
			}
		});
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private UserRuleTree()
	{
	}
}
