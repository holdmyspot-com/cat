package io.github.cowwoc.cat.common.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Copies directory trees that tests build once and reuse.
 */
public final class FileTrees
{
	/**
	 * Prevents instantiation.
	 */
	private FileTrees()
	{
	}

	/**
	 * Copies a directory tree with each file's attributes, such as its permissions. A link is copied as a link to the
	 * same target, not as the file it points to.
	 *
	 * @param source the directory to copy
	 * @param target the directory to create, or an empty directory
	 * @throws IOException if the tree cannot be copied
	 */
	public static void copy(Path source, Path target) throws IOException
	{
		try (Stream<Path> paths = Files.walk(source))
		{
			for (Path path : paths.toList())
			{
				Path destination = target.resolve(source.relativize(path).toString());
				if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
					Files.createDirectories(destination);
				else if (Files.isSymbolicLink(path))
				{
					// Windows copies a link only with the privilege to create links, which Developer Mode does not grant,
					// but it lets any user create one
					Files.createSymbolicLink(destination, Files.readSymbolicLink(path));
				}
				else
					Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
			}
		}
	}
}
