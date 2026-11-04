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
 * Finds a harness's command-line program on the user's search path for setup to record.
 * <p>
 * The recorded search path keeps only absolute entries outside the directories that a project, a download or the
 * installer itself controls: the setup working directory, the system temporary directory and those that
 * {@code TMPDIR}, {@code TEMP} and {@code TMP} name, the harness home, {@code <user home>/.cat}, the directory that
 * holds every harness's installer installation and this installation's data directory. Each directory is compared both
 * as written and with its links resolved. The program is recorded at the path where it was found, without resolving
 * the link that a harness re-points when it updates itself, and is refused when that link leads into one of those
 * directories. On Windows the program is {@code <name>.exe} or {@code <name>.cmd}, in the order that {@code PATHEXT}
 * gives, and a {@code .cmd} program runs through {@code %SystemRoot%\System32\cmd.exe /d /s /c}.
 * <p>
 * The recorded environment holds the harness's own variables, that search path and, on Windows, {@code SystemRoot},
 * without which Windows programs cannot load system libraries, {@code PATHEXT}, without which a batch file that a
 * package manager installs cannot find its interpreter, and {@code NoDefaultCurrentDirectoryInExePath=1}, so that
 * {@code cmd.exe} never runs a program from the working directory.
 */
public final class ExecutableDiscovery
{
	/**
	 * The variables that name a temporary directory on some platform.
	 */
	private static final List<String> TEMPORARY_VARIABLES = List.of("TMPDIR", "TEMP", "TMP");
	/**
	 * The extensions, in lower case, of the Windows programs that discovery records.
	 */
	private static final Set<String> WINDOWS_EXTENSIONS = Set.of(".exe", ".cmd");
	private final Platform platform;
	private final Path workingDirectory;
	private final Path temporaryDirectory;

