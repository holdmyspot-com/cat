package com.holdmyspot.cat.common.updater.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.runner.SharedRuntime;
import com.holdmyspot.cat.common.updater.activation.ActivationStep;
import com.holdmyspot.cat.common.updater.activation.HarnessCommand;
import com.holdmyspot.cat.common.updater.activation.HarnessIntegration;
import com.holdmyspot.cat.common.updater.activation.InstalledState;
import com.holdmyspot.cat.common.updater.activation.PluginActivation;
import com.holdmyspot.cat.common.updater.activation.ReplaceDirectory;
import com.holdmyspot.cat.common.updater.engine.Bundle;
import com.holdmyspot.cat.common.updater.engine.LinkSafeDeletion;
import com.holdmyspot.cat.common.updater.engine.PermissionRecord;
import com.holdmyspot.cat.common.updater.engine.Registration;
import com.holdmyspot.cat.common.updater.engine.ReleaseSource;
import com.holdmyspot.cat.common.updater.engine.RuntimeDescription;
import com.holdmyspot.cat.common.updater.engine.RuntimeRequirement;
import com.holdmyspot.cat.common.updater.engine.TreeCopy;
import com.holdmyspot.cat.common.updater.engine.Uninstaller;
import com.holdmyspot.cat.common.updater.engine.UpdateEngine;
import com.holdmyspot.cat.common.updater.engine.UpdateResult;
import com.holdmyspot.cat.common.updater.engine.UpdaterState;
import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import com.holdmyspot.cat.common.updater.trust.TrustException.Reason;
import com.holdmyspot.cat.common.updater.trust.TrustPolicy;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Performs the user's explicit setup of one harness's installation from an extracted updater archive: the Java
 * runtime, the updater, its policy and stable launcher, the plugin and the permission entries that let an agent run
 * the routine operations. Every check runs before the first change, and every step can be repeated, so running setup
 * again repairs an installation that an earlier setup left incomplete.
 */
