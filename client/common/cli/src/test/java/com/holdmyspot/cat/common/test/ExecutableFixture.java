package com.holdmyspot.cat.common.test;

import com.holdmyspot.cat.common.PortableFiles;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Writes the programs that tests run, so that each one can run as soon as it is written.
 * <p>
 * A process that the JVM starts inherits every file that the JVM holds open at that moment, and keeps it until the
 * new process closes the descriptors that it inherited. Linux refuses to run a file that any process holds open for
 * writing and reports {@code ETXTBSY} ("Text file busy"). Tests run in parallel, so when the JVM writes a program, a
 * process that another test starts during the write can keep the program open for writing after the write has ended,
 * and running the program then fails. On a file system with POSIX modes, a separate {@code cp} process therefore
 * writes each program, so the JVM never opens one for writing. The JVM opens for writing only a staged copy in the
 * temporary directory, which nothing runs. On Windows, the processes that the JVM starts inherit no file that the JVM
 * opens, so there the JVM writes the program itself.
 * <p>
 * Code under test that writes a program in the JVM exposes it in the same way. {@link #rewrite(Path)} moves such a
 * program onto a new file before a test runs it.
 * <p>
 * This class is thread-safe.
 */
public final class ExecutableFixture
{
	/**
	 * The mode of each program on a file system with POSIX modes: every user may read and run it.
	 */
	private static final Set<PosixFilePermission> MODE = Set.copyOf(PosixFilePermissions.fromString("rwxr-xr-x"));

	/**
	 * Writes a program from text.
	 *
	 * @param program the program's path; an existing file is replaced
	 * @param content the program's text, which is written as UTF-8
	 * @return {@code program}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the program cannot be written
	 */
	public static Path write(Path program, String content) throws IOException
	{
		requireThat(content, "content").isNotNull();
		return write(program, content.getBytes(UTF_8));
	}

	/**
	 * Writes a program from bytes.
	 *
	 * @param program the program's path; an existing file is replaced
	 * @param content the program's bytes
	 * @return {@code program}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the program cannot be written
	 */
	public static Path write(Path program, byte[] content) throws IOException
	{
		requireThat(program, "program").isNotNull();
		requireThat(content, "content").isNotNull();
		if (!writesThroughProcess(program))
		{
			Files.write(program, content);
			return program;
		}
		Path staged = Files.createTempFile("cat-program-", ".tmp");
		try
		{
			Files.write(staged, content);
			copyThroughProcess(staged, program);
			Files.setPosixFilePermissions(program, MODE);
		}
		finally
		{
			Files.deleteIfExists(staged);
		}
		return program;
	}

	/**
	 * Copies a file to a program, such as a copy of a native program.
	 *
	 * @param source  the file to copy
	 * @param program the program's path; an existing file is replaced
	 * @return {@code program}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the program cannot be written
	 */
	public static Path copy(Path source, Path program) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(program, "program").isNotNull();
		if (!writesThroughProcess(program))
		{
			Files.copy(source, program, StandardCopyOption.REPLACE_EXISTING);
			return program;
		}
		copyThroughProcess(source, program);
		Files.setPosixFilePermissions(program, MODE);
		return program;
	}

	/**
	 * Moves a program that code under test wrote onto a new file that the JVM never opened for writing, so that a test
	 * can run it at once. The content and mode stay the same. A process that another test started while the program was
	 * being written may still hold the old file open for writing; it keeps that file after the move, and the program's
	 * path names the new one. This concerns only the processes that tests running at the same time start; code under
	 * test writes the program as before.
	 *
	 * @param program the program's path
	 * @return {@code program}
	 * @throws NullPointerException if {@code program} is null
	 * @throws IOException          if the program cannot be read or replaced
	 */
	public static Path rewrite(Path program) throws IOException
	{
		requireThat(program, "program").isNotNull();
		if (!writesThroughProcess(program))
			return program;
		Set<PosixFilePermission> mode = Files.getPosixFilePermissions(program);
		// cp creates the replacement, because a file that the JVM created would be open for writing in the JVM
		Path replacement = program.resolveSibling(program.getFileName() + "." + UUID.randomUUID() + ".tmp");
		try
		{
			copyThroughProcess(program, replacement);
			Files.setPosixFilePermissions(replacement, mode);
			Files.move(replacement, program, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		finally
		{
			Files.deleteIfExists(replacement);
		}
		return program;
	}

	/**
	 * Indicates whether a separate process writes a program.
	 *
	 * @param program the program's path
	 * @return {@code true} if the program is on the default file system and that file system has POSIX modes
	 */
	private static boolean writesThroughProcess(Path program)
	{
		return program.getFileSystem().equals(FileSystems.getDefault()) && PortableFiles.hasModes(program);
	}

	/**
	 * Has {@code cp} copy a file to a program. Only POSIX systems, such as Linux and macOS, provide {@code cp}; Java
	 * offers no way to write a file that the processes that the JVM starts cannot inherit.
	 *
	 * @param source  the file to copy
	 * @param program the program's path
	 * @throws IOException if {@code cp} cannot be started or fails
	 */
	private static void copyThroughProcess(Path source, Path program) throws IOException
	{
		ProcessBuilder builder = new ProcessBuilder("cp", source.toAbsolutePath().toString(),
			program.toAbsolutePath().toString()).redirectErrorStream(true);
		try (Process process = builder.start())
		{
			String output = new String(process.getInputStream().readAllBytes(), UTF_8);
			int status = process.waitFor();
			if (status != 0)
			{
				throw new IOException("cp could not write " + program + " from " + source + ": exit status " +
					status + ", output: " + output);
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			InterruptedIOException failure = new InterruptedIOException("Interrupted while cp wrote " + program);
			failure.initCause(e);
			throw failure;
		}
	}

	/**
	 * Prevents instantiation.
	 */
	private ExecutableFixture()
	{
	}
}
