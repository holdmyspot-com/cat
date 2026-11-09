package com.holdmyspot.cat.common;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * File operations that behave the same on file systems with and without POSIX permissions.
 * <p>
 * Windows file systems have no POSIX permissions and refuse to open a directory for reading. There, Windows decides
 * whether to run a file from its extension, so a regular file whose extension Windows runs by default reads as
 * {@code rwxr-xr-x} and every other regular file reads as {@code rw-r--r--}. Setting a mode does nothing, and forcing
 * a directory does nothing because NTFS journals its directory entries. Files keep the access that they inherit from
 * their folder. A file mode that CAT compares is always read on the same machine as the mode it is compared with, so
 * a mode derived from the file's name never reports a change. A read-only file has its read-only attribute set, and
 * no directory is read-only, because that attribute does not keep files from being added to a directory.
 */
public final class PortableFiles
{
	private static final Set<PosixFilePermission> DEFAULT_MODE = PosixFilePermissions.fromString("rw-r--r--");
	private static final Set<PosixFilePermission> PROGRAM_MODE = PosixFilePermissions.fromString("rwxr-xr-x");
	private static final Set<PosixFilePermission> WRITE_PERMISSIONS = EnumSet.of(PosixFilePermission.OWNER_WRITE,
		PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);
	/**
	 * The extensions, in lower case, that Windows runs without naming a program, as its default {@code PATHEXT} lists
	 * them. A fixed list makes a file read with the same mode on every Windows machine.
	 */
	private static final Set<String> PROGRAM_EXTENSIONS = Set.of("com", "exe", "bat", "cmd", "vbs", "vbe", "js",
		"jse", "wsf", "wsh", "msc");

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
	 * @return the file's POSIX permissions; if its file system has none, {@code rwxr-xr-x} for a file whose extension
	 *   Windows runs by default and {@code rw-r--r--} for any other file
	 * @throws IOException if the file cannot be read
	 */
	public static Set<PosixFilePermission> mode(Path file) throws IOException
	{
		PosixFileAttributeView view = posixView(file);
		if (view == null)
		{
			// Fail on a missing file, as reading POSIX permissions does
			file.getFileSystem().provider().checkAccess(file);
			if (isProgram(file))
				return PROGRAM_MODE;
			return DEFAULT_MODE;
		}
		return view.readAttributes().permissions();
	}

	/**
	 * Indicates whether Windows runs a file without naming a program.
	 *
	 * @param file a file
	 * @return {@code true} if the file's extension, in any letter case, is one that Windows runs by default
	 */
	private static boolean isProgram(Path file)
	{
		Path name = file.getFileName();
		if (name == null)
			return false;
		String text = name.toString();
		int dot = text.lastIndexOf('.');
		return dot != -1 && PROGRAM_EXTENSIONS.contains(text.substring(dot + 1).toLowerCase(Locale.ROOT));
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
	 * Makes a file or directory read-only, without following symbolic links: no one may write a read-only file, and no
	 * one may add, remove or rename an entry of a read-only directory. Where the file system has no POSIX permissions,
	 * as on Windows, this sets a file's read-only attribute and does nothing to a directory.
	 *
	 * @param path a regular file or directory
	 * @throws IOException if the file's mode or attributes cannot be written
	 */
	public static void setReadOnly(Path path) throws IOException
	{
		PosixFileAttributeView view = posixView(path);
		if (view != null)
		{
			Set<PosixFilePermission> mode = EnumSet.noneOf(PosixFilePermission.class);
			mode.addAll(view.readAttributes().permissions());
			mode.removeAll(WRITE_PERMISSIONS);
			view.setPermissions(mode);
			return;
		}
		DosFileAttributeView dos = dosView(path);
		if (dos != null && !dos.readAttributes().isDirectory())
			dos.setReadOnly(true);
	}

	/**
	 * Indicates whether {@link #setReadOnly(Path)} would keep its owner from writing a file or directory, without
	 * following symbolic links.
	 *
	 * @param path a regular file or directory
	 * @return {@code true} if the file's owner may not write it; where the file system has no POSIX permissions, if a
	 *   file's read-only attribute is set, and never for a directory
	 * @throws IOException if the file's mode or attributes cannot be read
	 */
	public static boolean isReadOnly(Path path) throws IOException
	{
		PosixFileAttributeView view = posixView(path);
		if (view != null)
			return !view.readAttributes().permissions().contains(PosixFilePermission.OWNER_WRITE);
		DosFileAttributeView dos = dosView(path);
		if (dos == null)
			return false;
		DosFileAttributes attributes = dos.readAttributes();
		return !attributes.isDirectory() && attributes.isReadOnly();
	}

	/**
	 * Lets the owner of a file or directory write it again, without following symbolic links, so that it or its
	 * entries can be deleted. Where the file system has no POSIX permissions, as on Windows, this clears the read-only
	 * attribute.
	 *
	 * @param path a file or directory
	 * @return {@code true} if the file was read-only and now is not
	 * @throws IOException if the file's mode or attributes cannot be read or written
	 */
	public static boolean clearReadOnly(Path path) throws IOException
	{
		PosixFileAttributeView view = posixView(path);
		if (view != null)
		{
			Set<PosixFilePermission> mode = EnumSet.noneOf(PosixFilePermission.class);
			mode.addAll(view.readAttributes().permissions());
			if (!mode.add(PosixFilePermission.OWNER_WRITE))
				return false;
			view.setPermissions(mode);
			return true;
		}
		DosFileAttributeView dos = dosView(path);
		if (dos == null || !dos.readAttributes().isReadOnly())
			return false;
		dos.setReadOnly(false);
		return true;
	}

	/**
	 * Returns a file's DOS attribute view, without following symbolic links.
	 *
	 * @param file a file
	 * @return the view, or {@code null} if the file's file system has no DOS attributes
	 */
	private static DosFileAttributeView dosView(Path file)
	{
		return file.getFileSystem().provider().getFileAttributeView(file, DosFileAttributeView.class,
			LinkOption.NOFOLLOW_LINKS);
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
