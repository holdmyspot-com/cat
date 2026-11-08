package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Finds a harness's command-line program on the user's search path for a first install to record.
 * <p>
 * Discovery takes the first program that the search path names, in whichever folder it lies: the user set up that
 * search path, and the program it names is the one the user runs. The recorded search path keeps the absolute entries
 * without repeats, and leaves out empty and relative entries, because install runs the recorded program from other
 * working directories, where those entries would name other folders. The program is recorded at the path where it was
 * found, without resolving the link that a harness re-points when it updates itself, so that the record still names
 * the harness after it updates. On Windows the program is {@code <name>.exe} or {@code <name>.cmd}, in the order that
 * {@code PATHEXT} gives, and a {@code .cmd} program runs through {@code %SystemRoot%\System32\cmd.exe /d /s /c}.
 * <p>
 * The recorded environment holds the harness's own variables, that search path and, on Windows, {@code SystemRoot},
 * without which Windows programs cannot load system libraries, {@code PATHEXT}, without which a batch file that a
 * package manager installs cannot find its interpreter, and {@code NoDefaultCurrentDirectoryInExePath=1}, so that
 * {@code cmd.exe} never runs a program from the working directory.
 */
public final class ExecutableDiscovery
{
	/**
	 * The extensions, in lower case, of the Windows programs that discovery records.
	 */
	private static final Set<String> WINDOWS_EXTENSIONS = Set.of(".exe", ".cmd");
	private final Platform platform;

	/**
	 * Creates a discovery.
	 *
	 * @param platform the platform that the harness program and the installer run on
	 * @throws NullPointerException if {@code platform} is null
	 */
	public ExecutableDiscovery(Platform platform)
	{
		requireThat(platform, "platform").isNotNull();
		this.platform = platform;
	}

	/**
	 * Finds the harness program and returns the command and environment that install records for it.
	 *
	 * @param program     the program's name, without a Windows extension
	 * @param environment the install process's environment variables
	 * @param variables   the harness's own variables that the recorded environment holds
	 * @return the path at which the program was found, with its command prefix, and its complete environment
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code program} is blank
	 * @throws ProgramNotFoundException if the program is not on the recorded search path; the message names the
	 *                                  program and the search path
	 * @throws IOException              if {@code SystemRoot} is not an absolute directory on Windows
	 */
	public HarnessCommand find(String program, Map<String, String> environment, Map<String, String> variables)
		throws IOException
	{
		requireThat(program, "program").isNotBlank();
		requireThat(environment, "environment").isNotNull();
		requireThat(variables, "variables").isNotNull();
		Map<String, String> lookup = platform.environment(environment);
		String searchPath = recordedSearchPath(lookup.getOrDefault("PATH", ""));
		Path found = findProgram(program, lookup, searchPath);

		Map<String, String> recorded = new HashMap<>(variables);
		recorded.put("PATH", searchPath);
		List<String> command = new ArrayList<>();
		if (platform.isWindows())
		{
			Path systemRoot = absolutePath(lookup.get("SystemRoot"));
			if (systemRoot == null)
				throw new IOException("SystemRoot must name an absolute directory, so that install can run " + found);
			recorded.put("SystemRoot", lookup.get("SystemRoot"));
			String extensions = lookup.get("PATHEXT");
			if (extensions != null)
				recorded.put("PATHEXT", extensions);
			recorded.put("NoDefaultCurrentDirectoryInExePath", "1");
			if (found.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cmd"))
				command.addAll(List.of(systemRoot.resolve("System32").resolve("cmd.exe").toString(), "/d", "/s", "/c"));
		}
		command.add(found.toString());
		return new HarnessCommand(command, recorded);
	}

	/**
	 * Keeps the search path entries that name the same folder from every working directory.
	 *
	 * @param searchPath the install process's search path
	 * @return the absolute, normalized entries, without repeats, in their original order
	 */
	private String recordedSearchPath(String searchPath)
	{
		String separator = separator();
		Set<Path> entries = new LinkedHashSet<>();
		for (String text : searchPath.split(separator, -1))
		{
			Path entry = absolutePath(text);
			if (entry != null)
				entries.add(entry);
		}
		StringJoiner joiner = new StringJoiner(separator);
		for (Path entry : entries)
			joiner.add(entry.toString());
		return joiner.toString();
	}

	/**
	 * Finds the program in the recorded search path.
	 *
	 * @param program    the program's name
	 * @param lookup     the install environment
	 * @param searchPath the recorded search path
	 * @return the absolute path at which the first match was found, with its links unresolved
	 * @throws ProgramNotFoundException if no program is found
	 */
	private Path findProgram(String program, Map<String, String> lookup, String searchPath)
		throws ProgramNotFoundException
	{
		Map<String, String> search = new HashMap<>();
		search.put("PATH", searchPath);
		if (platform.isWindows())
		{
			StringJoiner extensions = new StringJoiner(";");
			for (String extension : lookup.getOrDefault("PATHEXT", ".EXE;.CMD").split(";"))
			{
				String lowerCase = extension.strip().toLowerCase(Locale.ROOT);
				if (WINDOWS_EXTENSIONS.contains(lowerCase))
					extensions.add(lowerCase);
			}
			if (extensions.length() == 0)
				extensions.add(".exe").add(".cmd");
			search.put("PATHEXT", extensions.toString());
		}
		String found = platform.findProgram(program, search);
		if (found.equals(program))
		{
			throw new ProgramNotFoundException("The program \"" + program + "\" was not found on PATH in the " +
				"installer's environment (" + searchPath + ").");
		}
		return Path.of(found).toAbsolutePath().normalize();
	}

	/**
	 * Returns the platform's search path separator.
	 *
	 * @return {@code ;} on Windows, {@code :} elsewhere
	 */
	private String separator()
	{
		if (platform.isWindows())
			return ";";
		return ":";
	}

	/**
	 * Converts an environment value to an absolute path.
	 *
	 * @param value the value, or null
	 * @return the normalized absolute path, or null if {@code value} is null, blank, relative or not a path
	 */
	private static Path absolutePath(String value)
	{
		if (value == null || value.isBlank())
			return null;
		try
		{
			Path path = Path.of(value.strip());
			if (!path.isAbsolute())
				return null;
			return path.normalize();
		}
		catch (InvalidPathException _)
		{
			return null;
		}
	}
}
