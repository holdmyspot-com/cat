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
 * Copies a complete CAT plugin directory into an isolated local marketplace plugin directory.
 */
public final class LocalMarketplace
{
	/**
	 * Copies an artifact into an isolated marketplace plugin directory without following links.
	 *
	 * @param artifact the validated unpacked plugin directory
	 *
	 * @param plugin the isolated marketplace plugin directory
	 *
	 * @throws IOException if copying fails
	 */
	public static void copy(Path artifact, Path plugin) throws IOException
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(plugin, "plugin").isNotNull();
		Files.createDirectories(plugin);
		Files.walkFileTree(artifact, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
				throws IOException
			{
				if (Files.isSymbolicLink(directory))
				{
					throw new IOException("Artifact directory is a symbolic link: " + artifact.relativize(directory) +
						". Replace it with a real directory before installing the artifact.");
				}
				Files.createDirectories(plugin.resolve(artifact.relativize(directory)));
				return CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
				throws IOException
			{
				if (Files.isSymbolicLink(file))
				{
					Path target = Files.readSymbolicLink(file);
					Path resolvedTarget = file.getParent().resolve(target).normalize();
					if (!resolvedTarget.startsWith(artifact) || !Files.isRegularFile(resolvedTarget,
						NOFOLLOW_LINKS))
					{
						throw new IOException("Artifact symbolic link " + artifact.relativize(file) + " points to " +
				target +
							", which is outside the artifact or not a regular file. Replace it with a link to a regular file " +

							"inside the artifact.");
					}
					Files.createSymbolicLink(plugin.resolve(artifact.relativize(file)), target);
				}
				else
					Files.copy(file, plugin.resolve(artifact.relativize(file)));
				return CONTINUE;
			}
		});
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private LocalMarketplace()
	{
	}
}
