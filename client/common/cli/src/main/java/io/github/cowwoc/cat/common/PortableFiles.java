package io.github.cowwoc.cat.common;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * File operations that behave the same on file systems with and without POSIX permissions.
 * <p>
 * Windows file systems have no POSIX permissions and refuse to open a directory for reading. There, every regular
 * file reads as {@code rw-r--r--}, setting a mode does nothing, and forcing a directory does nothing because NTFS
 * journals its directory entries. Files keep the access that they inherit from their folder. A file mode that CAT
 * compares is always read on the same machine as the mode it is compared with, so the fixed mode never reports a
 * change.
 */
public final class PortableFiles
{
	private static final Set<PosixFilePermission> DEFAULT_MODE = PosixFilePermissions.fromString("rw-r--r--");

	/**
	 * Indicates whether a file's file system records POSIX permissions.
	 *
	 * @param path a file or directory
	 * @return {@code true} if the file system records POSIX permissions
	 */
	public static boolean hasModes(Path path)
	{
		return path.getFileSystem().supportedFileAttributeViews().contains("posix");
	}

	/**
	 * Returns a file's mode, without following symbolic links.
	 *
	 * @param file a file
	 * @return the file's POSIX permissions, or {@code rw-r--r--} if its file system has none
	 * @throws IOException if the file cannot be read
	 */
	public static Set<PosixFilePermission> mode(Path file) throws IOException
	{
		PosixFileAttributeView view = posixView(file);
		if (view == null)
		{
			// Fail on a missing file, as reading POSIX permissions does
			file.getFileSystem().provider().checkAccess(file);
			return DEFAULT_MODE;
		}
		return view.readAttributes().permissions();
	}

	/**
	 * Sets a file's mode, or does nothing if its file system has no POSIX permissions.
	 *
	 * @param file a file
	 * @param mode the file's new POSIX permissions
	 * @throws IOException if the file's mode cannot be set
	 */
	public static void setMode(Path file, Set<PosixFilePermission> mode) throws IOException
	{
		PosixFileAttributeView view = posixView(file);
		if (view != null)
			view.setPermissions(mode);
	}

	/**
	 * Forces a directory's entries to the storage device, or does nothing if its file system has no POSIX
	 * permissions.
	 *
	 * @param directory a directory
	 * @throws IOException if the directory cannot be forced
	 */
	public static void forceDirectory(Path directory) throws IOException
	{
		if (!hasModes(directory))
			return;
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ))
		{
			channel.force(true);
		}
	}

	/**
	 * Returns a file's POSIX attribute view, without following symbolic links.
	 *
	 * @param file a file
	 * @return the view, or {@code null} if the file's file system has no POSIX permissions
	 */
	private static PosixFileAttributeView posixView(Path file)
	{
		if (!hasModes(file))
			return null;
		return file.getFileSystem().provider().getFileAttributeView(file, PosixFileAttributeView.class,
			LinkOption.NOFOLLOW_LINKS);
	}

	/**
	 * Prevents construction of this static utility.
	 */
	private PortableFiles()
	{
	}
}
