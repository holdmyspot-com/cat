package com.holdmyspot.cat.codex.layout;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.WindowsKnownFolders;
import com.holdmyspot.cat.common.WindowsRegistry;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Where Codex reads its machine-wide files, which an administrator manages for every user of the machine.
 * <p>
 * Codex reads the folder of {@code /etc/codex} on Linux and macOS. On Windows it reads {@code OpenAI\Codex} below the
 * program data folder that {@code SHGetKnownFolderPath(FOLDERID_ProgramData)} names, or below {@code C:\ProgramData}
 * when that call fails, and it never reads the {@code ProgramData} environment variable. Windows has no machine-wide
 * managed configuration file. The registry value {@code RequirementsPath} of {@code HKEY_LOCAL_MACHINE} key
 * {@code SOFTWARE\Policies\OpenAI\Codex}, read from the 64-bit view, moves the requirements file; nothing else moves
 * a machine-wide file.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe if the lookups that it was given are.
 */
public final class CodexSystemFolder
{
	/**
	 * The name of the machine-wide configuration file, which has the lowest precedence of the configuration layers.
	 */
	private static final String CONFIG_FILE = "config.toml";
	/**
	 * The name of the machine-wide managed configuration file, whose values override the session's own.
	 */
	private static final String MANAGED_CONFIG_FILE = "managed_config.toml";
	/**
	 * The name of the machine-wide requirements file, whose constraints bind the session.
	 */
	private static final String REQUIREMENTS_FILE = "requirements.toml";
	/**
	 * Codex's machine-wide folder on a platform whose paths start at a root.
	 */
	private static final String ROOTED_FOLDER = "/etc/codex";
	/**
	 * The program data folder that Codex falls back to when Windows does not name one.
	 */
	private static final String FALLBACK_PROGRAM_DATA = "C:\\ProgramData";
	/**
	 * The registry key below {@code HKEY_LOCAL_MACHINE} that holds Codex's policy values on Windows.
	 */
	private static final String POLICY_KEY = "SOFTWARE\\Policies\\OpenAI\\Codex";
	/**
	 * The registry value that moves the requirements file.
	 */
	private static final String REQUIREMENTS_VALUE = "RequirementsPath";

	/**
	 * Asks Windows for the program data folder.
	 */
	@FunctionalInterface
	public interface ProgramDataLookup
	{
		/**
		 * Returns the folder that {@code SHGetKnownFolderPath(FOLDERID_ProgramData)} names.
		 *
		 * @return the absolute folder
		 * @throws IOException if Windows cannot be asked or the call fails
		 */
		Path programData() throws IOException;
	}

	/**
	 * Reads the registry of Windows.
	 */
	@FunctionalInterface
	public interface RegistryLookup
	{
		/**
		 * Reads a text value of {@code HKEY_LOCAL_MACHINE}, from the 64-bit view of the registry.
		 *
		 * @param subkey the key below {@code HKEY_LOCAL_MACHINE}
		 * @param name   the value's name
		 * @return the value, or empty if the key or the value does not exist
		 * @throws IOException if the registry cannot be read for another reason
		 */
		Optional<String> machineText(String subkey, String name) throws IOException;
	}

	private final Path folder;
	private final boolean managedConfig;
	private final RegistryLookup registry;
	private final Optional<String> fallbackCause;

	/**
	 * Creates the folder.
	 *
	 * @param folder        the folder that holds the machine-wide files
	 * @param managedConfig {@code true} if the platform has a machine-wide managed configuration file
	 * @param registry      reads the value that moves the requirements file, or finds none if the platform has no
	 *                      such value
	 * @param fallbackCause why the folder is the one that Codex falls back to, or empty if Windows named it
	 */
	private CodexSystemFolder(Path folder, boolean managedConfig, RegistryLookup registry,
		Optional<String> fallbackCause)
	{
		this.folder = folder;
		this.managedConfig = managedConfig;
		this.registry = registry;
		this.fallbackCause = fallbackCause;
	}

	/**
	 * Returns the machine-wide folder of the machine that runs this process.
	 *
	 * @return the folder
	 */
	public static CodexSystemFolder ofThisMachine()
	{
		return of(Platform.current(), WindowsKnownFolders::programData, WindowsRegistry::machineText);
	}

