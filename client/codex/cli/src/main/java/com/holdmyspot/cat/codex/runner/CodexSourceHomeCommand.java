package com.holdmyspot.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a source provider's own token command with the source home as its home folder.
 * <p>
 * Codex starts a provider's token command itself and with no shell, and the command has to read the source's files
 * through its home folder, so the session's token command is this program, started like
 * {@link CodexKeyFileCommand}'s with the runtime's Java program by its absolute path. It sets the variables that name
 * the home folder, runs the source command with its arguments in the folder that the program itself started in, with
 * the program's own standard streams, and exits with the source command's status. It needs neither Unix's
 * {@code env} nor a {@code PATH} lookup of its own.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CodexSourceHomeCommand
{
	/**
	 * The status that a shell gives when it cannot find the program that it was asked to run.
	 */
	private static final int NOT_FOUND = 127;
	/**
	 * The status that a shell gives when it finds a program that it cannot run.
	 */
	private static final int NOT_RUNNABLE = 126;

	/**
	 * Prevents construction.
	 */
	private CodexSourceHomeCommand()
	{
	}

	/**
	 * Returns the variables that name a home folder on a platform.
	 *
	 * @param home     the home folder
	 * @param platform the platform
	 * @return {@code HOME}, and also {@code USERPROFILE} on Windows
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static Map<String, String> homeVariables(Path home, Platform platform)
	{
		requireThat(home, "home").isNotNull();
		requireThat(platform, "platform").isNotNull();
		Map<String, String> variables = new LinkedHashMap<>();
		variables.put("HOME", home.toString());
		if (platform.isWindows())
			variables.put("USERPROFILE", home.toString());
		return variables;
	}

	/**
	 * Returns the command that runs a source command with a home folder.
	 *
	 * @param home       the source home, which the source command sees as its home folder
	 * @param program    the source command's program, which the operating system finds as it does for any program
	 * @param arguments  the source command's arguments
	 * @param platform   the platform that the command runs on
	 * @param javaHome   the home folder of the runtime that runs the runner
	 * @param modulePath the module path that the runner's program was started with, or {@code null} if its modules
	 *                   are in the runtime
	 * @return the invocation
	 * @throws NullPointerException if any of the arguments other than {@code modulePath} are null
	 */
	public static CodexKeyFileCommand.Invocation invocation(Path home, String program, List<String> arguments,
		Platform platform, Path javaHome, String modulePath)
	{
		requireThat(home, "home").isNotNull();
		requireThat(program, "program").isNotNull();
		requireThat(arguments, "arguments").isNotNull();
		List<String> mainArguments = new ArrayList<>();
		mainArguments.add(home.toString());
		mainArguments.add(program);
		mainArguments.addAll(arguments);
		return CodexKeyFileCommand.bundled(CodexSourceHomeCommand.class, mainArguments, platform, javaHome, modulePath);
	}

	/**
	 * Returns the command that runs a source command with a home folder, for the runtime and platform that this program
	 * runs on.
	 *
	 * @param home      the source home
	 * @param program   the source command's program
	 * @param arguments the source command's arguments
	 * @return the invocation
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static CodexKeyFileCommand.Invocation invocation(Path home, String program, List<String> arguments)
	{
		return invocation(home, program, arguments, Platform.current(), Path.of(System.getProperty("java.home")),
			System.getProperty("jdk.module.path"));
	}

	/**
	 * Runs the command that the arguments name, after the home folder, and exits with its status.
	 *
	 * @param args the home folder, the program and the program's arguments
	 */
	// The launcher exits with the process's status, so the process ends with it
	@SuppressWarnings("PMD.CloseResource")
	public static void main(String[] args)
	{
		args = CommandLineArguments.recover(args);
		if (args.length < 2)
		{
			System.err.println("Usage: " + CodexSourceHomeCommand.class.getName() + " <home> <program> [<argument>...]");
			System.exit(1);
		}
		ProcessBuilder builder = new ProcessBuilder(List.of(args).subList(1, args.length)).inheritIO();
		builder.environment().putAll(homeVariables(Path.of(args[0]), Platform.current()));
		Process process;
		try
		{
			process = builder.start();
		}
		catch (IOException e)
		{
			System.err.println(args[1] + ": cannot run the program: " + e.getMessage());
			if (Files.exists(Path.of(args[1])))
				System.exit(NOT_RUNNABLE);
			System.exit(NOT_FOUND);
			return;
		}
		// A launcher that Codex stops at its timeout takes the source command with it
		Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
		try
		{
			System.exit(process.waitFor());
		}
		catch (InterruptedException _)
		{
			process.destroy();
			System.exit(1);
		}
	}
}
