package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Deletes a file or directory tree that CAT owns without following links. Every deletion that removes CAT from a
 * harness or from the installer's data directory uses this rule, so a link that a user or another program placed in a
 * CAT-owned tree can never make CAT delete files outside it.
 */
public final class LinkSafeDeletion
{
	/**
	 * Prevents instantiation.
	 */
	private LinkSafeDeletion()
	{
	}

	/**
	 * Deletes {@code path} and, when it is a directory, everything inside it. A missing path is already deleted. The
	 * path's real location must equal the path itself, and neither the path nor any ancestor from its parent up to and
	 * including {@code boundary} may be a link. Inside the tree, a symbolic link, junction or other reparse point is
	 * deleted as an entry and never descended.
	 *
	 * @param path     the absolute, normalized path to delete
	 * @param boundary an ancestor of {@code path} that bounds the ancestor check, such as the harness home
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code path} is not absolute and normalized or is not inside {@code boundary}
	 * @throws Refused                  if the path or one of its checked ancestors is a link
	 * @throws IOException              if the tree cannot be deleted
	 */
	public static void delete(Path path, Path boundary) throws IOException
	{
		requireThat(path, "path").isAbsolute().isEqualTo(path.normalize(), "path.normalize()");
		requireThat(boundary, "boundary").isNotNull();
		if (!path.startsWith(boundary) || path.equals(boundary))
			throw new IllegalArgumentException("The deleted path " + path + " is not inside " + boundary);
		for (Path ancestor = path.getParent(); ancestor.startsWith(boundary); ancestor = ancestor.getParent())
		{
			if (Files.exists(ancestor, NOFOLLOW_LINKS) && isLink(ancestor))
				throw new Refused("CAT refuses to delete " + path + " because " + ancestor + " is a link");
			if (ancestor.equals(boundary))
				break;
		}
		if (!Files.exists(path, NOFOLLOW_LINKS))
			return;
		if (isLink(path) || !path.toRealPath().equals(path))
			throw new Refused("CAT refuses to delete " + path + " because it is a link or is reached through one");
		deleteEntry(path);
	}

	/**
	 * Resolves a directory below which CAT deletes or writes link-safely, such as a harness home or the directory that
	 * holds the installer's installations. That directory may itself be reached through symbolic links, so an operation
	 * resolves it once when it starts and then descends from the result without following links.
	 *
	 * @param root an absolute directory, which may not exist yet
	 * @return the real path of {@code root}, or the real path of its nearest existing ancestor followed by the rest of
	 * 	{@code root}
	 * @throws NullPointerException     if {@code root} is null
	 * @throws IllegalArgumentException if {@code root} is not absolute
	 * @throws IOException              if an existing ancestor cannot be resolved
	 */
	public static Path resolveRoot(Path root) throws IOException
	{
		requireThat(root, "root").isAbsolute();
		Path existing = root.normalize();
		while (!Files.exists(existing, NOFOLLOW_LINKS))
			existing = existing.getParent();
		return existing.toRealPath().resolve(existing.relativize(root.normalize())).normalize();
	}

	/**
	 * Returns whether a path is a symbolic link, a junction or another reparse point.
	 *
	 * @param path an existing path
	 * @return true if the path is a link
	 * @throws IOException if its attributes cannot be read
	 */
	private static boolean isLink(Path path) throws IOException
	{
		BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
		// Windows reports a junction as both a directory and "other"; descending into it would delete its target.
		return attributes.isSymbolicLink() || (attributes.isDirectory() && attributes.isOther());
	}

	/**
	 * Deletes one entry, descending only into real directories.
	 *
	 * @param path the entry
	 * @throws IOException if the entry cannot be deleted
	 */
	private static void deleteEntry(Path path) throws IOException
	{
		BasicFileAttributes attributes;
		try
		{
			attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
		}
		catch (NoSuchFileException _)
		{
			return;
		}
		if (attributes.isDirectory() && !attributes.isOther() && !attributes.isSymbolicLink())
		{
			try (DirectoryStream<Path> children = Files.newDirectoryStream(path))
			{
				for (Path child : children)
					deleteEntry(child);
			}
		}
		Files.deleteIfExists(path);
	}

	/**
	 * Reports a deletion that CAT refuses because a link could redirect it.
	 */
	public static final class Refused extends IOException
	{
		private static final long serialVersionUID = 1L;

		/**
		 * Creates a refusal.
		 *
		 * @param message the refused path and the link that caused the refusal
		 */
		public Refused(String message)
		{
			super(message);
		}
	}
}
