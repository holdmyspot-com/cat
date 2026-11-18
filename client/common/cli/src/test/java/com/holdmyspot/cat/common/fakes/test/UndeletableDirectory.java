package com.holdmyspot.cat.common.fakes.test;

import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A directory that no one may list, so that a deletion cannot remove it, placed where its parent lists it after a given
 * entry. A deletion that removes the parent's entries in the order that the parent lists them removes the given entry
 * and then stops at this directory, as a deletion whose process is killed partway stops. Closing it lets its owner
 * list it again, so that the test's cleanup can remove it.
 * <p>
 * Where the file system has POSIX permissions, the directory has no permissions; elsewhere, as on Windows, an access
 * control entry denies its owner the right to list it.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe.
 */
public final class UndeletableDirectory implements AutoCloseable
{
	/**
	 * How many names are tried before giving up on finding one that the parent lists after the given entry.
	 */
	private static final int MAXIMUM_ATTEMPTS = 100;
	private final Path directory;
	private final List<AclEntry> acl;

	/**
	 * Creates a directory that no one may list.
	 *
	 * @param directory the directory
	 * @param acl       the directory's access control list before it was denied, or empty where the file system has
	 *                  POSIX permissions
	 */
	private UndeletableDirectory(Path directory, List<AclEntry> acl)
	{
		this.directory = directory;
		this.acl = acl;
	}

	/**
	 * Creates a directory that no one may list in a parent that lists it after one of its entries.
	 *
	 * @param parent  the parent
	 * @param earlier the name of the entry of the parent that the parent lists before the new directory
	 * @return the directory
	 * @throws IOException if the directory cannot be created or its permissions cannot be changed
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
				return deny(candidate);
			TemporaryDirectories.deleteRecursively(candidate);
		}
		throw new AssertionError("No directory name was listed after " + before);
	}

	/**
	 * Denies everyone the right to list a directory.
	 *
	 * @param directory the directory
	 * @return the directory
	 * @throws IOException if its permissions cannot be changed
	 */
	private static UndeletableDirectory deny(Path directory) throws IOException
	{
		if (PortableFiles.hasModes(directory))
		{
			PortableFiles.setMode(directory, Set.of());
			return new UndeletableDirectory(directory, List.of());
		}
		AclFileAttributeView view = Files.getFileAttributeView(directory, AclFileAttributeView.class,
			LinkOption.NOFOLLOW_LINKS);
		if (view == null)
			throw new AssertionError("The file system can neither set a mode nor deny access: " + directory);
		List<AclEntry> original = view.getAcl();
		List<AclEntry> denied = new ArrayList<>();
		denied.add(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(view.getOwner()).
			setPermissions(AclEntryPermission.LIST_DIRECTORY).build());
		denied.addAll(original);
		view.setAcl(denied);
		return new UndeletableDirectory(directory, original);
	}

	@Override
	public void close() throws IOException
	{
		if (PortableFiles.hasModes(directory))
		{
			// Setting the mode without following links opens the directory first, which its mode forbids, so this
			// follows links: the test created the directory, and it is not a link
			Files.setPosixFilePermissions(directory, EnumSet.of(PosixFilePermission.OWNER_READ,
				PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
			return;
		}
		Files.getFileAttributeView(directory, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).setAcl(acl);
	}
}
