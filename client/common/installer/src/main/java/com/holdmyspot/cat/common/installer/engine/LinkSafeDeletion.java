package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.Waiting;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

/**
 * Deletes a file or directory tree that CAT owns without following links. Every deletion that removes CAT from a
 * harness or from the installer's data directory uses this rule, so a link that a user or another program placed in a
 * CAT-owned tree can never make CAT delete files outside it.
 * <p>
 * A deletion that fails because another program briefly holds a file open is retried for a short time. Antivirus
 * software, the search indexer and sync tools open files in the folders that they watch, and Windows then refuses to
 * delete the file or its folder with {@link AccessDeniedException} or {@link DirectoryNotEmptyException} until that
 * program lets go. The retry is not limited to Windows: telling a transient lock from a permanent denial by the error
 * alone is impossible on any OS, and a permanent denial only costs {@link #RETRY_DEADLINE} before it is reported.
 */
public final class LinkSafeDeletion
{
	/**
	 * How long one deletion keeps retrying, (the sum of its waits) before it reports its failure.
	 */
	public static final Duration RETRY_DEADLINE = Duration.ofSeconds(2);
	/**
	 * The waits between attempts: each is twice the one before it, and the last makes them add up to
	 * {@link #RETRY_DEADLINE}.
	 */
	private static final List<Duration> WAITS = Stream.of(10, 20, 40, 80, 160, 320, 640, 730).
		map(Duration::ofMillis).toList();

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
	 * @param waiting  creates the pauses between the retries; only an application entry point supplies the waiting that
	 *                 blocks
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code path} is not absolute and normalized or is not inside {@code boundary}
	 * @throws Refused                  if the path or one of its checked ancestors is a link
	 * @throws IOException              if the tree cannot be deleted. An {@link AccessDeniedException} or
	 *                                  {@link DirectoryNotEmptyException} is thrown only after the deletion was retried
	 *                                  for {@link #RETRY_DEADLINE}.
	 */
	public static void delete(Path path, Path boundary, Waiting waiting) throws IOException
	{
		requireThat(path, "path").isAbsolute().isEqualTo(path.normalize(), "path.normalize()");
		requireThat(boundary, "boundary").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
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
		deleteEntry(path, new Retry(waiting));
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
	 * Deletes one entry, descending only into real directories. A failure that a program holding a file open can cause
	 * is retried with the entry's type and link status read again, so an entry replaced by a link meanwhile is deleted
	 * as an entry and never descended.
	 *
	 * @param path  the entry
	 * @param retry the retry budget of the whole deletion
	 * @throws IOException if the entry cannot be deleted
	 */
	private static void deleteEntry(Path path, Retry retry) throws IOException
	{
		while (true)
		{
			try
			{
				deleteOnce(path, retry);
				return;
			}
			catch (AccessDeniedException | DirectoryNotEmptyException e)
			{
				if (!retry.pause())
					throw e;
			}
		}
	}

	/**
	 * Makes one attempt to delete an entry.
	 *
	 * @param path  the entry
	 * @param retry the retry budget of the whole deletion
	 * @throws IOException if the entry cannot be deleted
	 */
	private static void deleteOnce(Path path, Retry retry) throws IOException
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
					deleteEntry(child, retry);
			}
		}
		Files.deleteIfExists(path);
	}

	/**
	 * The waits between the attempts of one deletion, which share one schedule so that the whole deletion waits no
	 * longer than {@link #RETRY_DEADLINE} however many entries fail.
	 */
	private static final class Retry
	{
		private final Waiting waiting;
		private int next;

		/**
		 * Creates the retries of one deletion.
		 *
		 * @param waiting creates the pauses
		 */
		Retry(Waiting waiting)
		{
			this.waiting = waiting;
		}

		/**
		 * Waits before the next attempt.
		 *
		 * @return false if the schedule is used up or the wait was interrupted, in which case the caller reports its
		 * 	failure
		 */
		boolean pause()
		{
			if (next == WAITS.size())
				return false;
			Duration wait = WAITS.get(next);
			next += 1;
			try
			{
				waiting.every(wait).pause();
			}
			catch (IOException _)
			{
				// The wait was cut short, and the pause kept the thread's interrupt status
				return false;
			}
			return true;
		}
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