	/**
	 * Creates a discovery.
	 *
	 * @param platform           the platform that the harness program and the installer run on
	 * @param workingDirectory   the setup process's working directory
	 * @param temporaryDirectory the system temporary directory
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if either directory is relative
	 */
	public ExecutableDiscovery(Platform platform, Path workingDirectory, Path temporaryDirectory)
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(workingDirectory, "workingDirectory").isAbsolute();
		requireThat(temporaryDirectory, "temporaryDirectory").isAbsolute();
		this.platform = platform;
		this.workingDirectory = workingDirectory.normalize();
		this.temporaryDirectory = temporaryDirectory.normalize();
	}

	/**
	 * Finds the harness program and returns the command and environment that setup records for it.
	 *
	 * @param program     the program's name, without a Windows extension
	 * @param home        the absolute, normalized harness home
	 * @param data        the installation's absolute data directory
	 * @param environment the setup process's environment variables
	 * @param variables   the harness's own variables that the recorded environment holds
	 * @return the path at which the program was found, with its command prefix, and its complete environment
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code program} is blank, or {@code home} or {@code data} is relative
	 * @throws IOException              if the user's home directory is unknown, {@code SystemRoot} is not an absolute
	 *                                  directory on Windows, the program is not on the recorded search path, or it
	 *                                  leads into a directory that discovery never records
	 */
	public HarnessCommand find(String program, Path home, Path data, Map<String, String> environment,
		Map<String, String> variables) throws IOException
	{
		requireThat(program, "program").isNotBlank();
		requireThat(home, "home").isAbsolute();
		requireThat(data, "data").isAbsolute();
		requireThat(environment, "environment").isNotNull();
		requireThat(variables, "variables").isNotNull();
		Map<String, String> lookup = platform.environment(environment);
		List<Path> excluded = excludedDirectories(home.normalize(), data.normalize(), lookup);
		String searchPath = recordedSearchPath(lookup.getOrDefault("PATH", ""), excluded);
		Path found = findProgram(program, lookup, searchPath, excluded);

		Map<String, String> recorded = new HashMap<>(variables);
		recorded.put("PATH", searchPath);
		List<String> command = new ArrayList<>();
		if (platform.isWindows())
		{
			Path systemRoot = absolutePath(lookup.get("SystemRoot"));
			if (systemRoot == null)
				throw new IOException("SystemRoot must name an absolute directory, so that setup can run " + found);
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
	 * Returns the directories whose programs discovery never records.
	 *
	 * @param home   the harness home
	 * @param data   the installation's data directory
	 * @param lookup the setup environment
	 * @return the directories, as written and with their existing ancestors' links resolved
	 * @throws IOException if the user's home directory is unknown
	 */
	private List<Path> excludedDirectories(Path home, Path data, Map<String, String> lookup) throws IOException
	{
		String homeVariable = platform.homeVariable();
		Path userHome = absolutePath(lookup.get(homeVariable));
		if (userHome == null)
		{
			throw new IOException(homeVariable + " must name an absolute directory, so that setup can keep " +
				"programs in <user home>/.cat off the harness program's search path.");
		}
		List<Path> excluded = new ArrayList<>(List.of(workingDirectory, temporaryDirectory, home,
			userHome.resolve(".cat"), data));
		if (data.getParent() != null)
			excluded.add(data.getParent());
		for (String name : TEMPORARY_VARIABLES)
		{
			Path directory = absolutePath(lookup.get(name));
			if (directory != null)
				excluded.add(directory);
		}
		for (Path directory : List.copyOf(excluded))
			excluded.add(resolved(directory));
		return excluded;
	}

	/**
	 * Keeps the search path entries that discovery may record.
	 *
	 * @param searchPath the setup process's search path
	 * @param excluded   the directories whose entries and entries below them are dropped
	 * @return the remaining absolute, normalized entries, without repeats, in their original order
	 */
	private String recordedSearchPath(String searchPath, List<Path> excluded)
	{
		String separator = separator();
		Set<Path> entries = new LinkedHashSet<>();
		for (String text : searchPath.split(separator, -1))
		{
			Path entry = absolutePath(text);
			if (entry != null && !isExcluded(entry, excluded))
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
	 * @param lookup     the setup environment
	 * @param searchPath the recorded search path
	 * @param excluded   the directories whose programs discovery never records
	 * @return the absolute path at which the first match was found, with its links unresolved
	 * @throws IOException if no program is found, or its links lead into an excluded directory
	 */
	private Path findProgram(String program, Map<String, String> lookup, String searchPath, List<Path> excluded)
		throws IOException
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
			throw new IOException("The program \"" + program + "\" is not on PATH (" + searchPath + "), outside the " +
				"working directory, the temporary directories, the harness home, <user home>/.cat and the installer's " +
				"data. Install the harness, or add its directory to PATH, and rerun setup.");
		}
		Path path = Path.of(found).toAbsolutePath().normalize();
		if (isExcluded(path, excluded))
		{
			throw new IOException("The program " + path + " resolves into a directory that setup never records, " +
				"because a project, a download or the installer could replace it there: " + resolved(path) + ". " +
				"Install the harness elsewhere and rerun setup.");
		}
		return path;
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
	 * Checks whether a path, as written or with its links resolved, lies in an excluded directory.
	 *
	 * @param path     an absolute, normalized path
	 * @param excluded the excluded directories
	 * @return true if the path is or lies below an excluded directory
	 */
	private static boolean isExcluded(Path path, List<Path> excluded)
	{
		Path real = resolved(path);
		for (Path directory : excluded)
		{
			if (path.startsWith(directory) || real.startsWith(directory))
				return true;
		}
		return false;
	}

	/**
	 * Resolves the links of a path's existing ancestors.
	 *
	 * @param path an absolute, normalized path
	 * @return the path below its nearest real ancestor, or {@code path} if that ancestor cannot be resolved
	 */
	private static Path resolved(Path path)
	{
		try
		{
			return Locations.resolveExistingAncestor(path);
		}
		catch (IOException _)
		{
			return path;
		}
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
