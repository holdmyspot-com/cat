package com.holdmyspot.cat.common.updater.engine;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Creates protected state and atomically replaces its bounded records. Never follows a symbolic link in an updater
 * write path; another process must not mutate those paths while an operation holds the harness lock.
 */
final class StateFiles
{
	/**
	 * Owner-only access for the updater's state directories.
	 */
	static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
	/**
	 * Owner-only access for the updater's state records.
	 */
	static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");

	/**
	 * Prevents instantiation.
	 */
	private StateFiles()
	{
	}

	/**
	 * Rejects links in existing ancestors before writing below a path.
	 *
	 * @param path the absolute path to inspect
	 * @throws IOException if an ancestor is a link
	 */
	static void requireNoLinks(Path path) throws IOException
	{
		for (Path current = path.toAbsolutePath(); current != null; current = current.getParent())
		{
			if (Files.isSymbolicLink(current))
				throw new IOException("The updater write path passes through a symbolic link: " + current);
		}
	}

	/**
	 * Creates owner-only directories, rejecting links in existing ancestors.
	 *
	 * @param directory the directory to create
	 * @throws IOException if creating the directory fails
	 */
	static void createDirectories(Path directory) throws IOException
	{
		requireNoLinks(directory);
		if (Files.exists(directory, NOFOLLOW_LINKS))
		{
			if (!Files.isDirectory(directory, NOFOLLOW_LINKS))
				throw new IOException("The updater directory is not a directory: " + directory);
			return;
		}
		Path parent = directory.getParent();
		if (parent != null)
			createDirectories(parent);
		if (PortableFiles.hasModes(directory))
			Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
		else
			Files.createDirectory(directory);
	}

	/**
	 * Creates a fresh private staging directory beside its eventual activation target.
	 *
	 * @param parent the staging parent
	 * @param prefix the filename prefix
	 * @return the newly created directory
	 * @throws IOException if creating it fails
	 */
	static Path createTemporaryDirectory(Path parent, String prefix) throws IOException
	{
		createDirectories(parent);
		if (PortableFiles.hasModes(parent))
			return Files.createTempDirectory(parent, prefix, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
		return Files.createTempDirectory(parent, prefix);
	}

	/**
	 * Replaces a state record through an owner-only file, forcing its bytes and parent directory.
	 *
	 * @param file    the record to replace
	 * @param content the complete bounded content
	 * @throws IOException if creating, forcing or replacing the record fails
	 */
	static void write(Path file, byte[] content) throws IOException
	{
		requireNoLinks(file);
		createDirectories(file.getParent());
		Path temporary;
		if (PortableFiles.hasModes(file))
		{
			temporary = Files.createTempFile(file.getParent(), ".cat-record-", ".new",
				PosixFilePermissions.asFileAttribute(FILE_MODE));
		}
		else
			temporary = Files.createTempFile(file.getParent(), ".cat-record-", ".new");
		try
		{
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE))
			{
				ByteBuffer buffer = ByteBuffer.wrap(content);
				while (buffer.hasRemaining())
					channel.write(buffer);
				channel.force(true);
			}
			Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING);
			PortableFiles.forceDirectory(file.getParent());
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Deletes a tree without following links.
	 *
	 * @param path the operation-owned tree or file
	 * @throws IOException if deletion fails
	 */
	static void delete(Path path) throws IOException
	{
		TemporaryDirectories.deleteRecursively(path);
	}
}
