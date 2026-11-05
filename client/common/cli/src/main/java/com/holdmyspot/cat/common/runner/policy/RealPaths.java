package com.holdmyspot.cat.common.runner.policy;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the paths of a sandbox policy to the files that the operating system would reach through them, so that two
 * paths that name the same file through a symbolic link or a {@code ..} name compare equal.
 */
final class RealPaths
{
	/**
	 * The most symbolic links that one resolution follows, the limit at which Linux reports a link loop.
	 */
	private static final int MAX_LINKS = 40;

	/**
	 * Resolves a path to the file that it reaches. The part of the path that exists resolves to its real path; the
	 * names below it that do not exist yet are appended to that. A symbolic link whose target does not exist resolves
	 * through its target, so that a file created later through the link counts as inside the target's folder.
	 *
	 * @param path an absolute path
	 * @return the resolved path, absolute and normalized
	 * @throws IOException if a symbolic link cannot be read, or more than 40 links are followed
	 */
	static Path resolve(Path path) throws IOException
	{
		assert path.isAbsolute() : path;
		return resolve(path, path, 0);
	}

	/**
	 * Resolves a path to the file that it reaches.
	 *
	 * @param original      the path that the caller asked to resolve, for error messages
	 * @param path          the absolute path to resolve
	 * @param linksFollowed the number of symbolic links followed so far
	 * @return the resolved path
	 * @throws IOException if a symbolic link cannot be read, or more than 40 links are followed
	 */
	private static Path resolve(Path original, Path path, int linksFollowed) throws IOException
	{
		Path existing = path;
		while (existing.getParent() != null && Files.notExists(existing, LinkOption.NOFOLLOW_LINKS))
			existing = existing.getParent();
		Path base;
		boolean danglingLink = Files.isSymbolicLink(existing) && Files.notExists(existing);
		if (danglingLink)
		{
			if (linksFollowed >= MAX_LINKS)
				throw new FileSystemException(original.toString(), null, "Too many levels of symbolic links");
			Path target = existing.resolveSibling(Files.readSymbolicLink(existing));
			base = resolve(original, target, linksFollowed + 1);
		}
		else
			base = existing.toRealPath();
		if (existing.getNameCount() == path.getNameCount())
			return base;
		return base.resolve(path.subpath(existing.getNameCount(), path.getNameCount())).normalize();
	}

	/**
	 * Resolves paths to the files that they reach.
	 *
	 * @param paths absolute paths
	 * @return the resolved paths, each once, in their order
	 * @throws IOException if a symbolic link cannot be read, or more than 40 links are followed
	 */
	static List<Path> resolveAll(Collection<Path> paths) throws IOException
	{
		Set<Path> resolved = new LinkedHashSet<>();
		for (Path path : paths)
			resolved.add(resolve(path));
		return List.copyOf(resolved);
	}

	/**
	 * Indicates whether a resolved path lies inside, or is, one of some resolved folders.
	 *
	 * @param path    a resolved path
	 * @param folders resolved folders
	 * @return {@code true} if {@code path} is one of {@code folders} or lies below one of them
	 */
	static boolean isInsideAny(Path path, Collection<Path> folders)
	{
		for (Path folder : folders)
		{
			if (path.startsWith(folder))
				return true;
		}
		return false;
	}

	/**
	 * Prevents construction.
	 */
	private RealPaths()
	{
	}
}
