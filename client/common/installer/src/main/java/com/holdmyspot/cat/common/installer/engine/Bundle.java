package com.holdmyspot.cat.common.installer.engine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.runner.RuntimeDescription;
import com.holdmyspot.cat.common.runner.RuntimeRequirement;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The directories of one installation's bundle beside its state: the {@code current} selection, the Java runtime that
 * the installer and plugin run on, the runtime staged by an update or kept from the bundle that it replaced, and the
 * installer version directories, each of which ships its release's stable launcher. An update stages the new runtime
 * and installer and installs the new stable launcher; the stable launcher that is running renames the runtimes and
 * rewrites {@code current} after the installer's JVM has exited. The replaced runtime and installer stay as the
 * previous bundle, together with the plugin that the activation journal keeps, until the next update.
 */
public final class Bundle
{
	/**
	 * The data-directory-relative file that names the selected installer version.
	 */
	public static final String CURRENT = "current";
	/**
	 * The name of the installation's Java runtime, which the installer and plugin run on.
	 */
	public static final String RUNTIME = "runtime";
	/**
	 * The name of the runtime that an update staged for the launcher.
	 */
	public static final String RUNTIME_NEXT = "runtime.next";
	/**
	 * The name of the runtime that the last update replaced.
	 */
	public static final String RUNTIME_PREVIOUS = "runtime.previous";
	/**
	 * The data-directory-relative request with which an operation hands the replacement of the Java runtime to the
	 * stable launcher.
	 */
	public static final Path SWAP_REQUEST = Path.of("state/runtime-swap");
	/**
	 * The data-directory-relative result that the stable launcher prints once it finished the runtime replacement.
	 */
	public static final Path SWAP_RESULT = Path.of("state/runtime-swap-result.json");
	/**
	 * The installer-version-relative anchor that names the protected policy.
	 */
	public static final Path POLICY_ANCHOR = Path.of("conf/cat-updater/policy-path");
	/**
	 * The installer-version-relative description of the Java runtime that the installer needs.
	 */
	public static final Path REQUIREMENT_FILE = Path.of("runtime-requirement.json");
	/**
	 * The installer-version-relative directory that holds the release's stable launchers, each under the name that it
	 * has in the installation's {@code bin} directory.
	 */
	public static final Path STABLE_LAUNCHERS = Path.of("stable-launcher");
	/**
	 * The installer-version-relative release version file.
	 */
	private static final Path VERSION_FILE = Path.of("VERSION");
	/**
	 * The largest {@code current} file or version file, including its newline.
	 */
	private static final int MAXIMUM_VERSION_BYTES = 128;
	/**
	 * The largest policy anchor.
	 */
	private static final int MAXIMUM_ANCHOR_BYTES = 65_536;
	/**
	 * The largest stable launcher.
	 */
	private static final int MAXIMUM_LAUNCHER_BYTES = 65_536;
	/**
	 * Only the installing user may execute or change the stable launcher.
	 */
	private static final Set<PosixFilePermission> LAUNCHER_MODE = PosixFilePermissions.fromString("rwx------");
	private final Path data;
	private final String launcher;
	private final FileReplacer replacer;

	/**
	 * Creates the bundle of an installation.
	 *
	 * @param data     the installation's absolute, normalized data directory
	 * @param windows  true if the installation runs the Windows stable launcher
	 * @param replacer renames a new stable launcher over the installed one
	 */
	Bundle(Path data, boolean windows, FileReplacer replacer)
	{
		this.data = data;
		if (windows)
			this.launcher = "cat-updater.ps1";
		else
			this.launcher = "cat-updater";
		this.replacer = replacer;
	}

	/**
	 * Returns the installation's Java runtime.
	 *
	 * @return the runtime directory
	 */
	Path runtime()
	{
		return data.resolve(RUNTIME);
	}

	/**
	 * Returns the runtime that the launcher renames into place to finish an update's runtime replacement.
	 *
	 * @return the staged runtime directory
	 */
	Path staged()
	{
		return data.resolve(RUNTIME_NEXT);
	}

	/**
	 * Returns the location to which the launcher renames the replaced runtime.
	 *
	 * @return the replaced runtime's new location
	 */
	Path replaced()
	{
		return data.resolve(RUNTIME_PREVIOUS);
	}

