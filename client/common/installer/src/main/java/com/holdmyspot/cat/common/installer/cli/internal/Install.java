package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installation.CapacityLocks;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.engine.InstallerState;
import com.holdmyspot.cat.common.installer.engine.LinkSafeDeletion;
import com.holdmyspot.cat.common.installer.engine.PermissionRecord;
import com.holdmyspot.cat.common.installer.engine.StartupCacheTrainer;
import com.holdmyspot.cat.common.installer.engine.TreeCopy;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustException.Reason;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.RuntimeBinding;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Installs CAT's plugin into one harness from the extracted plugin archive that the running installer belongs to. The
 * installer writes the plugin into CAT's marketplace in the harness home and asks the harness to install it, which
 * puts it in its version folder; it then trains the plugin's startup cache there, writes the policy into the plugin's
 * data folder and adds the permission entries that name the installer in the version folder. Every check runs before
 * the first change, and every step can be repeated, so running install again repairs an installation that an earlier
 * install left incomplete.
 */
public final class Install
{
	/**
	 * The longest time that each Java run of the startup cache training may take.
	 */
	private static final Duration TRAINING_LIMIT = Duration.ofMinutes(1);
	/**
	 * The largest supported encoded plugin version, including its final newline.
	 */
	private static final int MAXIMUM_VERSION_BYTES = 128;
	/**
	 * Only the installing user may read or change the policy.
	 */
	private static final Set<PosixFilePermission> PRIVATE_FILE = Set.of(PosixFilePermission.OWNER_READ,
		PosixFilePermission.OWNER_WRITE);
	private final Path image;
	private final Platform platform;
	private final FileReplacer replacer;
	private final HarnessCliFactory cliFactory;
	private final Predicate<Path> elevation;

	/**
	 * Creates the install of the extracted plugin archive.
	 *
	 * @param image      the plugin folder of the extracted archive, the archive's top directory
	 * @param platform   the current platform
	 * @param replacer   atomically replaces the policy
	 * @param cliFactory runs the harness program that install discovers
	 * @param elevation  returns whether this process runs with administrative rights, given a directory that the check
	 *                   may write short-lived files to; the startup cache is not trained with those rights
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Install(Path image, Platform platform, FileReplacer replacer, HarnessCliFactory cliFactory,
		Predicate<Path> elevation)
	{
		requireThat(image, "image").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		requireThat(cliFactory, "cliFactory").isNotNull();
		requireThat(elevation, "elevation").isNotNull();
		this.image = image;
		this.platform = platform;
		this.replacer = replacer;
		this.cliFactory = cliFactory;
		this.elevation = elevation;
	}

	/**
	 * Installs the archive's plugin into the harness.
	 *
	 * @param harness     the harness
	 * @param home        the existing absolute harness home, or null for the home that the user's environment selects
	 * @param cwd         the existing working directory from which the user ran install
	 * @param environment the user's environment, which selects the harness homes and is searched for the harness
	 *                    program
	 * @param userHome    the user's home directory, which holds the capacity locks
	 * @param activation  the harness's activation provider
	 * @param integration the harness's integration
	 * @param others      the integrations of the other harnesses, whose CAT installations the harness's sandbox may
	 *                    reach
	 * @return the outcome
	 * @throws NullPointerException if any argument other than {@code home} is null
	 * @throws TrustException       if a check refuses install
	 * @throws IOException          if the harness program is not found, the harness configuration is invalid, the
	 *                              harness does not install the plugin, or a file cannot be read, written or replaced
	 */
	public Outcome install(String harness, Path home, Path cwd, Map<String, String> environment, Path userHome,
		PluginActivation activation, HarnessIntegration integration, List<HarnessIntegration> others)
		throws TrustException, IOException
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(cwd, "cwd").isDirectory();
		requireThat(environment, "environment").isNotNull();
		requireThat(userHome, "userHome").isNotNull();
		requireThat(activation.harness(), "activation.harness()").isEqualTo(harness, "harness");
		requireThat(integration.harness(), "integration.harness()").isEqualTo(harness, "harness");
		requireThat(others, "others").isNotNull();
		if (home == null)
			home = integration.installation(environment, userHome).home();
		if (!home.isAbsolute() || !Files.isDirectory(home))
		{
			throw refusal("The harness home " + home + " must be an existing absolute directory; create it by " +
				"running " + harness + " once, or name it with --home.");
		}
		home = home.toRealPath();
		Path plugin = image.toRealPath();
		PluginVersion version = version(plugin);
		TrustPolicy defaults = TrustPolicy.read(plugin.resolve(PluginDirectory.INSTALLER_CONFIGURATION).
			resolve("default-policy.json"));
		if (!Files.isRegularFile(plugin.resolve(activation.pluginManifest()), NOFOLLOW_LINKS))
		{
			throw refusal("The archive " + plugin + " holds no " + harness + " plugin; download the " + harness +
				" plugin archive of this release.");
		}
		HarnessInstallation installation = integration.installation(home);
		Path policyFile = installation.policy();
		requireOwnPolicy(policyFile, harness);
		requireWritable(policyFile);
		HarnessCommand command = discoverProgram(integration, home, installation.dataDirectory(), cwd, environment);
		integration.validateConfiguration(home);

