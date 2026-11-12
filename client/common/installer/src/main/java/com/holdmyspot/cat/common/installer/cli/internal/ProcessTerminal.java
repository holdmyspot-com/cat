package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * The terminal that controls the installer process. On Linux and macOS it is {@code /dev/tty}, which the entry
 * script's installer reaches even though the script reads itself from a pipe on standard input; a process without a
 * controlling terminal, such as a command that an agent runs, cannot open it. On Windows it is the console, when both
 * standard input and standard output are attached to it.
 */
public final class ProcessTerminal implements Terminal
{
	/**
	 * The controlling terminal of the process on Linux and macOS.
	 */
	private static final Path TTY = Path.of("/dev/tty");
	private final Platform platform;

	/**
	 * Creates the terminal of the running process.
	 *
	 * @param platform the running platform
	 * @throws NullPointerException if {@code platform} is null
	 */
	public ProcessTerminal(Platform platform)
	{
		requireThat(platform, "platform").isNotNull();
		this.platform = platform;
	}

	@Override
	public Optional<String> ask(String question) throws IOException
	{
		requireThat(question, "question").isNotNull();
		if (platform.isWindows())
		{
			Console console = System.console();
			if (console == null || !console.isTerminal())
				return Optional.empty();
			return Optional.ofNullable(console.readLine("%s", question));
		}
		OutputStream out;
		try
		{
			out = Files.newOutputStream(TTY, StandardOpenOption.WRITE);
		}
		catch (FileSystemException _)
		{
			// The process has no controlling terminal
			return Optional.empty();
		}
		try (out; BufferedReader in = new BufferedReader(new InputStreamReader(Files.newInputStream(TTY), UTF_8)))
		{
			out.write(question.getBytes(UTF_8));
			out.flush();
			return Optional.ofNullable(in.readLine());
		}
	}
}
