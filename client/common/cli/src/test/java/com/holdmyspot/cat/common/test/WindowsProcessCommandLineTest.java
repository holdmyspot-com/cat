package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.WindowsCommandLine;
import com.holdmyspot.cat.common.WindowsProcessCommandLine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Tests {@link WindowsProcessCommandLine}, which only Windows can answer.
 */
public final class WindowsProcessCommandLineTest
{
	/**
	 * A process identifier that Windows gives to no process that a program can open: the system idle process.
	 */
	private static final long UNOPENABLE_PROCESS = 0;

	/**
	 * Creates a test instance.
	 */
	public WindowsProcessCommandLineTest()
	{
	}

	/**
	 * Verifies that the command line of a child that the test starts holds the program and the quoted argument that
	 * the test gave it.
	 *
	 * @throws IOException   if the child cannot be started or Windows cannot be asked
	 * @throws SkipException if the platform is not Windows
	 */
	@Test
	// destroyForcibly ends the child, whose streams the test never opens
	@SuppressWarnings("PMD.CloseResource")
	public void readsChildCommandLine() throws IOException
	{
		// Requirement: R-I35
		requireWindows();
		Process child = new ProcessBuilder("findstr.exe", "two words").start();
		try
		{
			Optional<String> line = WindowsProcessCommandLine.read(child.pid());

			requireThat(line, "line").isPresent();
			List<String> arguments = WindowsCommandLine.split(line.get());
			requireThat(arguments.size(), "arguments.size()").isEqualTo(2);
			requireThat(Path.of(arguments.getFirst()).getFileName().toString().toLowerCase(Locale.ROOT), "program").
				isEqualTo("findstr.exe");
			requireThat(arguments.get(1), "argument").isEqualTo("two words");
		}
		finally
		{
			child.destroyForcibly();
		}
	}

	/**
	 * Verifies that the command line of the test's own JVM names that JVM's program.
	 *
	 * @throws IOException   if Windows cannot be asked
	 * @throws SkipException if the platform is not Windows
	 */
	@Test
	public void readsOwnCommandLine() throws IOException
	{
		// Requirement: R-I35
		requireWindows();

		Optional<String> line = WindowsProcessCommandLine.read(ProcessHandle.current().pid());

		requireThat(line, "line").isPresent();
		List<String> arguments = WindowsCommandLine.split(line.get());
		String program = ProcessHandle.current().info().command().orElseThrow();
		// The launcher may have started the JVM by a name without the extension, which Windows adds
		requireThat(stem(arguments.getFirst()), "program").isEqualTo(stem(program));
	}

	/**
	 * Verifies that a process that Windows does not let the caller open gives no command line, and that no failure
	 * results.
	 *
	 * @throws IOException   if Windows cannot be asked
	 * @throws SkipException if the platform is not Windows
	 */
	@Test
	public void givesNothingForProcessThatCannotBeOpened() throws IOException
	{
		// Requirement: R-I35
		requireWindows();

		requireThat(WindowsProcessCommandLine.read(UNOPENABLE_PROCESS), "line").isEqualTo(Optional.empty());
	}

	/**
	 * Verifies that a process that has ended gives no command line.
	 *
	 * @throws IOException          if the child cannot be started or Windows cannot be asked
	 * @throws InterruptedException if the wait for the child is interrupted
	 * @throws SkipException        if the platform is not Windows
	 */
	@Test
	// The child has ended, and the test never opens its streams
	@SuppressWarnings("PMD.CloseResource")
	public void givesNothingForEndedProcess() throws IOException, InterruptedException
	{
		// Requirement: R-I35
		requireWindows();
		Process child = new ProcessBuilder("cmd.exe", "/c", "exit", "0").start();
		requireThat(child.waitFor(30, TimeUnit.SECONDS), "exited").isTrue();

		requireThat(WindowsProcessCommandLine.read(child.pid()), "line").isEqualTo(Optional.empty());
	}

	/**
	 * Returns the name of a program's file without its folder and its extension, in lower case.
	 *
	 * @param program the program's path or name
	 * @return the name
	 */
	private static String stem(String program)
	{
		String name = Path.of(program).getFileName().toString().toLowerCase(Locale.ROOT);
		if (name.endsWith(".exe"))
			return name.substring(0, name.length() - ".exe".length());
		return name;
	}

	/**
	 * Skips the test unless the platform is Windows.
	 *
	 * @throws SkipException if the platform is not Windows
	 */
	private static void requireWindows()
	{
		if (!Platform.current().isWindows())
			throw new SkipException("Only Windows can read a process's command line");
	}
}