public final class Setup
{
	/**
	 * The image-relative default policy authenticated by the pinned archive digest.
	 */
	private static final Path DEFAULT_POLICY = Path.of("conf/cat-updater/default-policy.json");
	/**
	 * The image-relative release version file.
	 */
	private static final Path VERSION_FILE = Path.of("VERSION");
	/**
	 * The image-relative description of the Java runtime that the updater needs.
	 */
	private static final Path REQUIREMENT_FILE = Path.of("runtime-requirement.json");
	/**
	 * The stable launcher's active-image selection file, relative to the data directory.
	 */
	private static final Path CURRENT_FILE = Path.of("current");
	/**
	 * The name of the installation's Java runtime directory, beside the updater version directories.
	 */
	private static final String RUNTIME = "runtime";
	/**
	 * Only the installing user may read or change a protected policy or anchor.
	 */
	private static final Set<PosixFilePermission> PRIVATE_FILE = Set.of(PosixFilePermission.OWNER_READ,
		PosixFilePermission.OWNER_WRITE);
	/**
	 * Only the installing user may execute or change the stable launcher.
	 */
	private static final Set<PosixFilePermission> PRIVATE_EXECUTABLE = Set.of(PosixFilePermission.OWNER_READ,
		PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	private final Locations locations;
	private final Platform platform;
	private final FileReplacer replacer;
	private final DirectoryMover mover;
	private final HarnessCliFactory cliFactory;
	private final Clock clock;
	private final Waiting waiting;

	/**
	 * Creates explicit setup for one harness's installation.
	 *
	 * @param locations  the user's selected protected locations, whose image is the archive's updater version
	 * @param platform   the current platform
	 * @param replacer   atomically replaces policy, anchor, launcher and current files
	 * @param mover      renames the Java runtime and updater directories into place
	 * @param cliFactory runs the harness program that setup discovers
	 * @param clock      operation time source
	 * @param waiting    creates pauses between lock attempts
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Setup(Locations locations, Platform platform, FileReplacer replacer, DirectoryMover mover,
		HarnessCliFactory cliFactory, Clock clock, Waiting waiting)
	{
		requireThat(locations, "locations").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		requireThat(mover, "mover").isNotNull();
		requireThat(cliFactory, "cliFactory").isNotNull();
		requireThat(clock, "clock").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		this.locations = locations;
		this.platform = platform;
		this.replacer = replacer;
		this.mover = mover;
		this.cliFactory = cliFactory;
		this.clock = clock;
		this.waiting = waiting;
	}

	/**
	 * Sets the harness's installation up. Setup first checks the harness home, the protected locations, the harness
	 * program and configuration, and that the archive's Java runtime can run the updater and the installed plugin. It
	 * then installs the runtime, the updater, the policy, the stable launcher and the current selection, activates the
	 * plugin of this release (also when the harness already has it) and installs the permission entries. Setup from
	 * a newer release than the installed one keeps the installed bundle as the previous bundle that rollback restores,
	 * and setup from the installed release keeps the previous bundle that the installation has. Otherwise setup deletes
	 * the previous bundle; it always deletes a pending runtime replacement. A failure of the plugin step leaves the
	 * updater installed and is reported in the outcome. After a successful plugin step, setup removes the harness from
	 * the single updater installation that earlier builds shared between harnesses, and deletes that installation once
	 * it serves no harness.
	 *
	 * @param harness     the harness
	 * @param home        the existing absolute harness home
	 * @param plugin      the user's local plugin tree that replaces the signed release, or null
	 * @param cwd         the existing working directory from which the user explicitly ran setup
	 * @param environment the user's environment, which is searched for the harness program
	 * @param activation  the harness's activation provider
	 * @param integration the harness's integration
	 * @param source      the release transport for the signed plugin
	 * @return the outcome
	 * @throws NullPointerException if any argument other than {@code plugin} is null
	 * @throws TrustException       if a check refuses setup
	 * @throws IOException          if the harness program is not found, the harness configuration is invalid, the Java
	 *                              runtime cannot run the updater or the installed plugin, or a file cannot be read,
	 *                              written or replaced
	 */
	public Outcome setUp(String harness, Path home, Path plugin, Path cwd, Map<String, String> environment,
		PluginActivation activation, HarnessIntegration integration, ReleaseSource source)
		throws TrustException, IOException
	{
		requireThat(harness, "harness").isEqualTo(locations.harness(), "locations.harness()");
		requireThat(home, "home").isNotNull();
		requireThat(cwd, "cwd").isDirectory();
		requireThat(environment, "environment").isNotNull();
		requireThat(activation.harness(), "activation.harness()").isEqualTo(harness, "harness");
		requireThat(integration.harness(), "integration.harness()").isEqualTo(harness, "harness");
		requireThat(source, "source").isNotNull();
		if (!home.isAbsolute() || !Files.isDirectory(home))
			throw refusal("Setup --home must name an existing absolute harness directory.");
		home = home.toRealPath();
		if (plugin != null)
		{
			if (!plugin.isAbsolute() || !Files.isDirectory(plugin))
				throw refusal("Setup --plugin must name an existing absolute plugin directory.");
			plugin = plugin.toRealPath();
		}
		Path image = locations.image();
		String version = version();
		Path data = Locations.resolveExistingAncestor(locations.data());
		Path policyPath = Locations.resolveExistingAncestor(locations.policy());
		boolean installed = image.startsWith(data);
		if (installed && (!image.getParent().equals(data) || !image.getFileName().toString().equals(version)))
		{
			throw refusal("Setup must run from the extracted updater archive, or from the updater version that " +
				"this installation runs.");
		}
		TrustPolicy defaults = defaults();
		if (defaults.keys().isEmpty() && plugin == null)
			throw refusal("This build has no release key; name a local plugin tree with --plugin.");
		requireOwnPolicy(harness);
		refuseInside(data, policyPath, cwd.toRealPath());
		refuseInside(data, policyPath, home);
		if (data.toString().codePoints().anyMatch(Character::isISOControl) ||
			policyPath.toString().codePoints().anyMatch(Character::isISOControl))
		{
			throw refusal("Updater data and policy paths may not contain control characters.");
		}
		HarnessCommand command = integration.discover(home, withoutUpdaterSearchPath(environment, data));
		integration.validateConfiguration(home);
		Path runtime = image.getParent().resolve(RUNTIME);
		requireRuntime(runtime, image, activation, home);
		TrustPolicy policy = TrustPolicy.of(defaults.keys(), defaults.releaseBaseUrl(), defaults.minimum(),
			defaults.maximumMajor(), Map.of(harness, home), Map.of(harness, command));
		Path launcher = locations.launcher(platform);
		requireWritable(locations.policy());
		requireWritable(launcher);
		requireWritable(locations.data().resolve(CURRENT_FILE));
		UpdateEngine engine = new UpdateEngine(locations.data(), policy, source, activation, platform.id(), clock,
			waiting);
		restoreRuntimeAside();
		String selected = selectedVersion();
		boolean replacing = !installed && selected != null &&
			ReleaseVersion.parse(version).compareTo(ReleaseVersion.parse(selected)) > 0 &&
			Files.isDirectory(locations.data().resolve(selected), NOFOLLOW_LINKS) &&
			Files.isDirectory(locations.data().resolve(RUNTIME), NOFOLLOW_LINKS);
		String kept = null;
		if (replacing)
			kept = selected;
		else if (version.equals(selected))
			kept = previousBundle(engine);

		Files.createDirectories(locations.data());
		Path installedImage = locations.data().resolve(version);
		if (!installed)
		{
			Path installedRuntime = locations.data().resolve(RUNTIME);
			// A setup that stopped after it moved this runtime into place already set the replaced one aside
			if (sameRuntime(runtime, installedRuntime))
				LinkSafeDeletion.delete(staged(installedRuntime), locations.data());
			else if (replacing)
				moveIntoPlace(runtime, installedRuntime, locations.data().resolve(Bundle.RUNTIME_PREVIOUS));
			else
				replaceDirectory(runtime, installedRuntime);
			replaceDirectory(image, installedImage);
		}
		write(installedImage.resolve(Locations.POLICY_ANCHOR), policyPath + "\n", PRIVATE_FILE);
		if (replacing)
		{
			write(locations.data().resolve(selected).resolve(Locations.POLICY_ANCHOR), policyPath + "\n",
				PRIVATE_FILE);
		}
		Files.createDirectories(locations.policy().getParent());
		policy.write(locations.policy(), replacer);
		setPermissions(locations.policy(), PRIVATE_FILE);
		Uninstaller.discard(locations.data());
		writeLauncher(launcher);
		write(locations.data().resolve(CURRENT_FILE), version + "\n", PRIVATE_FILE);
		discardReplacement();
		if (kept == null)
		{
			LinkSafeDeletion.delete(locations.data().resolve(Bundle.RUNTIME_PREVIOUS), locations.data());
			deleteOtherVersions(List.of(version));
		}
		else
			deleteOtherVersions(List.of(version, kept));

		Registration registration = new Registration(integration, cliFactory.create(command, home), replacer);
		UpdateResult pluginResult;
		if (plugin == null)
			pluginResult = engine.setUp(version, registration);
		else
			pluginResult = engine.setUpFrom(plugin, version, registration);
		Map<String, List<String>> permissions = Map.of();
		if (pluginResult.result().exitStatus() == 0)
		{
			permissions = PermissionRecord.add(UpdaterState.of(locations.data(), harness).permissions(),
				integration.installPermissions(home, launcher, replacer));
			new SharedLayout(locations.data().getParent(), locations.sharedPolicy(), replacer).retire(harness);
		}
		return new Outcome(version, pluginResult, launcher, permissions, policy);
	}

	/**
	 * Reads the default policy that the running updater version ships.
	 *
	 * @return the default policy
	 * @throws IOException    if the policy cannot be read
	 * @throws TrustException if the policy is malformed
	 */
	public TrustPolicy defaults() throws IOException, TrustException
	{
		return TrustPolicy.read(locations.image().resolve(DEFAULT_POLICY));
	}

	/**
	 * Reads the canonical release version of the running image.
	 *
	 * @return the version without its tag prefix
	 * @throws IOException    if VERSION cannot be read
	 * @throws TrustException if VERSION does not contain a supported release version
	 */
	public String version() throws IOException, TrustException
	{
		String version = BoundedInput.readUtf8(locations.image().resolve(VERSION_FILE), 128,
			"The updater image version").strip();
		return ReleaseVersion.parse(version).toString();
	}

	/**
	 * Refuses an existing policy that is not a regular file or that records another harness.
	 *
	 * @param harness the installation's harness
	 * @throws TrustException if the policy is a link, is malformed or records another harness
	 * @throws IOException    if the policy cannot be read
	 */
	private void requireOwnPolicy(String harness) throws TrustException, IOException
	{
		if (!Files.exists(locations.policy(), NOFOLLOW_LINKS))
			return;
		if (!Files.isRegularFile(locations.policy(), NOFOLLOW_LINKS))
			throw refusal("The protected policy must be a regular file, not a symbolic link.");
		for (String recorded : TrustPolicy.read(locations.policy()).harnesses().keySet())
		{
			if (!recorded.equals(harness))
			{
				throw refusal("The policy " + locations.policy() + " records the harness " + recorded + ", but this " +
					"installation is for " + harness + " only.");
			}
		}
	}

	/**
	 * Returns the environment without the search path entries inside the updater's data directories, so that no
	 * updater file can stand in for the harness program.
	 *
	 * @param environment the user's environment
	 * @param data        the installation's resolved data directory
	 * @return the environment for discovering the harness program
	 * @throws IOException if a search path entry cannot be resolved
	 */
	private static Map<String, String> withoutUpdaterSearchPath(Map<String, String> environment, Path data)
		throws IOException
	{
		Map<String, String> filtered = new HashMap<>(environment);
		Path base = data.getParent();
		for (Map.Entry<String, String> variable : environment.entrySet())
		{
			if (!variable.getKey().equalsIgnoreCase("PATH"))
				continue;
			List<String> kept = new ArrayList<>();
			for (String entry : variable.getValue().split(File.pathSeparator, -1))
			{
				if (!entry.isEmpty() && Path.of(entry).isAbsolute())
				{
					Path resolved = Locations.resolveExistingAncestor(Path.of(entry).normalize());
					if (resolved.startsWith(data) || (base != null && resolved.startsWith(base)))
						continue;
				}
				kept.add(entry);
			}
			filtered.put(variable.getKey(), String.join(File.pathSeparator, kept));
		}
		return filtered;
	}

	/**
	 * Refuses a Java runtime that cannot run the updater or the plugin that the harness has.
	 *
	 * @param runtime    the archive's Java runtime
	 * @param image      the archive's updater version directory
	 * @param activation the harness's activation provider
	 * @param home       the harness home
	 * @throws IOException    if a description cannot be read, or the runtime does not satisfy a requirement
	 * @throws TrustException if the installed plugin's version is malformed
	 */
	private static void requireRuntime(Path runtime, Path image, PluginActivation activation, Path home)
		throws IOException, TrustException
	{
		RuntimeDescription description = RuntimeDescription.read(runtime);
		List<Path> requirements = new ArrayList<>();
		requirements.add(image.resolve(REQUIREMENT_FILE));
		InstalledState installed = activation.installed(home);
		if (installed.version().isPresent())
		{
			ReleaseVersion.parse(installed.version().get());
			for (ActivationStep step : activation.plan(home, installed.version().get()))
			{
				if (!(step instanceof ReplaceDirectory replace))
					continue;
				Path requirement = replace.live().resolve(RuntimeRequirement.PLUGIN_FILE);
				if (Files.isRegularFile(requirement, NOFOLLOW_LINKS))
					requirements.add(requirement);
			}
		}
		for (Path file : requirements)
		{
			RuntimeRequirement requirement = RuntimeRequirement.read(file);
			if (!description.satisfies(requirement))
			{
				throw new IOException(file + " needs Java " + requirement.javaFeature() + " with the modules " +
					requirement.modules() + ", but this release's Java runtime is " + description.javaVersion() +
					" with " + description.modules() + "; run the entry command of a release whose runtime can " +
					"run it.");
			}
		}
	}

	/**
	 * Returns the updater version that the installation selects, unless an operation left a runtime replacement to the
	 * stable launcher, which leaves the installed bundle in between two releases.
	 *
	 * @return the version, or null when the installation selects none, its selection is malformed or a runtime
	 *         replacement is pending
	 * @throws IOException if the selection cannot be read
	 */
	private String selectedVersion() throws IOException
	{
		Path data = locations.data();
		Path current = data.resolve(CURRENT_FILE);
		if (Files.exists(data.resolve(Bundle.SWAP_REQUEST), NOFOLLOW_LINKS) ||
			!Files.isRegularFile(current, NOFOLLOW_LINKS))
		{
			return null;
		}
		String selected = BoundedInput.readUtf8(current, 128, "The selected updater version").strip();
		if (!isVersion(selected))
			return null;
		return selected;
	}

	/**
	 * Returns the version of the previous bundle that the installation keeps for rollback.
	 *
	 * @param engine the installation's engine
	 * @return the version, or null when the installation keeps no previous bundle or its record is unreadable, which
	 *         setup then repairs
	 */
	private static String previousBundle(UpdateEngine engine)
	{
		try
		{
			return engine.status().previousVersion().orElse(null);
		}
		catch (IOException _)
		{
			return null;
		}
	}

	/**
	 * Puts back the installed runtime that a setup which stopped while it replaced the runtime had moved aside. A
	 * runtime that the stable launcher replaces is left to the launcher, which records its replacement in a request.
	 *
	 * @throws IOException if the runtime cannot be moved back
	 */
	private void restoreRuntimeAside() throws IOException
	{
		Path data = locations.data();
		Path installedRuntime = data.resolve(RUNTIME);
		Path aside = data.resolve(Bundle.RUNTIME_PREVIOUS);
		if (!Files.exists(installedRuntime, NOFOLLOW_LINKS) && Files.isDirectory(aside, NOFOLLOW_LINKS) &&
			!Files.exists(data.resolve(Bundle.SWAP_REQUEST), NOFOLLOW_LINKS))
		{
			mover.move(aside, installedRuntime);
		}
	}

	/**
	 * Returns whether the installed runtime is a verified copy of the archive's runtime.
	 *
	 * @param archived  the archive's runtime
	 * @param installed the installed runtime
	 * @return true if both runtimes match their manifests and the manifests are identical; false if either runtime is
	 * 	missing, has no manifest or does not match it
	 */
	private static boolean sameRuntime(Path archived, Path installed)
	{
		if (!Files.isDirectory(installed, NOFOLLOW_LINKS))
			return false;
		try
		{
			return SharedRuntime.identity(installed).equals(SharedRuntime.identity(archived));
		}
		catch (IOException _)
		{
			// A runtime that cannot be verified is replaced
			return false;
		}
	}

	/**
	 * Replaces an installed directory with a copy of the archive's directory, and deletes the replaced directory.
	 *
	 * @param source the archive's directory
	 * @param target the installed directory
	 * @throws IOException if the copy or a rename fails; the target is then unchanged
	 */
	private void replaceDirectory(Path source, Path target) throws IOException
	{
		Path aside = target.resolveSibling("." + target.getFileName() + "-old");
		moveIntoPlace(source, target, aside);
		LinkSafeDeletion.delete(aside, target.getParent());
	}

	/**
	 * Replaces an installed directory with a copy of the archive's directory, and keeps the replaced directory aside.
	 * The copy is staged beside the target, the target is renamed aside, and the copy is renamed into place; if that
	 * fails, the target is renamed back.
	 *
	 * @param source the archive's directory
	 * @param target the installed directory
	 * @param aside  the location for the replaced directory, beside the target, which this method replaces
	 * @throws IOException if the copy or a rename fails; the target is then unchanged
	 */
	private void moveIntoPlace(Path source, Path target, Path aside) throws IOException
	{
		Path parent = target.getParent();
		Path staged = staged(target);
		LinkSafeDeletion.delete(staged, parent);
		LinkSafeDeletion.delete(aside, parent);
		TreeCopy.copy(source, staged);
		boolean replacing = Files.exists(target, NOFOLLOW_LINKS);
		try
		{
			if (replacing)
				mover.move(target, aside);
		}
		catch (IOException e)
		{
			LinkSafeDeletion.delete(staged, parent);
			throw notReplaced(target, e);
		}
		try
		{
			mover.move(staged, target);
		}
		catch (IOException e)
		{
			if (replacing)
			{
				try
				{
					mover.move(aside, target);
				}
				catch (IOException restore)
				{
					e.addSuppressed(restore);
					throw new IOException("Setup could not replace " + target + " and could not move the earlier copy " +
						"back from " + aside + ": " + e.getMessage(), e);
				}
			}
			LinkSafeDeletion.delete(staged, parent);
			throw notReplaced(target, e);
		}
	}

	/**
	 * Returns where a replacement of an installed directory stages its copy.
	 *
	 * @param target the installed directory
	 * @return the staged copy's location, beside the target
	 */
	private static Path staged(Path target)
	{
		return target.resolveSibling("." + target.getFileName() + "-new");
	}

	/**
	 * Creates the failure of a directory replacement that left the installed directory unchanged.
	 *
	 * @param target the installed directory
	 * @param cause  the failed rename
	 * @return the failure
	 */
	private static IOException notReplaced(Path target, IOException cause)
	{
		return new IOException("Setup could not replace " + target + ": " + cause.getMessage() + ". Close every " +
			"program that runs from it, then run the entry command again.", cause);
	}

	/**
	 * Deletes a runtime replacement that an earlier operation left to the stable launcher, because the installed
	 * runtime and {@code current} now select this release. The request goes first, so that no later operation acts on
	 * it without its staged runtime.
	 *
	 * @throws IOException if a file or directory cannot be deleted
	 */
	private void discardReplacement() throws IOException
	{
		Path data = locations.data();
		LinkSafeDeletion.delete(data.resolve(Bundle.SWAP_REQUEST), data);
		LinkSafeDeletion.delete(data.resolve(Bundle.SWAP_RESULT), data);
		LinkSafeDeletion.delete(data.resolve(Bundle.RUNTIME_NEXT), data);
	}

	/**
	 * Deletes every updater version directory other than the kept ones.
	 *
	 * @param kept the installed version and the previous bundle's version, if any
	 * @throws IOException if a directory cannot be deleted
	 */
	private void deleteOtherVersions(List<String> kept) throws IOException
	{
		List<Path> others = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(locations.data()))
		{
			for (Path entry : entries)
			{
				String name = entry.getFileName().toString();
				if (!kept.contains(name) && isVersion(name) && Files.isDirectory(entry, NOFOLLOW_LINKS))
					others.add(entry);
			}
		}
		for (Path other : others)
			LinkSafeDeletion.delete(other, locations.data());
	}

