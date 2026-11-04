package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortableFiles;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Copies a directory tree as the installer stages a plugin: links inside the tree stay links, a link that leaves the
 * tree is refused, and the executable bits of files are kept.
 */
public final class TreeCopy
{
	/**
	 * The largest number of entries that a copied tree may have.
	 */
	private static final int MAXIMUM_FILES = 100_000;

	/**
	 * Prevents construction.
	 */
	private TreeCopy()
	{
	}

	/**
	 * Copies a tree into a directory that does not exist yet.
	 *
	 * @param source the tree
	 * @param target the new directory
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the copy fails, a link leaves the tree, or the tree has too many entries
	 */
	public static void copy(Path source, Path target) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(target, "target").isNotNull();
		copyTree(source, target);
	}

	/**
	 * Copies a validated tree without following its links and checks its complete entry count.
	 *
	 * @param source the validated source tree
	 * @param target the fresh staging directory
	 * @throws IOException if copying fails or a link escapes the source tree
	 */
	private static void copyTree(Path source, Path target) throws IOException
	{
		StateFiles.createDirectories(target);
		long[] counts = new long[2];
		Path canonical = source.toRealPath();
		Files.walkFileTree(source, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException
			{
				counts[0] += 1;
				if (counts[0] > MAXIMUM_FILES)
					throw new IOException("The staged tree contains too many entries: " + source);
				StateFiles.createDirectories(target.resolve(source.relativize(directory)));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				counts[0] += 1;
				if (counts[0] > MAXIMUM_FILES)
					throw new IOException("The staged tree contains too many entries: " + source);
				Path output = target.resolve(source.relativize(file));
				if (attributes.isSymbolicLink())
				{
					Path resolved = file.toRealPath();
					if (!resolved.startsWith(canonical))
						throw new IOException("The staged link resolves outside the validated tree: " + file);
					Path corresponding = target.resolve(canonical.relativize(resolved));
					Files.createSymbolicLink(output, output.getParent().relativize(corresponding));
				}
				else if (attributes.isRegularFile())
					copyFile(file, output);
				else
					throw new IOException("The staged entry is neither a regular file nor an internal link: " + file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException
			{
				if (failure != null)
					throw failure;
				PortableFiles.forceDirectory(target.resolve(source.relativize(directory)));
				return FileVisitResult.CONTINUE;
			}
		});
		Files.walkFileTree(target, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
			{
				counts[1] += 1;
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
			{
				counts[1] += 1;
				return FileVisitResult.CONTINUE;
			}
		});
		if (counts[0] != counts[1])
			throw new IOException("The staged copy's entry count differs from the validated tree: " + target);
		PortableFiles.forceDirectory(target);
	}

	/**
	 * Copies and forces a regular file, preserving executable bits.
	 *
	 * @param source the source file
	 * @param target the new file
	 * @throws IOException if copying or forcing fails
	 */
	private static void copyFile(Path source, Path target) throws IOException
	{
		StateFiles.createDirectories(target.getParent());
		StateFiles.requireNoLinks(target);
		try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
		{
			Files.copy(source, Channels.newOutputStream(channel));
			PortableFiles.setMode(target, PortableFiles.mode(source));
			channel.force(true);
		}
		PortableFiles.forceDirectory(target.getParent());
	}
}