	/**
	 * Reads the selected installer version.
	 *
	 * @return the version, or empty when the installation has no {@code current} file
	 * @throws IOException if the file is a link, unreadable or malformed
	 */
	Optional<String> current() throws IOException
	{
		Path file = data.resolve(CURRENT);
		StateFiles.requireNoLinks(file);
		if (!Files.exists(file, NOFOLLOW_LINKS))
			return Optional.empty();
		String text = BoundedInput.readUtf8(file, MAXIMUM_VERSION_BYTES, "The current installer version");
		try
		{
			if (!text.endsWith("\n"))
				throw new TrustException(TrustException.Reason.MALFORMED_POLICY, "missing newline");
			String version = text.substring(0, text.length() - 1);
			if (!ReleaseVersion.parse(version).toString().equals(version))
				throw new TrustException(TrustException.Reason.MALFORMED_POLICY, "not canonical");
			return Optional.of(version);
		}
		catch (TrustException e)
		{
			throw new IOException("The current installer version " + file + " must contain one canonical version " +
				"and one newline; rerun the entry command to repair the installation.", e);
		}
	}

	/**
	 * Selects an installer version, as the launcher does when it finishes a runtime replacement.
	 *
	 * @param version the version
	 * @throws IOException if {@code current} cannot be replaced
	 */
	void select(String version) throws IOException
	{
		StateFiles.write(data.resolve(CURRENT), (version + "\n").getBytes(UTF_8));
	}

