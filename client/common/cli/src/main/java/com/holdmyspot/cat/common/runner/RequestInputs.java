package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import com.holdmyspot.cat.common.PortableFiles;
import java.util.Set;

/**
 * Reads session-controlled inputs through directory handles, refusing links and special files even during a rename.
 */
final class RequestInputs
{
	/**
	 * Reads a bounded regular file without following links in any path component.
	 *
	 * @param source the absolute source file
	 * @param maximum the maximum accepted byte count
	 * @return the bytes
	 * @throws IOException if the input is unsafe, unavailable, or exceeds the bound
	 */
	static byte[] read(Path source, int maximum) throws IOException
	{
		try (DirectoryStream<Path> root = Files.newDirectoryStream(source.getRoot()))
		{
			return read(secure(root), source.getRoot().relativize(source), maximum);
		}
	}

	/**
	 * Reads a path relative to an already open directory.
	 *
	 * @param directory the open ancestor
	 * @param relative the remaining components
	 * @param maximum the byte limit
	 * @return the bytes
	 * @throws IOException if the path is unsafe or too large
	 */
	private static byte[] read(SecureDirectoryStream<Path> directory, Path relative, int maximum) throws IOException
	{
		Path name = relative.getName(0);
		if (relative.getNameCount() > 1)
		{
			try (SecureDirectoryStream<Path> child = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS))
			{
				return read(child, relative.subpath(1, relative.getNameCount()), maximum);
			}
		}
		BasicFileAttributes attributes = attributes(directory, name);
		if (!attributes.isRegularFile())
			throw new IOException("Input must be a regular file without symbolic links: " + name);
		try (SeekableByteChannel channel = directory.newByteChannel(name,
			Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
			InputStream input = Channels.newInputStream(channel))
		{
			byte[] bytes = input.readNBytes(maximum + 1);
			if (bytes.length > maximum)
				throw new IOException("Input exceeds " + maximum + " bytes: " + name);
			return bytes;
		}
	}

	/**
	 * Copies a directory into a private destination, without following links at any level.
	 *
	 * @param source the absolute input directory
	 * @param destination a new private destination
	 * @throws IOException if the tree contains unsafe entries or cannot be copied securely
	 */
	static void copy(Path source, Path destination) throws IOException
	{
		try (DirectoryStream<Path> root = Files.newDirectoryStream(source.getRoot()))
		{
			copy(secure(root), source.getRoot().relativize(source), destination);
		}
	}

	/**
	 * Opens each ancestor without following links before copying a tree.
	 *
	 * @param directory the open ancestor
	 * @param relative the remaining input path
	 * @param destination the new private directory
	 * @throws IOException if a component is unsafe or cannot be copied
	 */
	private static void copy(SecureDirectoryStream<Path> directory, Path relative, Path destination) throws IOException
	{
		Path name = relative.getName(0);
		try (SecureDirectoryStream<Path> child = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS))
		{
			if (relative.getNameCount() > 1)
				copy(child, relative.subpath(1, relative.getNameCount()), destination);
			else
				copyDirectory(child, destination);
		}
	}

	/**
	 * Copies the entries of one pinned directory. The destination belongs only to the broker.
	 *
	 * @param source the pinned source
	 * @param destination the new destination
	 * @throws IOException if an entry is unsafe or copying fails
	 */
	private static void copyDirectory(SecureDirectoryStream<Path> source, Path destination) throws IOException
	{
		Files.createDirectory(destination);
		for (Path entry : source)
		{
			Path name = entry.getFileName();
			BasicFileAttributes attributes = attributes(source, name);
			Path target = destination.resolve(name.toString());
			if (attributes.isDirectory())
			{
				try (SecureDirectoryStream<Path> child = source.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS))
				{
					copyDirectory(child, target);
				}
			}
			else if (attributes.isRegularFile())
			{
				try (SeekableByteChannel channel = source.newByteChannel(name,
					Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
					InputStream input = Channels.newInputStream(channel))
				{
					Files.copy(input, target);
					PosixFileAttributeView permissions = source.getFileAttributeView(name, PosixFileAttributeView.class,
						LinkOption.NOFOLLOW_LINKS);
					if (permissions != null)
						PortableFiles.setMode(target, permissions.readAttributes().permissions());
				}
			}
			else
				throw new IOException("Input tree contains a symbolic link or special file: " + name);
		}
	}

	/**
	 * Reads attributes relative to a pinned directory without following a final link.
	 *
	 * @param directory the pinned directory
	 * @param name the entry name
	 * @return the attributes
	 * @throws IOException if the entry cannot be inspected
	 */
	private static BasicFileAttributes attributes(SecureDirectoryStream<Path> directory, Path name) throws IOException
	{
		return directory.getFileAttributeView(name, BasicFileAttributeView.class,
			LinkOption.NOFOLLOW_LINKS).readAttributes();
	}

	/**
	 * Requires the file system's race-resistant directory access rather than weakening the input boundary.
	 *
	 * @param directory the opened directory
	 * @return its secure view
	 * @throws IOException if the file system cannot provide secure directory access
	 */
	private static SecureDirectoryStream<Path> secure(DirectoryStream<Path> directory) throws IOException
	{
		if (directory instanceof SecureDirectoryStream<Path> secure)
			return secure;
		throw new IOException("Session requests require a file system with secure directory access");
	}

	/**
	 * Prevents construction.
	 */
	private RequestInputs()
	{
	}
}