		Path marketplace = integration.marketplaceFolder(home, version);
		Path versionFolder = installation.versionFolder(version);
		writeMarketplace(plugin, marketplace, versionFolder, home);
		integration.register(home, version, cliFactory.create(command, home), replacer);
		if (!Files.isDirectory(versionFolder, NOFOLLOW_LINKS))
		{
			throw new IOException(harness + " did not install CAT " + version + " into its version folder " +
				versionFolder + ".");
		}
		Path scratch = InstallerState.of(installation.dataDirectory(), harness).directory().resolve("startup-cache");
		boolean startupCache = new StartupCacheTrainer(scratch, TRAINING_LIMIT, () -> elevation.test(scratch)).
			train(versionFolder);

		TrustPolicy policy = TrustPolicy.of(defaults.keys(), defaults.releaseBaseUrl(), defaults.minimum(),
			defaults.maximumMajor(), Map.of(harness, home), Map.of(harness, command));
		Files.createDirectories(policyFile.getParent());
		policy.write(policyFile, replacer);
		setPermissions(policyFile);

		Path installer = installer(versionFolder);
		Map<String, List<String>> permissions = installEntries(integration, home, installer, installation,
			environment, userHome, others);
		return new Outcome(version, versionFolder, installer, startupCache, permissions, policy);
	}

	/**
	 * Returns the installer launcher of a plugin folder for the current platform.
	 *
	 * @param plugin the plugin folder
	 * @return the launcher
	 */
	private Path installer(Path plugin)
	{
		Path launcher = plugin.resolve(PluginDirectory.INSTALLER);
		if (platform.isWindows())
			return launcher.resolveSibling(launcher.getFileName() + ".ps1");
		return launcher;
	}

	/**
	 * Reads the plugin version of the archive.
	 *
	 * @param plugin the archive's plugin folder
	 * @return the version
	 * @throws IOException    if the version file cannot be read
	 * @throws TrustException if the file does not hold one plugin version
	 */
	private static PluginVersion version(Path plugin) throws IOException, TrustException
	{
		String text = BoundedInput.readUtf8(plugin.resolve(PluginDirectory.VERSION), MAXIMUM_VERSION_BYTES,
			"The plugin version").strip();
		try
		{
			return PluginVersion.parse(text);
		}
		catch (IllegalArgumentException e)
		{
			throw refusal("The archive " + plugin + " does not name its plugin version: " + e.getMessage());
		}
	}

	/**
	 * Writes the archive's plugin into CAT's marketplace in the harness home, bound to the Java runtime that the
	 * plugin will have in its version folder. The copy is staged beside its destination and then replaces it.
	 *
	 * @param plugin        the archive's plugin folder
	 * @param marketplace   the marketplace folder that the harness installs the plugin from
	 * @param versionFolder the version folder into which the harness installs the plugin
	 * @param home          the harness home
	 * @throws IOException if the folder cannot be written
	 */
	private static void writeMarketplace(Path plugin, Path marketplace, Path versionFolder, Path home)
		throws IOException
	{
		if (marketplace.equals(plugin))
			throw new IOException("Install must run from an extracted plugin archive, not from " + plugin + ".");
		Path parent = Files.createDirectories(marketplace.getParent());
		Path staged = parent.resolve("." + marketplace.getFileName() + "-staged");
		LinkSafeDeletion.delete(staged, home);
		TreeCopy.copy(plugin, staged);
		RuntimeBinding.write(staged, versionFolder.resolve(PluginDirectory.RUNTIME));
		LinkSafeDeletion.delete(marketplace, home);
		Files.move(staged, marketplace);
	}

	/**
	 * Adds the permission entries and the sandbox entries, removes the recorded entries that the integration no longer
	 * maintains, and records the added ones.
	 *
	 * @param integration  the harness's integration
	 * @param home         the harness home
	 * @param installer    the installer launcher in the version folder
	 * @param installation the harness's CAT installation
	 * @param environment  the user's environment, which selects the other harnesses' homes
	 * @param userHome     the user's home directory
	 * @param others       the integrations of the other harnesses
	 * @return the recorded entries, by entry key
	 * @throws IOException if an entry or the record cannot be read or written
	 */
	private Map<String, List<String>> installEntries(HarnessIntegration integration, Path home, Path installer,
		HarnessInstallation installation, Map<String, String> environment, Path userHome,
		List<HarnessIntegration> others) throws IOException
	{
		Path record = InstallerState.of(installation.dataDirectory(), integration.harness()).permissions();
		Path catLocks = CapacityLocks.root(userHome);
		Map<String, List<String>> maintained = new LinkedHashMap<>(integration.permissions(installer));
		List<HarnessInstallation> otherInstallations = new ArrayList<>();
		for (HarnessIntegration other : others)
		{
			HarnessInstallation otherInstallation = other.installation(environment, userHome);
			otherInstallations.add(otherInstallation);
			merge(maintained, integration.sandboxEntries(home, otherInstallation, catLocks));
		}
		Map<String, List<String>> obsolete = PermissionRecord.obsolete(record, maintained);
		if (!obsolete.isEmpty())
		{
			integration.removePermissions(home, obsolete, replacer);
			PermissionRecord.remove(record, obsolete);
		}
		Map<String, List<String>> added = new LinkedHashMap<>(integration.installPermissions(home, installer,
			replacer));
		for (HarnessInstallation otherInstallation : otherInstallations)
			merge(added, integration.installSandboxEntries(home, otherInstallation, catLocks, replacer));
		return PermissionRecord.add(record, added);
	}

	/**
	 * Adds entries to a map of entries, keeping each key's entries in order and each entry once.
	 *
	 * @param target  the entries that receive the others
	 * @param entries the added entries
	 */
	private static void merge(Map<String, List<String>> target, Map<String, List<String>> entries)
	{
		for (Map.Entry<String, List<String>> entry : entries.entrySet())
		{
			List<String> values = new ArrayList<>(target.getOrDefault(entry.getKey(), List.of()));
			for (String value : entry.getValue())
			{
				if (!values.contains(value))
					values.add(value);
			}
			target.put(entry.getKey(), List.copyOf(values));
		}
	}

	/**
	 * Refuses an existing policy that is not a regular file or that records another harness.
	 *
	 * @param policyFile the policy
	 * @param harness    the installation's harness
	 * @throws TrustException if the policy is a link, is malformed or records another harness
	 * @throws IOException    if the policy cannot be read
	 */
	private static void requireOwnPolicy(Path policyFile, String harness) throws TrustException, IOException
	{
		if (!Files.exists(policyFile, NOFOLLOW_LINKS))
			return;
		if (!Files.isRegularFile(policyFile, NOFOLLOW_LINKS))
			throw refusal("The policy " + policyFile + " must be a regular file, not a symbolic link.");
		for (String recorded : TrustPolicy.read(policyFile).harnesses().keySet())
		{
			if (!recorded.equals(harness))
			{
				throw refusal("The policy " + policyFile + " records the harness " + recorded + ", but this " +
					"installation is for " + harness + " only.");
			}
		}
	}

	/**
	 * Finds the harness program on the user's search path and confirms that it is the harness's program by running
	 * {@code <program> --version} exactly as registration will run it.
	 *
	 * @param integration the harness's integration
	 * @param home        the real harness home
	 * @param data        the plugin data folder, where the program may not lie
	 * @param cwd         the working directory from which the user ran install
	 * @param environment the user's environment
	 * @return the command and environment to record
	 * @throws IOException if the program is not found, cannot be run, or does not identify itself as the harness's
	 *                     program
	 */
	private HarnessCommand discoverProgram(HarnessIntegration integration, Path home, Path data, Path cwd,
		Map<String, String> environment) throws IOException
	{
		ExecutableDiscovery discovery = new ExecutableDiscovery(platform, cwd.toRealPath(),
			Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath());
		HarnessCommand command = discovery.find(integration.programName(), home, data, environment,
			integration.programEnvironment(home, platform.environment(environment)));
		CommandResult version = cliFactory.create(command, home).run(List.of("--version"));
		try
		{
			integration.requireIdentity(version);
		}
		catch (IOException e)
		{
			throw new IOException("The program " + command.command().getLast() + " that install found on PATH is " +
				"not the " + integration.harness() + " program: " + e.getMessage(), e);
		}
		return command;
	}

	/**
	 * Refuses an unwritable policy or an existing symbolic link before install changes anything.
	 *
	 * @param path the policy that install will atomically replace
	 * @throws TrustException if the existing policy is not a regular file
	 * @throws IOException    if its nearest existing parent cannot accept the replacement
	 */
	private static void requireWritable(Path path) throws TrustException, IOException
	{
		Path existing = path;
		if (Files.exists(path, NOFOLLOW_LINKS))
		{
			if (!Files.isRegularFile(path, NOFOLLOW_LINKS))
				throw refusal("Install requires a regular file at " + path + ".");
			if (!Files.isWritable(path))
				throw new AccessDeniedException(path.toString());
			existing = path.getParent();
		}
		while (!Files.exists(existing, NOFOLLOW_LINKS))
			existing = existing.getParent();
		if (!Files.isDirectory(existing) || !Files.isWritable(existing))
			throw new AccessDeniedException(path.toString());
	}

	/**
	 * Lets only the installing user read or change a file, on file systems that implement POSIX permissions.
	 *
	 * @param path the file
	 * @throws IOException if the permissions cannot be applied on a supporting file system
	 */
	private static void setPermissions(Path path) throws IOException
	{
		if (path.getFileSystem().supportedFileAttributeViews().contains("posix"))
			Files.setPosixFilePermissions(path, PRIVATE_FILE);
	}

	/**
	 * Creates an install refusal.
	 *
	 * @param message the refused condition
	 * @return the refusal
	 */
	private static TrustException refusal(String message)
	{
		return new TrustException(Reason.MALFORMED_POLICY, message);
	}

	/**
	 * The outcome of install.
	 *
	 * @param version       the installed plugin version
	 * @param versionFolder the version folder that the harness loads the plugin from
	 * @param installer     the installer launcher in the version folder, which the permission entries name
	 * @param startupCache  whether the plugin got its startup cache
	 * @param permissions   the permission and sandbox entries that install has recorded, by entry key
	 * @param policy        the written policy
	 */
	public record Outcome(PluginVersion version, Path versionFolder, Path installer, boolean startupCache,
		Map<String, List<String>> permissions, TrustPolicy policy)
	{
		/**
		 * Creates the outcome.
		 *
		 * @param version       the installed plugin version
		 * @param versionFolder the version folder that the harness loads the plugin from
		 * @param installer     the installer launcher in the version folder, which the permission entries name
		 * @param startupCache  whether the plugin got its startup cache
		 * @param permissions   the permission and sandbox entries that install has recorded, by entry key
		 * @param policy        the written policy
		 * @throws NullPointerException if any of the arguments are null
		 */
		public Outcome
		{
			requireThat(version, "version").isNotNull();
			requireThat(versionFolder, "versionFolder").isNotNull();
			requireThat(installer, "installer").isNotNull();
			requireThat(policy, "policy").isNotNull();
			permissions = Map.copyOf(permissions);
		}
	}
}
