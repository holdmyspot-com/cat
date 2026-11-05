package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_16LE;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Deletes the folders that the running installer's own files are in, once the installer no longer needs them. On
 * Linux and macOS the installer deletes them itself after it has written its result, because a running program's
 * files can be deleted there. Windows refuses to delete a file that a running program has open, so the installer
 * starts Windows PowerShell, which waits for the installer's process to exit and then deletes the folders, retrying
 * for a limited time. The commands are passed encoded on the command line, so no script file is left in a deleted
 * folder.
 * <p>
 * A process that the installer starts on Windows inherits the handles that the installer inherited from its
 * PowerShell launcher, among them the pipe through which the installer's caller reads the result, and the caller
 * waits for the pipe's end until every process that holds it has exited. So the installer starts a Windows PowerShell
 * that only starts the deleting Windows PowerShell through the Windows shell, which passes on no handle, and then
 * exits.
 */
public final class ExitDeletion
{
	/**
	 * The longest time for which Windows PowerShell retries deleting the folders after the installer's process exited.
	 */
	public static final Duration RETRY_LIMIT = Duration.ofSeconds(60);
	/**
	 * The pause between two attempts to delete the folders.
	 */
	private static final Duration RETRY_PAUSE = Duration.ofMillis(500);
	private final Platform platform;
	private final Map<String, String> environment;
	private final ProcessStarter starter;
	private final PrintStream stderr;

	/**
	 * Creates the deletion.
	 *
	 * @param platform    the running platform
	 * @param environment the installer's environment variables, which name the Windows folder in {@code SystemRoot}
	 * @param starter     starts Windows PowerShell
	 * @param stderr      receives a sentence for each folder that could not be deleted or handed over
	 * @throws NullPointerException if any of the arguments are null
	 */
	public ExitDeletion(Platform platform, Map<String, String> environment, ProcessStarter starter,
		PrintStream stderr)
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(starter, "starter").isNotNull();
		requireThat(stderr, "stderr").isNotNull();
		this.platform = platform;
		this.environment = environment;
		this.starter = starter;
		this.stderr = stderr;
	}

	/**
	 * Deletes the folders, or hands them to a program that deletes them once this process has exited. The caller
	 * loads no more of its own classes afterward.
	 *
	 * @param folders the absolute folders
	 * @throws NullPointerException if {@code folders} is null
	 */
	public void delete(List<Path> folders)
	{
		requireThat(folders, "folders").isNotNull();
		if (folders.isEmpty())
			return;
		if (!platform.isWindows())
		{
			for (Path folder : folders)
			{
				try
				{
					TemporaryDirectories.deleteRecursively(folder);
				}
				catch (IOException e)
				{
					stderr.println("CAT could not delete " + folder + " (" + e.getMessage() + "); delete it yourself.");
				}
			}
			return;
		}
		String systemRoot = platform.environment(environment).get("SystemRoot");
		if (systemRoot == null || systemRoot.isBlank())
		{
			stderr.println("CAT could not delete " + folders + " because SystemRoot does not name the Windows " +
				"folder; delete them yourself.");
			return;
		}
		List<String> command = command(systemRoot, ProcessHandle.current().pid(), folders);
		try
		{
			starter.start(command);
		}
		catch (IOException e)
		{
			stderr.println("CAT could not start " + command.getFirst() + " to delete " + folders + " (" +
				e.getMessage() + "); delete them yourself.");
		}
	}

	/**
	 * Returns the Windows PowerShell command that starts, through the Windows shell, a hidden Windows PowerShell that
	 * waits for a process to exit and then deletes folders.
	 *
	 * @param systemRoot the Windows folder
	 * @param pid        the process to wait for
	 * @param folders    the folders to delete
	 * @return the program's absolute path, then its arguments
	 */
	private static List<String> command(String systemRoot, long pid, List<Path> folders)
	{
		StringJoiner quoted = new StringJoiner(",");
		for (Path folder : folders)
			quoted.add(quote(folder.toString()));
		String script = "$ErrorActionPreference = 'SilentlyContinue'\n" +
			"Wait-Process -Id " + pid + "\n" +
			"$folders = @(" + quoted + ")\n" +
			"$deadline = [DateTime]::UtcNow.AddSeconds(" + RETRY_LIMIT.toSeconds() + ")\n" +
			"while ($true)\n" +
			"{\n" +
			"\t$folders = @($folders | Where-Object { [System.IO.Directory]::Exists($_) })\n" +
			"\tforeach ($folder in $folders) { [System.IO.Directory]::Delete($folder, $true) }\n" +
			"\t$folders = @($folders | Where-Object { [System.IO.Directory]::Exists($_) })\n" +
			"\tif ($folders.Count -eq 0 -or [DateTime]::UtcNow -ge $deadline) { break }\n" +
			"\tStart-Sleep -Milliseconds " + RETRY_PAUSE.toMillis() + "\n" +
			"}\n";
		String program = systemRoot.replaceAll("[\\\\/]+$", "") +
			"\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
		// Start-Process without redirection or -NoNewWindow starts the program through the Windows shell
		String starter = "Start-Process -WindowStyle Hidden -FilePath " + quote(program) +
			" -ArgumentList '-NoProfile','-NonInteractive','-EncodedCommand','" + encode(script) + "'\n";
		List<String> command = new ArrayList<>();
		command.add(program);
		command.add("-NoProfile");
		command.add("-NonInteractive");
		command.add("-EncodedCommand");
		command.add(encode(starter));
		return command;
	}

	/**
	 * Encodes a Windows PowerShell script as its {@code -EncodedCommand} option takes it.
	 *
	 * @param script the script
	 * @return the Base64 of the script's UTF-16LE bytes
	 */
	private static String encode(String script)
	{
		return Base64.getEncoder().encodeToString(script.getBytes(UTF_16LE));
	}

	/**
	 * Quotes a string as a Windows PowerShell literal, in which no character is special except the quotes, which
	 * PowerShell accepts in their typographic forms too.
	 *
	 * @param value the string
	 * @return the literal
	 */
	private static String quote(String value)
	{
		StringBuilder literal = new StringBuilder("'");
		for (char character : value.toCharArray())
		{
			literal.append(character);
			if (character == '\'' || character == '‘' || character == '’' || character == '‚' ||
				character == '‛')
			{
				literal.append(character);
			}
		}
		return literal.append('\'').toString();
	}
}
