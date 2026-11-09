package com.holdmyspot.cat.common.runner;

import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.FileVisitResult.SKIP_SUBTREE;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Decides how each file, directory and symbolic link of a project reaches a copy of the project, which the runner makes
 * in a run's directory and a request archive carries to its receiver.
 * <p>
 * A link whose target, resolved one name at a time, never leaves the project reaches the copy as a link with the same
 * target, so that it resolves inside the copy to the copy of its target. Any other link reaches the copy as a copy of
 * the file or directory tree that it leads to, read when the project is copied, with every link inside that tree
 * replaced by a copy of its target, so that a write in the copy never reaches the target. A link whose target does not
 * exist, cannot be read, or holds the project is refused.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
final class ProjectTree
{
	/**
	 * The most symbolic links that one resolution passes through, as Linux allows.
	 */
	private static final int MAXIMUM_LINKS = 40;

	/**
	 * Passes each entry of a project to a writer: each directory before its contents, each file and each copied link
	 * target as the files and directories that the copy holds, and each link that stays a link.
	 *
	 * @param project    the absolute, normalized project directory, which is not a symbolic link
	 * @param nullDevice the null device, which a sandbox mounts over each file that the session may not read; the copy
	 *                   leaves out each file that is that device. Where it does not exist, as on Windows, the copy
	 *                   leaves out no file.
	 * @param excluded   indicates whether the copy leaves out a file or directory, with its contents, given its real
	 *                   path; a link whose target lies at or below such a path is left out too
	 * @param writer     writes the copy
	 * @throws IOException if a link's target does not exist, cannot be read, is hidden by the sandbox, holds the
	 *                     project or is neither a file nor a directory, if a directory tree that a link leads to holds
	 *                     such a link or a link back into itself, or if the project cannot be read or the writer fails
	 */
	static void copy(Path project, Path nullDevice, Predicate<Path> excluded, Writer writer) throws IOException
	{
		Path realProject = project.toRealPath();
		Files.walkFileTree(project, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
				throws IOException
			{
				Path relative = project.relativize(directory);
				if (excluded.test(realProject.resolve(relative)))
					return SKIP_SUBTREE;
				writer.directory(relative);
				return CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				Path relative = project.relativize(file);
				if (excluded.test(realProject.resolve(relative)))
					return CONTINUE;
				if (attributes.isSymbolicLink())
					copyLink(project, realProject, relative, nullDevice, excluded, writer);
				else if (!SandboxPlaceholders.isPlaceholder(file, attributes, nullDevice))
					writer.file(relative, file);
				return CONTINUE;
			}
		});
	}

	/**
	 * Passes one symbolic link of a project to a writer as a link or as a copy of its target.
	 *
	 * @param project     the absolute, normalized project directory
	 * @param realProject the project directory's real path
	 * @param relative    the link's path relative to the project
	 * @param nullDevice  the null device
	 * @param excluded    indicates whether the copy leaves out a file or directory, given its real path
	 * @param writer      writes the copy
	 * @throws IOException if the link cannot be copied
	 */
	private static void copyLink(Path project, Path realProject, Path relative, Path nullDevice,
		Predicate<Path> excluded, Writer writer) throws IOException
	{
		Path link = project.resolve(relative);
		Path text = Files.readSymbolicLink(link);
		String name = "The project's symbolic link " + relative + " points to ";
		Path target = resolve(link, name + text);
		for (Path ancestor = target; ancestor != null; ancestor = ancestor.getParent())
		{
			if (excluded.test(ancestor))
				return;
		}
		BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class, NOFOLLOW_LINKS);
		if (SandboxPlaceholders.isPlaceholder(target, attributes, nullDevice))
			throw new IOException(name + target + ", which the sandbox does not let this session read");
		if (staysInside(project, link.getParent(), text))
		{
			writer.link(relative, text);
			return;
		}
		try
		{
			if (attributes.isRegularFile())
				writer.file(relative, target);
			else if (attributes.isDirectory())
			{
				if (realProject.startsWith(target))
					throw new IOException(name + target + ", which holds the project");
				copyTree(target, relative, name + target, nullDevice, excluded, writer);
			}
			else
				throw new IOException(name + target + ", which is neither a file nor a directory");
		}
		catch (AccessDeniedException e)
		{
			throw new IOException(name + target + ", which cannot be read: " + e.getFile(), e);
		}
	}

	/**
	 * Returns the real path of the file or directory that a symbolic link leads to, through every link on the way.
	 *
	 * @param link        the link
	 * @param description the start of a refusal, which names the link and its target
	 * @return the real path of the link's target
	 * @throws IOException if the target does not exist, cannot be read, or cannot be resolved
	 */
	private static Path resolve(Path link, String description) throws IOException
	{
		try
		{
			return link.toRealPath();
		}
		catch (NoSuchFileException e)
		{
			throw new IOException(description + ", which does not exist", e);
		}
		catch (AccessDeniedException e)
		{
			throw new IOException(description + ", which cannot be read", e);
		}
		catch (FileSystemException e)
		{
			// Such as a link that leads back to itself
			throw new IOException(description + ", which cannot be resolved: " + e.getReason(), e);
		}
	}

	/**
	 * Indicates whether a symbolic link's target names a path inside a directory both as text and when it is resolved
	 * one name at a time, so that a copy of the directory that holds the link with the same target reaches the copy of
	 * the same file.
	 *
	 * @param root      the absolute, normalized directory
	 * @param directory the directory that holds the link, below {@code root} and reached through no link
	 * @param target    the link's target
	 * @return {@code true} if the target leaves {@code root} neither as text nor through a link that it passes through
	 * @throws IOException if a link cannot be read
	 */
	private static boolean staysInside(Path root, Path directory, Path target) throws IOException
	{
		try
		{
			return resolveInside(root, directory, target).isPresent();
		}
		catch (NoSuchFileException _)
		{
			// A name that the resolution one name at a time cannot find, such as a file followed by "..", is one that the
			// copy may resolve differently
			return false;
		}
	}

	/**
	 * Resolves a symbolic link's target one name at a time without leaving a directory, as the operating system
	 * resolves it: a link that the target passes through is resolved from the directory that holds it, and {@code ..}
	 * leads to the parent of the directory that the resolution has reached.
	 *
	 * @param root      the absolute, normalized directory that the resolution may not leave
	 * @param directory the directory that holds the link, at or below {@code root} and reached through no link
	 * @param target    the link's target
	 * @return the path that the target leads to, reached through no link, or empty if the target is absolute or leaves
	 *   {@code root} as text or at any name
	 * @throws NoSuchFileException if a name that the target passes through does not exist, or {@code ..} follows a name
	 *                             that is not a directory
	 * @throws FileSystemException if the resolution passes through more links than the operating system allows
	 * @throws IOException         if a link cannot be read
	 */
	private static Optional<Path> resolveInside(Path root, Path directory, Path target) throws IOException
	{
		return resolveInside(root, directory, target, 0);
	}

	/**
	 * Resolves a symbolic link's target one name at a time without leaving a directory.
	 *
	 * @param root      the absolute, normalized directory that the resolution may not leave
	 * @param directory the directory that holds the link, at or below {@code root} and reached through no link
	 * @param target    the link's target
	 * @param links     the number of links that the resolution has passed through
	 * @return the path that the target leads to, reached through no link, or empty if the target is absolute or leaves
	 *   {@code root} as text or at any name
	 * @throws IOException if a name does not exist, {@code ..} follows a name that is not a directory, the resolution
	 *                     passes through too many links, or a link cannot be read
	 */
	private static Optional<Path> resolveInside(Path root, Path directory, Path target, int links)
		throws IOException
	{
		if (target.getRoot() != null || !directory.resolve(target).normalize().startsWith(root))
			return Optional.empty();
		Path current = directory;
		for (Path name : target)
		{
			switch (name.toString())
			{
				case "." ->
				{
				}
				case ".." ->
				{
					if (current.equals(root))
						return Optional.empty();
					if (!Files.isDirectory(current, NOFOLLOW_LINKS))
						throw new NoSuchFileException(current.toString());
					current = current.getParent();
				}
				default ->
				{
					Path next = current.resolve(name);
					if (Files.isSymbolicLink(next))
					{
						if (links == MAXIMUM_LINKS)
							throw new FileSystemException(next.toString(), null, "Too many levels of symbolic links");
						Optional<Path> resolved = resolveInside(root, current, Files.readSymbolicLink(next), links + 1);
						if (resolved.isEmpty())
							return resolved;
						next = resolved.get();
					}
					else if (Files.notExists(next, NOFOLLOW_LINKS))
						throw new NoSuchFileException(next.toString());
					current = next;
				}
			}
		}
		return Optional.of(current);
	}

	/**
	 * Passes a directory tree that a project's link leads to, outside the project, to a writer as files and
	 * directories, replacing each link inside the tree by a copy of its target.
	 *
	 * @param source      the real path of the directory
	 * @param relative    the link's path relative to the project
	 * @param description the start of a refusal, which names the link and its target
	 * @param nullDevice  the null device
	 * @param excluded    indicates whether the copy leaves out a file or directory, given its real path
	 * @param writer      writes the copy
	 * @throws IOException if a link inside the tree does not exist or leads back into the tree, or reading or writing
	 *                     fails
	 */
	private static void copyTree(Path source, Path relative, String description, Path nullDevice,
		Predicate<Path> excluded, Writer writer) throws IOException
	{
		Files.walkFileTree(source, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
			new SimpleFileVisitor<>()
			{
				@Override
				public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
					throws IOException
				{
					if (excluded.test(directory.toRealPath()))
						return SKIP_SUBTREE;
					writer.directory(relative.resolve(source.relativize(directory)));
					return CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
				{
					// Following links, the walk passes a link with its own attributes only when its target is missing
					if (attributes.isSymbolicLink())
					{
						throw new IOException(description + ", whose symbolic link " + file + " points to " +
							Files.readSymbolicLink(file) + ", which does not exist");
					}
					Path real = file.toRealPath();
					if (!excluded.test(real) && !SandboxPlaceholders.isPlaceholder(file, attributes, nullDevice))
						writer.file(relative.resolve(source.relativize(file)), real);
					return CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException
				{
					if (failure instanceof FileSystemLoopException)
					{
						throw new IOException(description + ", whose symbolic link " + file + " leads back into the " +
							"directory that holds it", failure);
					}
					throw failure;
				}
			});
	}

	/**
	 * Creates the symbolic links of a copy of a project after its files and directories exist, each once every link
	 * that its target passes through exists, so that a link to a directory is created as one on Windows.
	 *
	 * @param project the absolute, normalized directory of the copy, which holds no symbolic link yet
	 * @param links   the target of each link, by the link's absolute, normalized path below {@code project}; each
	 *                link's directory exists and is reached through no link
	 * @throws IOException if a link's target leaves the project, as text or through another link, if it does not exist
	 *                     or leads back to itself, or if a link cannot be created
	 */
	static void createLinks(Path project, Map<Path, Path> links) throws IOException
	{
		List<Entry<Path, Path>> pending = new ArrayList<>(links.entrySet());
		while (!pending.isEmpty())
		{
			List<Entry<Path, Path>> waiting = new ArrayList<>();
			for (Entry<Path, Path> link : pending)
			{
				Optional<Path> target;
				try
				{
					target = resolveInside(project, link.getKey().getParent(), link.getValue());
				}
				catch (NoSuchFileException _)
				{
					// The target may pass through a link that is created later
					waiting.add(link);
					continue;
				}
				if (target.isEmpty())
				{
					throw new IOException("The project's symbolic link " + project.relativize(link.getKey()) +
						" points to " + link.getValue() + ", which lies outside the project");
				}
				Files.createSymbolicLink(link.getKey(), link.getValue());
			}
			if (waiting.size() == pending.size())
			{
				Entry<Path, Path> link = waiting.getFirst();
				throw new IOException("The project's symbolic link " + project.relativize(link.getKey()) + " points to " +
					link.getValue() + ", which does not exist or leads back to the link");
			}
			pending = waiting;
		}
	}

	/**
	 * Writes the entries of a copy of a project.
	 */
	interface Writer
	{
		/**
		 * Writes a directory of the copy.
		 *
		 * @param relative the directory's path relative to the copy, empty for the copy itself
		 * @throws IOException if writing fails
		 */
		void directory(Path relative) throws IOException;

		/**
		 * Writes a file of the copy.
		 *
		 * @param relative the file's path relative to the copy
		 * @param source   the file whose contents the copy holds, reached through no symbolic link
		 * @throws IOException if reading or writing fails
		 */
		void file(Path relative, Path source) throws IOException;

		/**
		 * Writes a symbolic link of the copy.
		 *
		 * @param relative the link's path relative to the copy
		 * @param target   the link's target, which resolves inside the copy
		 * @throws IOException if writing fails
		 */
		void link(Path relative, Path target) throws IOException;
	}

	/**
	 * Prevents construction.
	 */
	private ProjectTree()
	{
	}
}