	/**
	 * Checks that an extracted installer archive mirrors an installation of one version: its Java runtime, with a
	 * readable description, and the installer version directory, with the version, the runtime requirement and this
	 * platform's stable launcher.
	 *
	 * @param tree    the archive's top directory
	 * @param version the release version
	 * @throws IOException    if the tree is incomplete, contains a link that leaves it, or names another version
	 * @throws TrustException if a link cannot be resolved or leaves the tree
	 */
	void validate(Path tree, String version) throws IOException, TrustException
	{
		if (!Files.isDirectory(tree, NOFOLLOW_LINKS))
			throw new ActivationTransaction.Refused("The extracted installer archive is missing: " + tree);
		StagedRelease.checkLinks(tree.toRealPath());
		Set<String> names = Set.of(RUNTIME, version);
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(tree))
		{
			for (Path entry : entries)
			{
				String name = entry.getFileName().toString();
				if (!names.contains(name) || !Files.isDirectory(entry, NOFOLLOW_LINKS))
				{
					throw new ActivationTransaction.Refused("The installer archive may contain only the directories " +
						RUNTIME + " and " + version + ": " + entry);
				}
			}
		}
		RuntimeDescription.read(tree.resolve(RUNTIME));
		Path image = tree.resolve(version);
		String declared = BoundedInput.readUtf8(image.resolve(VERSION_FILE), MAXIMUM_VERSION_BYTES,
			"The installer version").strip();
		if (!declared.equals(version))
		{
			throw new ActivationTransaction.Refused("The installer archive declares version " + declared +
				" instead of " + version);
		}
		RuntimeRequirement.read(image.resolve(REQUIREMENT_FILE));
		readLauncher(image);
	}

	/**
	 * Reads the stable launcher that an installed installer version ships for this platform.
	 *
	 * @param version the installer version
	 * @return the launcher's content
	 * @throws IOException if the version directory has no such launcher or it cannot be read
	 */
	String stableLauncher(String version) throws IOException
	{
		return readLauncher(data.resolve(version));
	}

	/**
	 * Reads the stable launcher that an installer version directory ships for this platform.
	 *
	 * @param image the installer version directory
	 * @return the launcher's content
	 * @throws IOException if the directory has no such launcher or it cannot be read
	 */
	private String readLauncher(Path image) throws IOException
	{
		Path file = image.resolve(STABLE_LAUNCHERS).resolve(launcher);
		StateFiles.requireNoLinks(file);
		if (!Files.isRegularFile(file, NOFOLLOW_LINKS))
		{
			throw new ActivationTransaction.Refused("The installer version directory has no stable launcher " +
				file + ".");
		}
		return BoundedInput.readUtf8(file, MAXIMUM_LAUNCHER_BYTES, "The stable launcher");
	}

	/**
	 * Replaces the installation's stable launcher. The new launcher is written beside the installed one and renamed
	 * over it, so a launcher that is running, which reads its script while it runs, keeps reading the file that it
	 * started from.
	 *
	 * @param text the new launcher's content
	 * @throws IOException if the launcher cannot be replaced
	 */
	void installLauncher(String text) throws IOException
	{
		StateFiles.replace(data.resolve("bin").resolve(launcher), text.getBytes(UTF_8), LAUNCHER_MODE, replacer);
	}

	/**
	 * Moves an extracted, validated installer archive into the installation: its runtime as {@code runtime.next} and its
	 * installer version directory beside the running one, with the running version's policy anchor.
	 *
	 * @param tree    the archive's top directory, on the installation's file system
	 * @param version the release version
	 * @param running the running installer version, whose policy anchor the new version gets
	 * @throws IOException if a move or the anchor copy fails
	 */
	void stage(Path tree, String version, String running) throws IOException
	{
		discard(version);
		Path anchor = data.resolve(running).resolve(POLICY_ANCHOR);
		StateFiles.requireNoLinks(anchor);
		String policy = BoundedInput.readUtf8(anchor, MAXIMUM_ANCHOR_BYTES, "The installer policy path");
		Files.move(tree.resolve(RUNTIME), data.resolve(RUNTIME_NEXT));
		Path image = data.resolve(version);
		Files.move(tree.resolve(version), image);
		StateFiles.write(image.resolve(POLICY_ANCHOR), policy.getBytes(UTF_8));
	}

	/**
	 * Stages the bundle that an earlier update replaced and kept: a copy of its runtime as {@code runtime.next}, and
	 * its installer version directory in place, with the running version's policy anchor.
	 *
	 * @param version the kept version
	 * @param running the running installer version, whose policy anchor the kept version gets
	 * @throws IOException if the copy or the anchor copy fails
	 */
	void restage(String version, String running) throws IOException
	{
		discardStaged();
		Path anchor = data.resolve(running).resolve(POLICY_ANCHOR);
		StateFiles.requireNoLinks(anchor);
		String policy = BoundedInput.readUtf8(anchor, MAXIMUM_ANCHOR_BYTES, "The installer policy path");
		ActivationTransaction.copyTree(data.resolve(RUNTIME_PREVIOUS), data.resolve(RUNTIME_NEXT));
		StateFiles.write(data.resolve(version).resolve(POLICY_ANCHOR), policy.getBytes(UTF_8));
	}

	/**
	 * Deletes a staged runtime.
	 *
	 * @throws IOException if it cannot be deleted
	 */
	void discardStaged() throws IOException
	{
		LinkSafeDeletion.delete(data.resolve(RUNTIME_NEXT), data);
	}

	/**
	 * Deletes a staged runtime and installer version directory.
	 *
	 * @param version the staged installer version
	 * @throws IOException if either cannot be deleted
	 */
	void discard(String version) throws IOException
	{
		LinkSafeDeletion.delete(data.resolve(RUNTIME_NEXT), data);
		LinkSafeDeletion.delete(data.resolve(version), data);
	}

	/**
	 * Indicates whether the installation keeps a complete previous bundle: the runtime and installer that an update
	 * replaced, of the version whose plugin the activation journal keeps.
	 *
	 * @param current  the selected version
	 * @param previous the plugin version that the activation journal keeps
	 * @return true if the bundle of {@code previous} can be restored
	 */
	boolean hasPrevious(String current, String previous)
	{
		return !previous.equals(current) && Files.isDirectory(data.resolve(RUNTIME_PREVIOUS), NOFOLLOW_LINKS) &&
			Files.isDirectory(data.resolve(previous), NOFOLLOW_LINKS);
	}

	/**
	 * Deletes what no pending replacement or previous bundle needs: a staged runtime and every installer version
	 * directory other than the selected one and the previous bundle's, and the replaced runtime when there is no
	 * previous bundle.
	 *
	 * @param current  the selected version
	 * @param previous the previous bundle's version, or empty when there is none
	 * @throws IOException if a directory cannot be deleted
	 */
	void prune(String current, Optional<String> previous) throws IOException
	{
		LinkSafeDeletion.delete(data.resolve(RUNTIME_NEXT), data);
		if (previous.isEmpty())
			LinkSafeDeletion.delete(data.resolve(RUNTIME_PREVIOUS), data);
		List<String> kept = new ArrayList<>(List.of(current));
		previous.ifPresent(kept::add);
		deleteVersions(kept);
	}

	/**
	 * Deletes the previous bundle's runtime and every installer version directory other than two, after an update
	 * replaced the previous bundle with the one it replaces.
	 *
	 * @param replaced the version that the update replaces
	 * @param staged   the version that the update staged
	 * @throws IOException if a directory cannot be deleted
	 */
	void deleteOlder(String replaced, String staged) throws IOException
	{
		LinkSafeDeletion.delete(data.resolve(RUNTIME_PREVIOUS), data);
		deleteVersions(List.of(replaced, staged));
	}

	/**
	 * Deletes every installer version directory other than the kept ones.
	 *
	 * @param kept the versions to keep
	 * @throws IOException if a directory cannot be deleted
	 */
	private void deleteVersions(List<String> kept) throws IOException
	{
		if (!Files.isDirectory(data, NOFOLLOW_LINKS))
			return;
		List<Path> others = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(data))
		{
			for (Path entry : entries)
			{
				String name = entry.getFileName().toString();
				if (!kept.contains(name) && isVersion(name) && Files.isDirectory(entry, NOFOLLOW_LINKS))
					others.add(entry);
			}
		}
		for (Path other : others)
			LinkSafeDeletion.delete(other, data);
	}

	/**
	 * Returns whether a name is a release version.
	 *
	 * @param name the name
	 * @return true if it is a release version
	 */
	public static boolean isVersion(String name)
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
}
