package com.holdmyspot.cat.codex.layout;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

/**
 * Where Codex keeps CAT's installation. Codex copies the marketplace plugin into its plugin cache, one folder per
 * plugin version.
 *
 * @param home Codex's absolute home directory
 */
public record CodexInstallation(Path home) implements HarnessInstallation
{
	/**
	 * The name of Codex's home directory, relative to the directory that holds it.
	 */
	private static final Path DEFAULT_HOME = Path.of(".codex");
	/**
	 * The name of Codex's configuration file, relative to Codex's home.
	 */
	private static final Path CONFIGURATION = Path.of("config.toml");
	/**
	 * The environment variable that moves Codex's home.
	 */
	private static final String HOME_VARIABLE = "CODEX_HOME";

	/**
	 * Creates Codex's installation.
	 *
	 * @param home Codex's absolute home directory
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 */
	public CodexInstallation
	{
		requireThat(home, "home").isAbsolute();
		home = home.normalize();
	}

	/**
	 * Locates Codex's installation in the home that {@link #resolveHome(Map, Path, Path)} finds, except that a
	 * {@code CODEX_HOME} naming a folder that does not exist is accepted, because the installer creates the folder.
	 * The working directory is the process's.
	 *
	 * @param environment the environment variables
	 * @param userHome    the user's absolute home directory, which Codex reads from the account when the environment
	 *                    names none
	 * @return the installation
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code userHome} is relative, or {@code CODEX_HOME} names a file or a folder
	 *                                  that cannot be read
	 */
	public static CodexInstallation fromEnvironment(Map<String, String> environment, Path userHome)
	{
		requireThat(userHome, "userHome").isAbsolute();
		Path workingDirectory = Path.of("").toAbsolutePath();
		try
		{
			return new CodexInstallation(resolveHome(environment, userHome, workingDirectory));
		}
		catch (NoSuchFileException e)
		{
			return new CodexInstallation(Path.of(e.getFile()));
		}
		catch (IOException e)
		{
			throw new IllegalArgumentException(e.getMessage(), e);
		}
	}

	/**
	 * Locates Codex's home as {@link #resolveHome(Map, Path, Path)} does, in the process's working directory and with
	 * the home directory that the Java runtime reports for the current account.
	 *
	 * @param environment the environment variables
	 * @return Codex's absolute home directory
	 * @throws NullPointerException if {@code environment} is null
	 * @throws IOException          if Codex would fail to find its home
	 */
	public static Path resolveHome(Map<String, String> environment) throws IOException
	{
		String account = System.getProperty("user.home");
		Path accountHome = null;
		if (account != null && !account.isEmpty())
			accountHome = Path.of(account);
		return resolveHome(environment, accountHome, Path.of("").toAbsolutePath());
	}

