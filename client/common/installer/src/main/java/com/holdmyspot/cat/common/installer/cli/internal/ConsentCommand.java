package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installer.engine.PluginInstall;
import java.nio.file.Path;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * The install command with which the user allows the change that an install asked for: the same install, run by the
 * installer launcher of the version folder, with {@code --create-profile} before the operation. The command does not
 * start like the install that the harness's permission entries allow without asking, so the harness asks the user
 * before it runs.
 */
public final class ConsentCommand
{
	/**
	 * Matches a word that the shells of Linux and macOS take as it is.
	 */
	private static final Pattern PLAIN_WORD = Pattern.compile("[A-Za-z0-9_./:@%+=,-]+");

	/**
	 * Prevents instantiation.
	 */
	private ConsentCommand()
	{
	}

	/**
	 * Returns the command line.
	 *
	 * @param plugin    the version folder whose installer launcher runs the command
	 * @param platform  the running platform, whose shell the command is written for
	 * @param arguments the validated install operation that asked
	 * @return the command line, in the form that the installer's permission entries name the launcher: on Windows,
	 * 	{@code powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File} followed by the launcher in
	 * 	double quotes
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static String of(Path plugin, Platform platform, Arguments arguments)
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(arguments, "arguments").isNotNull();
		boolean windows = platform.isWindows();
		StringJoiner command = new StringJoiner(" ");
		if (windows)
			command.add("powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File");
		command.add(quote(PluginInstall.installer(plugin, windows).toString(), windows));
		if (arguments.home() != null)
			command.add("--home").add(quote(arguments.home().toString(), windows));
		command.add("--create-profile").add("install").add("--harness").add(arguments.harness());
		if (arguments.tag() != null)
			command.add(arguments.tag());
		return command.toString();
	}

	/**
	 * Quotes a path for the shell that runs the command.
	 *
	 * @param path    the path
	 * @param windows true for PowerShell, which gets the path in double quotes; otherwise the path is in single quotes
	 *                when it holds a character that the shell would interpret
	 * @return the shell word
	 */
	private static String quote(String path, boolean windows)
	{
		if (windows)
			return "\"" + path + "\"";
		if (PLAIN_WORD.matcher(path).matches())
			return path;
		return "'" + path.replace("'", "'\\''") + "'";
	}
}
