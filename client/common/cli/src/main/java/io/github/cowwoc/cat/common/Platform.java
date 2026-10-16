package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.that;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A supported operating-system and CPU-architecture combination for CAT release archives.
 */
public enum Platform
{
	LINUX_X64("linux-x64"),
	LINUX_ARM64("linux-arm64"),
	MACOS_X64("macos-x64"),
	MACOS_ARM64("macos-arm64"),
	WINDOWS_X64("windows-x64"),
	WINDOWS_ARM64("windows-arm64");

	/**
	 * Detects the current platform.
	 *
	 * @return the current platform
	 * @throws UnsupportedOperationException if the platform is unsupported
	 */
	public static Platform current()
	{
		return detect(System.getProperty("os.name"), System.getProperty("os.arch"));
	}

	/**
	 * Detects a platform from operating-system properties.
	 *
	 * @param osName a non-blank operating-system name
	 * @param architecture a non-blank operating-system architecture
	 * @return the matching platform
	 * @throws NullPointerException if {@code osName} or {@code architecture} are null
	 * @throws IllegalArgumentException if {@code osName} or {@code architecture} are blank
	 * @throws UnsupportedOperationException if the platform is unsupported
	 */
	public static Platform detect(String osName, String architecture)
	{
		requireThat(osName, "osName").isNotBlank();
		requireThat(architecture, "architecture").isNotBlank();

		String normalizedOs = osName.toLowerCase(Locale.ROOT);
		String normalizedArchitecture = architecture.toLowerCase(Locale.ROOT);
		Platform x64Platform = null;
		Platform arm64Platform = null;
		if (normalizedOs.equals("linux"))
		{
			x64Platform = LINUX_X64;
			arm64Platform = LINUX_ARM64;
		}
		else if (normalizedOs.equals("mac os x") || normalizedOs.equals("darwin"))
		{
			x64Platform = MACOS_X64;
			arm64Platform = MACOS_ARM64;
		}
		else if (normalizedOs.startsWith("windows"))
		{
			x64Platform = WINDOWS_X64;
			arm64Platform = WINDOWS_ARM64;
		}
		Platform platform = switch (normalizedArchitecture)
		{
			case "amd64", "x86_64" -> x64Platform;
			case "aarch64", "arm64" -> arm64Platform;
			default -> null;
		};
		if (platform != null)
			return platform;

		throw new UnsupportedOperationException("Unsupported platform: os.name=" + osName + ", os.arch=" + architecture);
	}

	/**
	 * The program extensions that Windows uses when {@code PATHEXT} is not set.
	 */
	private static final String DEFAULT_PROGRAM_EXTENSIONS = ".COM;.EXE;.BAT;.CMD";
	/**
	 * The extensions, in lower case, of the files that a Windows process launch starts without another program.
	 */
	private static final Set<String> STARTABLE_EXTENSIONS = Set.of(".com", ".exe", ".bat", ".cmd");

	private final String id;

	/**
	 * Creates a platform.
	 *
	 * @param id a non-blank identifier used in distribution filenames
	 */
	Platform(String id)
	{
		assert that(id, "id").isNotBlank().elseThrow();
		this.id = id;
	}

	/**
	 * Indicates whether the platform is Windows.
	 *
	 * @return {@code true} for Windows
	 */
	public boolean isWindows()
	{
		return this == WINDOWS_X64 || this == WINDOWS_ARM64;
	}

	/**
	 * Returns the environment variable that names the current user's home directory. Windows sets
	 * {@code USERPROFILE}, which Windows programs such as Codex and Claude Code read; only POSIX layers such as Git Bash
	 * and Cygwin set {@code HOME} there, so a program that a Windows program starts, such as a Codex hook, may lack it.
	 *
	 * @return {@code USERPROFILE} on Windows, otherwise {@code HOME}
	 */
	public String homeVariable()
	{
		if (isWindows())
			return "USERPROFILE";
		return "HOME";
	}

	/**
	 * Returns the current user's home directory, which a process environment names in {@link #homeVariable()}.
	 *
	 * @param environment the process environment
	 * @return the home directory
	 * @throws NullPointerException if {@code environment} is null
	 * @throws IOException if the environment does not set the home variable
	 */
	public Path homeDirectory(Map<String, String> environment) throws IOException
	{
		requireThat(environment, "environment").isNotNull();
		String variable = homeVariable();
		String home = environment.get(variable);
		if (home == null || home.isBlank())
			throw new IOException(variable + " is not set");
		return Path.of(home);
	}