	/**
	 * Locates Codex's home the way Codex does.
	 * <ul>
	 *   <li>A {@code CODEX_HOME} that is set and not empty names the home, even when it is blank. A relative value is
	 *   taken from {@code workingDirectory}. The folder must exist and be a directory. The home is its real path,
	 *   with symbolic links followed.</li>
	 *   <li>Otherwise the home is {@code .codex} in the user's home directory, whether or not it exists. The user's
	 *   home directory is the one that the platform's home variable names, unless it is unset or empty, and then
	 *   {@code accountHome}. A relative one is taken from {@code workingDirectory}, and the {@code ..} elements of
	 *   the result are removed.</li>
	 * </ul>
	 *
	 * @param environment      the environment variables
	 * @param accountHome      the user's home directory in the account database, which Codex reads when the
	 *                         environment names none, or {@code null} if the account has none
	 * @param workingDirectory the absolute working directory of the process that Codex runs in
	 * @return Codex's absolute, normalized home directory
	 * @throws NullPointerException     if {@code environment} or {@code workingDirectory} are null
	 * @throws IllegalArgumentException if {@code workingDirectory} is relative
	 * @throws NoSuchFileException      if {@code CODEX_HOME} names a folder that does not exist
	 * @throws IOException              if {@code CODEX_HOME} names a file or a folder that cannot be read, or the
	 *                                  environment and the account name no user home
	 */
	public static Path resolveHome(Map<String, String> environment, Path accountHome, Path workingDirectory)
		throws IOException
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(workingDirectory, "workingDirectory").isAbsolute();
		String configured = environment.get(HOME_VARIABLE);
		if (configured != null && !configured.isEmpty())
			return configuredHome(configured, workingDirectory);
		String userHomeVariable = Platform.current().homeVariable();
		String userHome = environment.get(userHomeVariable);
		Path base = accountHome;
		if (userHome != null && !userHome.isEmpty())
		{
			try
			{
				base = Path.of(userHome);
			}
			catch (InvalidPathException e)
			{
				throw new IOException(userHomeVariable + " is not a valid path: \"" + userHome + "\"", e);
			}
		}
		if (base == null)
		{
			throw new IOException("Could not find Codex's home directory: " + HOME_VARIABLE + " is not set, " +
				userHomeVariable + " is unset or empty, and the account has no home directory.");
		}
		return workingDirectory.resolve(base).resolve(DEFAULT_HOME).normalize();
	}

	/**
	 * Returns the folder that {@code CODEX_HOME} names.
	 *
	 * @param configured       the value of {@code CODEX_HOME}, which is not empty
	 * @param workingDirectory the absolute working directory
	 * @return the real path of the folder
	 * @throws NoSuchFileException if the folder does not exist
	 * @throws IOException         if the value is not a path, or names a file or a folder that cannot be read
	 */
	private static Path configuredHome(String configured, Path workingDirectory) throws IOException
	{
		Path folder;
		try
		{
			folder = workingDirectory.resolve(configured);
		}
		catch (InvalidPathException e)
		{
			throw new IOException(HOME_VARIABLE + " is not a valid path: \"" + configured + "\"", e);
		}
		BasicFileAttributes attributes;
		try
		{
			attributes = Files.readAttributes(folder, BasicFileAttributes.class);
		}
		catch (NoSuchFileException _)
		{
			throw new NoSuchFileException(folder.normalize().toString(), null, HOME_VARIABLE +
				" names a folder that does not exist");
		}
		catch (IOException e)
		{
			throw new IOException("Failed to read " + HOME_VARIABLE + " \"" + configured + "\": " + e.getMessage(), e);
		}
		if (!attributes.isDirectory())
		{
			throw new IOException(HOME_VARIABLE + " names \"" + configured + "\", which is not a directory");
		}
		return folder.toRealPath();
	}

	/**
	 * Returns the name of Codex's home directory when {@code CODEX_HOME} does not move it.
	 *
	 * @return {@code .codex}, relative to the directory that holds Codex's home, such as the user's home directory
	 */
	public static Path defaultHome()
	{
		return DEFAULT_HOME;
	}

	/**
	 * Returns where Codex's configuration file is, relative to the directory that holds {@link #defaultHome()}. A
	 * project directory holds a {@code .codex} folder of the same layout, so this is also the project's Codex
	 * configuration.
	 *
	 * @return {@code .codex/config.toml}
	 */
	public static Path defaultConfiguration()
	{
		return DEFAULT_HOME.resolve(CONFIGURATION);
	}

	@Override
	public Path marketplaceRoot()
	{
		return home.resolve("plugins/cat-marketplace");
	}

	@Override
	public Path marketplacePlugin()
	{
		return marketplaceRoot().resolve("plugins/cat");
	}

	@Override
	public Path cacheRoot()
	{
		return home.resolve("plugins/cache");
	}

	@Override
	public Path versionFolders()
	{
		return cacheRoot().resolve("cat/cat");
	}

	@Override
	public Path dataDirectory()
	{
		return home.resolve("plugins/data/cat-cat");
	}

	/**
	 * Returns the manifest of CAT's marketplace, which Codex reads to find CAT's plugin.
	 *
	 * @return {@code .agents/plugins/marketplace.json} in the marketplace folder
	 */
	public Path marketplaceManifest()
	{
		return marketplaceRoot().resolve(".agents/plugins/marketplace.json");
	}

	/**
	 * Returns Codex's custom agent folder, which holds CAT's agents beside the user's.
	 *
	 * @return {@code agents} in Codex's home
	 */
	public Path agents()
	{
		return home.resolve("agents");
	}

	/**
	 * Returns Codex's execution-policy rules folder, which holds the rules that CAT adds beside the user's.
	 *
	 * @return {@code rules} in Codex's home
	 */
	public Path rules()
	{
		return home.resolve("rules");
	}

	/**
	 * Returns Codex's configuration file.
	 *
	 * @return {@code config.toml} in Codex's home
	 */
	public Path configuration()
	{
		return home.resolve(CONFIGURATION);
	}
}