	/**
	 * Returns whether a name is a release version.
	 *
	 * @param name the name
	 * @return true if it is a release version
	 */
	static boolean isVersion(String name)
	{
		try
		{
			ReleaseVersion.parse(name);
			return true;
		}
		catch (TrustException _)
		{
			return false;
		}
	}

	/**
	 * Installs the stable launcher.
	 *
	 * @param launcher the launcher's path
	 * @throws IOException if the template is missing or the launcher cannot be written
	 */
	private void writeLauncher(Path launcher) throws IOException
	{
		String resource = "stable-launcher.sh";
		if (platform.isWindows())
			resource = "stable-launcher.ps1";
		try (InputStream input = Setup.class.getResourceAsStream(
			"/com/holdmyspot/cat/common/updater/cli/" + resource))
		{
			if (input == null)
				throw new IOException("The updater image is missing its stable launcher template " + resource + ".");
			String text = BoundedInput.readUtf8(input, resource, 65_536, "The updater launcher template");
			write(launcher, text, PRIVATE_EXECUTABLE);
		}
	}

	/**
	 * Refuses protected destinations inside a project or harness home.
	 *
	 * @param data       the resolved updater data directory
	 * @param policyPath the resolved policy file
	 * @param excluded   the directory that must not contain either protected location
	 * @throws TrustException if an updater destination lies inside excluded
	 */
	private static void refuseInside(Path data, Path policyPath, Path excluded) throws TrustException
	{
		if (data.startsWith(excluded) || policyPath.startsWith(excluded))
			throw refusal("Updater data and policy must be outside the harness homes and the working directory.");
	}