	/**
	 * Returns the machine-wide folder of a platform.
	 *
	 * @param platform    the platform whose Codex reads the folder
	 * @param programData asks Windows for the program data folder; called only for Windows
	 * @param registry    reads the registry of Windows; called only for Windows
	 * @return the folder
	 * @throws NullPointerException if any argument is null
	 */
	public static CodexSystemFolder of(Platform platform, ProgramDataLookup programData, RegistryLookup registry)
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(programData, "programData").isNotNull();
		requireThat(registry, "registry").isNotNull();
		if (!platform.isWindows())
			return at(Path.of(ROOTED_FOLDER));
		Path base;
		Optional<String> cause = Optional.empty();
		try
		{
			base = programData.programData();
		}
		catch (IOException e)
		{
			base = Path.of(FALLBACK_PROGRAM_DATA);
			cause = Optional.of(e.getMessage());
		}
		Path folder = base.resolve("OpenAI").resolve("Codex");
		if (cause.isPresent())
		{
			cause = Optional.of("Windows did not name the program data folder (" + cause.get() + "), so CAT reads " +
				"Codex's machine-wide files from " + folder + ", where Codex itself looks when its own call fails");
		}
		return new CodexSystemFolder(folder, false, registry, cause);
	}

	/**
	 * Returns a folder that holds all three machine-wide files, such as a folder that a test or an installer names in
	 * place of the machine's.
	 *
	 * @param folder the folder
	 * @return the folder, which the registry never moves a file out of
	 * @throws NullPointerException if {@code folder} is null
	 */
	public static CodexSystemFolder at(Path folder)
	{
		requireThat(folder, "folder").isNotNull();
		return new CodexSystemFolder(folder, true, (_, _) -> Optional.empty(), Optional.empty());
	}

	/**
	 * Returns the folder that holds the machine-wide files.
	 *
	 * @return the folder
	 */
	public Path folder()
	{
		return folder;
	}

	/**
	 * Returns the machine-wide configuration file, which may not exist.
	 *
	 * @return the file
	 */
	public Path config()
	{
		return folder.resolve(CONFIG_FILE);
	}

	/**
	 * Returns the machine-wide managed configuration file, which may not exist.
	 *
	 * @return the file, or empty if the platform has none
	 */
	public Optional<Path> managedConfig()
	{
		if (managedConfig)
			return Optional.of(folder.resolve(MANAGED_CONFIG_FILE));
		return Optional.empty();
	}

	/**
	 * Returns the machine-wide requirements file, which may not exist. On Windows the registry value
	 * {@code RequirementsPath} moves it, and a key or value that does not exist leaves it in the folder.
	 *
	 * @return the file
	 * @throws IOException if the registry cannot be read, or its value is not an absolute path, so that CAT cannot
	 *                     tell which file Codex reads
	 */
	public Path requirements() throws IOException
	{
		Optional<String> value;
		try
		{
			value = registry.machineText(POLICY_KEY, REQUIREMENTS_VALUE);
		}
		catch (IOException e)
		{
			throw new IOException("CAT cannot read the registry value " + REQUIREMENTS_VALUE + " of HKLM\\" + POLICY_KEY +
				", which moves Codex's requirements file: " + e.getMessage(), e);
		}
		if (value.isEmpty())
			return folder.resolve(REQUIREMENTS_FILE);
		Path moved;
		try
		{
			moved = Path.of(value.get());
		}
		catch (InvalidPathException e)
		{
			throw new IOException("The registry value " + REQUIREMENTS_VALUE + " of HKLM\\" + POLICY_KEY + " is " +
				"not a path (" + value.get() + "), so CAT cannot tell where Codex's requirements file is", e);
		}
		if (!moved.isAbsolute())
		{
			throw new IOException("The registry value " + REQUIREMENTS_VALUE + " of HKLM\\" + POLICY_KEY + " is " +
				"the relative path " + value.get() + ", which Codex refuses, so CAT cannot tell where Codex's " +
				"requirements file is");
		}
		return moved;
	}

	/**
	 * Returns why the folder is the one that Codex falls back to, which CAT can only guess is the one that Codex reads.
	 *
	 * @return the reason, or empty if the platform named the folder
	 */
	public Optional<String> fallbackCause()
	{
		return fallbackCause;
	}
}
