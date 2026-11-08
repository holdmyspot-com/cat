package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installation.CapacityLocks;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.ConsentRequest;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.engine.Consent;
import com.holdmyspot.cat.common.installer.engine.DataMigration;
import com.holdmyspot.cat.common.installer.engine.InstallerState;
import com.holdmyspot.cat.common.installer.engine.MigrationRunner;
import com.holdmyspot.cat.common.installer.engine.PluginInstall;
import com.holdmyspot.cat.common.installer.engine.PluginInstall.InstalledEntries;
import com.holdmyspot.cat.common.installer.engine.ShellStartupFiles;
import com.holdmyspot.cat.common.installer.engine.StartupCacheTrainer;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustException.Reason;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import com.holdmyspot.cat.common.release.EntryCommand;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Installs CAT's plugin into one harness from the extracted plugin archive that the running installer belongs to. The
 * installer writes the plugin into CAT's marketplace in the harness home and asks the harness to install it, which
 * puts it in its version folder; it then installs the provider's companion files from that folder, runs the data
 * migrations that the release registers after the recorded data version, trains the plugin's startup cache there,
 * writes the policy into the plugin's data folder and adds the permission entries that name the installer in the
 * version folder. It refuses a release older than the data version that kept data records, because a migration of a
 * newer release has shaped that data. Every check runs before the first change, and every step can be repeated, so
 * running install again repairs an installation that an earlier install left incomplete.
 * <p>
 * Install takes the harness program from the user's {@code PATH} only when the harness home's policy records none for
 * that home; otherwise it reuses the recorded program and its recorded environment, as {@link HarnessProgram}
 * explains.
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
	private final MigrationRunner migrations;
	private final EntryCommand entryCommand;

	/**
	 * Creates the install of the extracted plugin archive.
	 *
	 * @param image      the plugin folder of the extracted archive, the archive's top directory
	 * @param platform   the current platform
	 * @param replacer   atomically replaces the policy
	 * @param cliFactory runs the harness program that install discovers
	 * @param elevation  returns whether this process runs with administrative rights, given a directory that the check
	 *                   may write short-lived files to; the startup cache is not trained with those rights
	 * @param migrations runs the data migration scripts of the archive's release
	 * @param entryCommand writes the CAT entry command that a refusal tells the user to run
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Install(Path image, Platform platform, FileReplacer replacer, HarnessCliFactory cliFactory,
		Predicate<Path> elevation, MigrationRunner migrations, EntryCommand entryCommand)
	{
		requireThat(image, "image").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		requireThat(cliFactory, "cliFactory").isNotNull();
		requireThat(elevation, "elevation").isNotNull();
		requireThat(migrations, "migrations").isNotNull();
		requireThat(entryCommand, "entryCommand").isNotNull();
		this.image = image;
		this.platform = platform;
		this.replacer = replacer;
		this.cliFactory = cliFactory;
		this.elevation = elevation;
		this.migrations = migrations;
		this.entryCommand = entryCommand;
	}

	/**
	 * Installs the archive's plugin into the harness.
	 *
	 * @param harness     the harness
	 * @param home        the existing absolute harness home, or null for the home that the user's environment selects
	 * @param environment the user's environment, which selects the harness homes, and whose {@code PATH} a first
	 *                    install searches for the harness program
	 * @param userHome    the user's home directory, which holds the capacity locks
	 * @param activation  the harness's activation provider
	 * @param integration the harness's integration
	 * @param others      the integrations of the other harnesses, whose CAT installations the harness's sandbox may
	 *                    reach
	 * @param consent     decides whether the user allows a change that needs their permission
	 * @return the outcome
	 * @throws NullPointerException if any argument other than {@code home} is null
	 * @throws TrustException       if a check refuses install, including when the policy records a harness program
	 *                              that can no longer be used, a first install does not find the harness program on
	 *                              the user's search path or finds one that cannot start in the user's environment, or
	 *                              the user withholds a permission that install needs; the message of the latter is
	 *                              the question that the integration asked
	 * @throws IOException          if the harness configuration is invalid, the harness does not install the plugin, a
	 *                              data migration fails, or a file cannot be read, written or replaced
	 */
	public Outcome install(String harness, Path home, Map<String, String> environment, Path userHome,
		PluginActivation activation, HarnessIntegration integration, List<HarnessIntegration> others,
		Consent consent) throws TrustException, IOException
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(environment, "environment").isNotNull();
		requireThat(userHome, "userHome").isNotNull();
		requireThat(activation.harness(), "activation.harness()").isEqualTo(harness, "harness");
		requireThat(integration.harness(), "integration.harness()").isEqualTo(harness, "harness");
		requireThat(others, "others").isNotNull();
		requireThat(consent, "consent").isNotNull();
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
		TrustPolicy defaults = defaultPolicy(plugin);
		if (!Files.isRegularFile(plugin.resolve(activation.pluginManifest()), NOFOLLOW_LINKS))
		{
			throw refusal("The archive " + plugin + " holds no " + harness + " plugin; download the " + harness +
				" plugin archive of this release.");
		}
		HarnessInstallation installation = integration.installation(home);
		Path policyFile = installation.policy();
		HarnessCommand recorded = recordedProgram(policyFile, harness, home);
		requireWritable(policyFile);
		Path migrationFile = activation.migrationVersionFile(home);
		Optional<PluginVersion> before = activeVersion(activation, home);
		Optional<ReleaseVersion> beforeRelease = Optional.empty();
		if (before.isPresent())
			beforeRelease = Optional.of(release(before.get()));
		DataMigration migration = DataMigration.plan(plugin, migrationFile, release(version), beforeRelease);
		Optional<ReleaseVersion> newerData = migration.newerData();
		if (newerData.isPresent())
		{
			throw refusal("CAT's kept data in " + home + " has data version " + newerData.get() + ", which a " +
				"migration of a newer release produced, so release " + version.release() + " cannot read it and " +
				"installing it would lose data; run install with release " + newerData.get() + " or a later one " +
				"instead.");
		}
		HarnessCommand command;
		if (recorded == null)
			command = discoverProgram(integration, home, environment);
		else
		{
			HarnessProgram.requireRecorded(recorded, home, policyFile, integration, cliFactory, entryCommand);
			command = recorded;
		}
		integration.validateConfiguration(home);
		Path catLocks = CapacityLocks.root(userHome);
		boolean consented = false;
		Optional<ConsentRequest> consentRequest = integration.consentRequest(home, catLocks);
		if (consentRequest.isPresent())
		{
			consented = consent.grants(consentRequest.get().question());
			if (!consented && consentRequest.get().required())
				throw refusal(consentRequest.get().question());
		}

		PluginInstall steps = new PluginInstall(integration, cliFactory.create(command, home), replacer);
		Path versionFolder = steps.deliver(home, plugin, version);
		activation.installCompanions(home, versionFolder);
		migration.apply(versionFolder, migrationFile.getParent(), migrations, platform.isWindows());
		Path scratch = InstallerState.of(installation.dataDirectory(), harness).directory().resolve("startup-cache");
		boolean startupCache = new StartupCacheTrainer(scratch, TRAINING_LIMIT, () -> elevation.test(scratch)).
			train(versionFolder);

		TrustPolicy policy = TrustPolicy.of(defaults.keys(), defaults.releaseBaseUrl(), defaults.minimum(),
			defaults.maximumMajor(), Map.of(harness, home), Map.of(harness, command));
		writePolicy(policy, policyFile, replacer);

		Path installer = PluginInstall.installer(versionFolder, platform.isWindows());
		List<HarnessInstallation> otherInstallations = others.stream().
			map(other -> other.installation(environment, userHome)).toList();
		InstalledEntries entries = steps.installEntries(home, installer, installation, otherInstallations, catLocks,
			PluginInstall.formerLauncherState(userHome), ShellStartupFiles.in(userHome), consented);
		return new Outcome(version, before, versionFolder, installer, startupCache, entries, policy);
	}

	/**
	 * Reads the default policy of an extracted plugin archive, which names the signing keys of the archive's release
	 * and the releases that its install accepts.
	 *
	 * @param plugin the archive's plugin folder
	 * @return the policy
	 * @throws TrustException if the policy is malformed
	 * @throws IOException    if the policy cannot be read
	 */
	static TrustPolicy defaultPolicy(Path plugin) throws TrustException, IOException
	{
		return TrustPolicy.read(plugin.resolve(PluginDirectory.INSTALLER_CONFIGURATION).resolve("default-policy.json"));
	}

	/**
	 * Writes an installation's policy, which only the installing user may read or change.
	 *
	 * @param policy     the policy
	 * @param policyFile the installation's policy file
	 * @param replacer   atomically replaces the policy file
	 * @throws IOException if the policy cannot be written or replaced, or its permissions cannot be applied
	 */
	static void writePolicy(TrustPolicy policy, Path policyFile, FileReplacer replacer) throws IOException
	{
		Files.createDirectories(policyFile.getParent());
		policy.write(policyFile, replacer);
		setPermissions(policyFile);
	}

	/**
	 * Returns a plugin version's release.
	 *
	 * @param version the plugin version
	 * @return the release
	 * @throws TrustException if the release is malformed
	 */
	private static ReleaseVersion release(PluginVersion version) throws TrustException
	{
		return ReleaseVersion.parse(version.release());
	}

	/**
	 * Returns the plugin version that the harness has. Its release stands for the data version when CAT's data
	 * records none.
	 *
	 * @param activation the harness's activation provider
	 * @param home       the harness home
	 * @return the version, or empty when the harness has no plugin version with a build
	 * @throws IOException if the installation cannot be read
	 */
	private static Optional<PluginVersion> activeVersion(PluginActivation activation, Path home) throws IOException
	{
		Optional<String> installed = activation.installed(home).version();
		if (installed.isEmpty())
			return Optional.empty();
		try
		{
			return Optional.of(PluginVersion.parse(installed.get()));
		}
		catch (IllegalArgumentException _)
		{
			return Optional.empty();
		}
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
	 * Returns the harness program that the existing policy records for the harness home, after refusing a policy that
	 * is not a regular file or that records another harness.
	 *
	 * @param policyFile the policy
	 * @param harness    the installation's harness
	 * @param home       the real harness home
	 * @return the recorded command, or null when no policy exists, or it records no program or records another home
	 * @throws TrustException if the policy is a link, is malformed or records another harness
	 * @throws IOException    if the policy cannot be read
	 */
	private static HarnessCommand recordedProgram(Path policyFile, String harness, Path home)
		throws TrustException, IOException
	{
		if (!Files.exists(policyFile, NOFOLLOW_LINKS))
			return null;
		if (!Files.isRegularFile(policyFile, NOFOLLOW_LINKS))
			throw refusal("The policy " + policyFile + " must be a regular file, not a symbolic link.");
		TrustPolicy policy = TrustPolicy.read(policyFile);
		for (String recorded : policy.harnesses().keySet())
		{
			if (!recorded.equals(harness))
			{
				throw refusal("The policy " + policyFile + " records the harness " + recorded + ", but this " +
					"installation is for " + harness + " only.");
			}
		}
		// A record for another home runs the program with that home's variables, which would change the other home
		if (!home.equals(policy.harnesses().get(harness)))
			return null;
		return policy.command(harness).orElse(null);
	}

	/**
	 * Finds the harness program on the user's search path and confirms that it is the harness's program.
	 *
	 * @param integration the harness's integration
	 * @param home        the real harness home
	 * @param environment the user's environment
	 * @return the command and environment to record
	 * @throws TrustException if the program is not on the user's search path, or could not start in the user's
	 *                        environment; the message tells the user to install the harness or to run the installer
	 *                        with the environment of the harness's sessions
	 * @throws IOException    if the program cannot be run, or does not identify itself as the harness's program
	 */
	private HarnessCommand discoverProgram(HarnessIntegration integration, Path home, Map<String, String> environment)
		throws TrustException, IOException
	{
		HarnessCommand command;
		try
		{
			command = new ExecutableDiscovery(platform).find(integration.programName(), environment,
				integration.programEnvironment(home, platform.environment(environment)));
		}
		catch (ProgramNotFoundException e)
		{
			// The same environment finds no program again, so only the user can change the outcome
			throw refusal(e.getMessage() + " Install " + integration.productName() + ", or run the installer with " +
				"the PATH your " + integration.productName() + " sessions use, then run the same command again.");
		}
		try
		{
			HarnessProgram.requireIdentity(command, home, integration, cliFactory);
		}
		catch (ProgramStartException e)
		{
			// The same environment fails the same way, so only the user can change the outcome
			throw refusal(e.getMessage() + " Run the installer with the environment your " +
				integration.productName() + " sessions use.");
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
	 * @param before        the plugin version that the harness had before install, or empty when it had none
	 * @param versionFolder the version folder that the harness loads the plugin from
	 * @param installer     the installer launcher in the version folder, which the permission entries name
	 * @param startupCache  whether the plugin got its startup cache
	 * @param entries       the permission and sandbox entries that install has recorded, and what the user needs to
	 *                      know about the ones that it added
	 * @param policy        the written policy
	 */
	public record Outcome(PluginVersion version, Optional<PluginVersion> before, Path versionFolder, Path installer,
		boolean startupCache, InstalledEntries entries, TrustPolicy policy)
	{
		/**
		 * Creates the outcome.
		 *
		 * @param version       the installed plugin version
		 * @param before        the plugin version that the harness had before install, or empty when it had none
		 * @param versionFolder the version folder that the harness loads the plugin from
		 * @param installer     the installer launcher in the version folder, which the permission entries name
		 * @param startupCache  whether the plugin got its startup cache
		 * @param entries       the permission and sandbox entries that install has recorded, and what the user needs
		 *                      to know about the ones that it added
		 * @param policy        the written policy
		 * @throws NullPointerException if any of the arguments are null
		 */
		public Outcome
		{
			requireThat(version, "version").isNotNull();
			requireThat(before, "before").isNotNull();
			requireThat(versionFolder, "versionFolder").isNotNull();
			requireThat(installer, "installer").isNotNull();
			requireThat(entries, "entries").isNotNull();
			requireThat(policy, "policy").isNotNull();
		}
	}
}