	/**
	 * Refuses an unwritable destination or an existing symbolic link before setup changes any authority file.
	 *
	 * @param path the protected file setup will atomically replace
	 * @throws TrustException if the existing destination is not a regular file
	 * @throws IOException if its nearest existing parent cannot accept the replacement
	 */
	private static void requireWritable(Path path) throws TrustException, IOException
	{
		Path existing = path;
		if (Files.exists(path, NOFOLLOW_LINKS))
		{
			if (!Files.isRegularFile(path, NOFOLLOW_LINKS))
				throw refusal("Setup requires a regular file at " + path + ".");
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
	 * Replaces one setup-owned file atomically, cleaning its sibling temporary file on failure.
	 *
	 * @param path        the file being replaced
	 * @param text        its complete new UTF-8 content
	 * @param permissions its owner-only permissions where POSIX permissions are supported
	 * @throws IOException if writing or replacement fails
	 */
	private void write(Path path, String text, Set<PosixFilePermission> permissions) throws IOException
	{
		Files.createDirectories(path.getParent());
		if (Files.isSymbolicLink(path))
			throw new IOException("Setup refuses to replace a symbolic link: " + path);
		Path temporary = Files.createTempFile(path.getParent(), ".cat-updater-", ".tmp");
		try
		{
			Files.writeString(temporary, text, UTF_8);
			setPermissions(temporary, permissions);
			replacer.replace(temporary, path);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Applies owner-only permissions on file systems that implement POSIX permissions.
	 *
	 * @param path        the setup file
	 * @param permissions its owner-only permissions
	 * @throws IOException if permissions cannot be applied on a supporting file system
	 */
	private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException
	{
		if (path.getFileSystem().supportedFileAttributeViews().contains("posix"))
			Files.setPosixFilePermissions(path, permissions);
	}

	/**
	 * Creates a setup-policy refusal.
	 *
	 * @param message the rejected setup condition
	 * @return the refusal
	 */
	private static TrustException refusal(String message)
	{
		return new TrustException(Reason.MALFORMED_POLICY, message);
	}

	/**
	 * The outcome of setup.
	 *
	 * @param version     the installed updater version
	 * @param plugin      the outcome of the plugin step
	 * @param launcher    the stable launcher
	 * @param permissions the permission entries that setup has added to the harness configuration, by entry key; empty
	 *                    when the plugin step failed
	 * @param policy      the written policy
	 */
	public record Outcome(String version, UpdateResult plugin, Path launcher, Map<String, List<String>> permissions,
		TrustPolicy policy)
	{
		/**
		 * Creates the outcome.
		 *
		 * @param version     the installed updater version
		 * @param plugin      the outcome of the plugin step
		 * @param launcher    the stable launcher
		 * @param permissions the permission entries that setup has added to the harness configuration, by entry key
		 * @param policy      the written policy
		 * @throws NullPointerException if any of the arguments are null
		 */
		public Outcome
		{
			requireThat(version, "version").isNotBlank();
			requireThat(plugin, "plugin").isNotNull();
			requireThat(launcher, "launcher").isNotNull();
			requireThat(policy, "policy").isNotNull();
			permissions = Map.copyOf(permissions);
		}
	}
}
