package com.holdmyspot.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The token command of a session's model provider that prints the session's key file.
 * <p>
 * Codex starts a provider's token command itself, with no shell and with whatever {@code PATH} its own environment
 * has, so the command is the Java program of the runtime that runs the runner, named by its absolute path, started
 * with the runner's module path on this program. It therefore needs neither a program on a {@code PATH} that a session
 * may change nor a Unix tool that Windows lacks.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CodexKeyFileCommand
{
	/**
	 * The program and arguments of a token command.
	 *
	 * @param program   the absolute path of the program
	 * @param arguments the program's arguments
	 */
	public record Invocation(String program, List<String> arguments)
	{
		/**
		 * Creates an invocation.
		 *
		 * @param program   the absolute path of the program
		 * @param arguments the program's arguments
		 * @throws NullPointerException if any of the arguments are null
		 */
		public Invocation
		{
			requireThat(program, "program").isNotNull();
			arguments = List.copyOf(arguments);
		}
	}

	private static final String MODULE = "com.holdmyspot.cat.codex.cli";

	/**
	 * Prevents construction.
	 */
	private CodexKeyFileCommand()
	{
	}

	/**
	 * Returns the command that prints a key file.
	 *
	 * @param keyFile    the absolute path of the key file, which is the last argument
	 * @param platform   the platform that the command runs on
	 * @param javaHome   the home folder of the runtime that runs the runner
	 * @param modulePath the module path that the runner's program was started with, or {@code null} if its modules
	 *                   are in the runtime
	 * @return the invocation
	 * @throws NullPointerException if any of the arguments other than {@code modulePath} are null
	 */
	public static Invocation invocation(Path keyFile, Platform platform, Path javaHome, String modulePath)
	{
		requireThat(keyFile, "keyFile").isNotNull();
		return bundled(CodexKeyFileCommand.class, List.of(keyFile.toString()), platform, javaHome, modulePath);
	}

	/**
	 * Returns the command that runs a program of this module with the runtime that runs the runner.
	 *
	 * @param program    the class, of this module, whose {@code main} method the command runs
	 * @param arguments  the arguments of {@code main}
	 * @param platform   the platform that the command runs on
	 * @param javaHome   the home folder of the runtime that runs the runner
	 * @param modulePath the module path that the runner's program was started with, or {@code null} if its modules
	 *                   are in the runtime
	 * @return the invocation, whose program is the runtime's Java program by its absolute path
	 * @throws NullPointerException if any of the arguments other than {@code modulePath} are null
	 */
	static Invocation bundled(Class<?> program, List<String> arguments, Platform platform, Path javaHome,
		String modulePath)
	{
		requireThat(program, "program").isNotNull();
		requireThat(arguments, "arguments").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(javaHome, "javaHome").isNotNull();
		String name = "java";
		if (platform.isWindows())
			name = "java.exe";
		List<String> command = new ArrayList<>(List.of("-XX:+PerfDisableSharedMem",
			"--enable-native-access=com.holdmyspot.cat.common.cli"));
		if (modulePath != null)
		{
			command.add("--module-path");
			command.add(modulePath);
		}
		command.add("-m");
		command.add(MODULE + "/" + program.getName());
		command.addAll(arguments);
		return new Invocation(javaHome.resolve("bin").resolve(name).toString(), command);
	}

	/**
	 * Returns the command that prints a key file, for the runtime and platform that this program runs on.
	 *
	 * @param keyFile the absolute path of the key file
	 * @return the invocation
	 * @throws NullPointerException if {@code keyFile} is null
	 */
	public static Invocation invocation(Path keyFile)
	{
		return invocation(keyFile, Platform.current(), Path.of(System.getProperty("java.home")),
			System.getProperty("jdk.module.path"));
	}

	/**
	 * Prints the bytes of the key file that the argument names to standard output, and nothing else. Exits with status 1
	 * and a message on standard error if the file cannot be read.
	 *
	 * @param args the key file's path
	 */
	public static void main(String[] args)
	{
		args = CommandLineArguments.recover(args);
		if (args.length != 1)
		{
			System.err.println("Usage: " + CodexKeyFileCommand.class.getName() + " <key file>");
			System.exit(1);
		}
		try
		{
			System.out.write(Files.readAllBytes(Path.of(args[0])));
			System.out.flush();
		}
		catch (IOException e)
		{
			System.err.println("Cannot read the key file " + args[0] + ": " + e.getClass().getSimpleName());
			System.exit(1);
		}
	}
}