	/**
	 * Returns a process environment as this platform's programs read it. Windows matches a variable's name regardless
	 * of case and names the program search path {@code Path}, but the map that {@link System#getenv()} returns matches
	 * only the exact name, so a lookup of {@code PATH} there finds nothing.
	 *
	 * @param variables the process environment, such as {@link System#getenv()}
	 * @return on Windows, an unmodifiable copy that matches names regardless of case; otherwise {@code variables}
	 * @throws NullPointerException if {@code variables} is null
	 */
	public Map<String, String> environment(Map<String, String> variables)
	{
		requireThat(variables, "variables").isNotNull();
		if (!isWindows())
			return variables;
		Map<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		copy.putAll(variables);
		return Collections.unmodifiableMap(copy);
	}

	/**
	 * Finds the file that starts a program, as this platform's shell finds it in a child process's environment. Java's
	 * process launch searches the parent's folders rather than the child's, and on Windows adds only {@code .exe} to a
	 * name without an extension, so it cannot start a program that npm installs as a batch file, such as
	 * {@code codex.cmd}. On Windows, this search instead tries, in the folders of the child's {@code Path} or at the
	 * path that the program names, each extension of {@code PATHEXT} that Windows starts without another program; a
	 * file without such an extension, such as the POSIX script that npm installs beside the batch file, is not a match.
	 * Elsewhere, it finds an executable file by the program's name in the folders of the child's {@code PATH}.
	 *
	 * @param program     a program's name or path
	 * @param environment the environment of the process that starts the program
	 * @return the path of the first match, or {@code program} if nothing matches or, outside Windows, the program
	 *   names a path
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code program} is blank
	 */
	public String findProgram(String program, Map<String, String> environment)
	{
		requireThat(program, "program").isNotBlank();
		requireThat(environment, "environment").isNotNull();
		if (!isWindows())
			return findPosixProgram(program, environment);
		Map<String, String> variables = environment(environment);
		List<String> extensions = new ArrayList<>();
		for (String extension : variables.getOrDefault("PATHEXT", DEFAULT_PROGRAM_EXTENSIONS).split(";"))
		{
			String lowerCase = extension.strip().toLowerCase(Locale.ROOT);
			if (STARTABLE_EXTENSIONS.contains(lowerCase))
				extensions.add(lowerCase);
		}
		List<Path> files = new ArrayList<>();
		if (program.contains("\\") || program.contains("/"))
			addFile(files, "", program);
		else
		{
			for (String folder : variables.getOrDefault("PATH", "").split(";"))
			{
				if (!folder.isBlank())
					addFile(files, folder.strip(), program);
			}
		}
		String lowerCaseProgram = program.toLowerCase(Locale.ROOT);
		boolean hasExtension = extensions.stream().anyMatch(lowerCaseProgram::endsWith);
		for (Path file : files)
		{
			if (hasExtension)
			{
				if (Files.isRegularFile(file))
					return file.toString();
				continue;
			}
			for (String extension : extensions)
			{
				Path withExtension = file.resolveSibling(file.getFileName() + extension);
				if (Files.isRegularFile(withExtension))
					return withExtension.toString();
			}
		}
		return program;
	}

	/**
	 * Finds an executable file by a program's name in the folders of a POSIX {@code PATH}.
	 *
	 * @param program     a program's name or path
	 * @param environment the environment of the process that starts the program
	 * @return the path of the first match, or {@code program} if nothing matches or the program names a path
	 */
	private static String findPosixProgram(String program, Map<String, String> environment)
	{
		if (program.contains("/"))
			return program;
		List<Path> files = new ArrayList<>();
		for (String folder : environment.getOrDefault("PATH", "").split(":"))
		{
			if (!folder.isEmpty())
				addFile(files, folder, program);
		}
		for (Path file : files)
		{
			if (Files.isRegularFile(file) && Files.isExecutable(file))
				return file.toString();
		}
		return program;
	}

	/**
	 * Adds the path of a program in a folder, unless the platform cannot name it.
	 *
	 * @param files   the paths to add to
	 * @param folder  the folder, or an empty string for the working directory
	 * @param program the program's name or path
	 */
	private static void addFile(List<Path> files, String folder, String program)
	{
		try
		{
			files.add(Path.of(folder).resolve(program));
		}
		catch (InvalidPathException _)
		{
			// A folder that the platform cannot name holds no program
		}
	}

	/**
	 * Returns the identifier used in distribution filenames.
	 *
	 * @return the identifier
	 */
	public String id()
	{
		return id;
	}
}
