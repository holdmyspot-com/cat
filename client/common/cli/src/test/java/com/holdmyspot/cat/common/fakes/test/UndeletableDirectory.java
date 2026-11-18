package com.holdmyspot.cat.common.fakes.test;

import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A directory that a deletion cannot remove, placed where its parent lists it after a given entry. A deletion that
 * removes the parent's entries in the order that the parent lists them removes the given entry and then stops at this
 * directory, as a deletion whose process is killed partway stops. Closing it lets the directory be deleted again, so
 * that the test's cleanup can remove it.
 * <p>
 * Where the file system has POSIX permissions, the directory has no permissions, so no one may list it. Elsewhere, as
 * on Windows, the directory holds a file that this object keeps open without letting anyone delete it.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe.
 */
public final class UndeletableDirectory implements AutoCloseable
{
	/**
	 * How many names are tried before giving up on finding one that the parent lists after the given entry.
	 */
	private static final int MAXIMUM_ATTEMPTS = 100;
	private final Closeable release;

	/**
	 * Creates a directory that a deletion cannot remove.
	 *
	 * @param release lets the directory be deleted again
	 */
	private UndeletableDirectory(Closeable release)
	{
		this.release = release;
	}

	/**
	 * Creates a directory that a deletion cannot remove in a parent that lists it after one of its entries.
	 *
	 * @param parent  the parent
	 * @param earlier the name of the entry of the parent that the parent lists before the new directory
	 * @return the directory
	 * @throws IOException if the directory cannot be created or kept from being deleted
	 */
	public static UndeletableDirectory after(Path parent, String earlier) throws IOException
	{
		Path before = parent.resolve(earlier);
		for (int attempt = 0; attempt < MAXIMUM_ATTEMPTS; ++attempt)
		{
			Path candidate = Files.createDirectory(parent.resolve("undeletable-" + attempt));
			List<Path> listed;
			try (Stream<Path> entries = Files.list(parent))
			{
				listed = entries.toList();
			}
			if (listed.indexOf(candidate) > listed.indexOf(before))
				return keep(candidate);
			TemporaryDirectories.deleteRecursively(candidate);
		}
		throw new AssertionError("No directory name was listed after " + before);
	}

	/**
	 * Keeps a directory from being deleted.
	 *
	 * @param directory the directory
	 * @return the directory
	 * @throws IOException if its permissions cannot be changed or its file cannot be created or opened
	 */
	private static UndeletableDirectory keep(Path directory) throws IOException
	{
		if (PortableFiles.hasModes(directory))
		{
			PortableFiles.setMode(directory, Set.of());
			// Setting the mode without following links opens the directory first, which its mode forbids, so this
			// follows links: the test created the directory, and it is not a link
			return new UndeletableDirectory(() -> Files.setPosixFilePermissions(directory,
				EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
					PosixFilePermission.OWNER_EXECUTE)));
		}
		// Windows lets a process with the backup and restore privileges, such as a GitHub runner's account, list and
		// delete whatever an access control list denies it, so denying access would let the deletion remove the
		// directory. A RandomAccessFile opens its file without sharing the right to delete it, which no privilege
		// overrides, so the deletion stops at that file until the RandomAccessFile is closed.
		Path file = Files.createFile(directory.resolve("open"));
		return new UndeletableDirectory(new RandomAccessFile(file.toFile(), "r"));
	}

	@Override
	public void close() throws IOException
	{
		release.close();
	}
}
