package io.github.cowwoc.cat.common.test;

import io.github.cowwoc.cat.common.PortableFiles;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies that {@link PortableFiles} reads and sets file modes, and forces directories, on file systems with and
 * without POSIX permissions.
 */
public final class PortableFilesTest
{
	private static final Set<PosixFilePermission> WRITABLE = PosixFilePermissions.fromString("rw-r--r--");
	private static final Set<PosixFilePermission> READ_ONLY = PosixFilePermissions.fromString("r--r--r--");
	private static final Set<PosixFilePermission> EXECUTABLE = PosixFilePermissions.fromString("rwxr-xr-x");

	/**
	 * Creates the test fixture.
	 */
	public PortableFilesTest()
	{
	}

	/**
	 * Verifies that a file given the mode {@code rw-r--r--} reads back with that mode on every file system.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void readsWritableMode() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("portable-files-"));
			Path file = Files.writeString(directory.resolve("file.txt"), "content");

			PortableFiles.setMode(file, WRITABLE);

			requireThat(PortableFiles.mode(file), "mode").isEqualTo(WRITABLE);
		}
	}

	/**
	 * Verifies that a file whose name ends with an extension that Windows runs, in any letter case, reads back as
	 * executable on every file system.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void readsExecutableModeOfWindowsProgram() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("portable-files-"));
			Path file = Files.writeString(directory.resolve("tool.Cmd"), "@echo off");

			PortableFiles.setMode(file, EXECUTABLE);

			requireThat(PortableFiles.mode(file), "mode").isEqualTo(EXECUTABLE);
		}
	}

	/**
	 * Verifies that a mode that removes write access applies only where the file system has POSIX permissions, and
	 * that the file can be deleted either way.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void setsModeOnlyWhereSupported() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("portable-files-"));
			Path file = Files.writeString(directory.resolve("file.txt"), "content");
			Set<PosixFilePermission> expected = WRITABLE;
			if (PortableFiles.hasModes(file))
				expected = READ_ONLY;

			PortableFiles.setMode(file, READ_ONLY);

			requireThat(PortableFiles.mode(file), "mode").isEqualTo(expected);
			Files.delete(file);
		}
	}

	/**
	 * Verifies that forcing a directory to the storage device succeeds on every file system.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void forcesDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("portable-files-"));

			PortableFiles.forceDirectory(directory);
		}
	}
}
