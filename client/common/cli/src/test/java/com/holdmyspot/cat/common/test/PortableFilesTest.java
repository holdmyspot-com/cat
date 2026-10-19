package com.holdmyspot.cat.common.test;

import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies that {@link PortableFiles} reads and sets file modes, forces directories and replaces files, on file systems
 * with and without POSIX permissions.
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

	/**
	 * Verifies that a file that another thread is reading is replaced once that thread closes it. Windows refuses to
	 * replace a file while it is open.
	 *
	 * @throws IOException          if the test directory cannot be used
	 * @throws InterruptedException if the test is interrupted while it waits for the reader
	 */
	@Test(timeOut = 10_000)
	public void replacesFileThatAnotherThreadReads() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("portable-files-"));
			Path target = Files.writeString(directory.resolve("run.json"), "old");
			Path source = Files.writeString(directory.resolve("run.json.tmp"), "new");
			InputStream reader = Files.newInputStream(target);
			Thread closer = Thread.ofPlatform().start(() ->
			{
				try (reader)
				{
					Thread.sleep(300);
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
				catch (InterruptedException _)
				{
					Thread.currentThread().interrupt();
				}
			});

			PortableFiles.replace(source, target);
			closer.join();

			requireThat(Files.readString(target), "content").isEqualTo("new");
			requireThat(Files.exists(source), "sourceExists").isFalse();
		}
	}
}
