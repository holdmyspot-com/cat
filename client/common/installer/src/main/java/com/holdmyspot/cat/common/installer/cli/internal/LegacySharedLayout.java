package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.engine.LinkSafeDeletion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The single installer installation that earlier builds kept directly in the directory that now holds one installation
 * per harness: its current selection, stable launcher, installer version directories, the state of each harness and
 * its policy, which recorded every harness that it served.
 */
final class LegacySharedLayout
{
	/**
	 * The entries of the shared installation that have fixed names, besides its version directories.
	 */
	private static final List<String> FIXED_ENTRIES = List.of("current", "bin", "state");
	private final Path base;
	private final Path policy;
	private final FileReplacer replacer;

	/**
	 * Creates the shared installation's locations.
	 *
	 * @param base     the absolute directory that holds the shared installation and every harness's installation
	 * @param policy   the shared installation's absolute policy file
	 * @param replacer atomically replaces the policy file
	 * @throws NullPointerException if any of the arguments are null
	 */
	LegacySharedLayout(Path base, Path policy, FileReplacer replacer)
	{
		requireThat(base, "base").isAbsolute();
		requireThat(policy, "policy").isAbsolute();
		requireThat(replacer, "replacer").isNotNull();
		this.base = base;
		this.policy = policy;
		this.replacer = replacer;
	}

	/**
	 * Removes a harness from the shared installation: the permission entries that it asked the user to add for its
	 * stable launcher, its state and its entry in the policy. When the policy then records no harness, deletes the
	 * rest of the shared installation and the policy. A policy that is a link or that cannot be read keeps the rest of
	 * the installation. Every deletion is link-safe, and a harness's own installation is never touched.
	 *
	 * @param harness     the harness that now has its own installation
	 * @param integration the harness's integration, which names and removes its permission entries
	 * @param home        the absolute, normalized harness home
	 * @param platform    the current platform, which selects the shared stable launcher's file name
	 * @throws IOException    if a file cannot be read, written or deleted, or a deletion would pass through a link
	 * @throws TrustException if the policy's remaining entries cannot form a policy
	 */
	void retire(String harness, HarnessIntegration integration, Path home, Platform platform)
		throws IOException, TrustException
	{
		Map<String, List<String>> legacy = integration.legacyPermissions(launcher(platform));
		if (!legacy.isEmpty())
			integration.removePermissions(home, legacy, replacer);
		if (!Files.isDirectory(base, NOFOLLOW_LINKS))
			return;
		Path realBase = base.toRealPath();
		LinkSafeDeletion.delete(realBase.resolve("state").resolve(harness), realBase);
		if (!removeFromPolicy(harness))
			return;

		List<Path> entries = new ArrayList<>();
		for (String name : FIXED_ENTRIES)
			entries.add(realBase.resolve(name));
		try (DirectoryStream<Path> children = Files.newDirectoryStream(realBase))
		{
			for (Path child : children)
			{
				if (Setup.isVersion(child.getFileName().toString()))
					entries.add(child);
			}
		}
		for (Path entry : entries)
			LinkSafeDeletion.delete(entry, realBase);
		if (Files.exists(policy, NOFOLLOW_LINKS))
		{
			Path directory = policy.getParent().toRealPath();
			LinkSafeDeletion.delete(directory.resolve(policy.getFileName()), directory);
		}
	}

	/**
	 * Returns the shared installation's stable launcher.
	 *
	 * @param platform the current platform
	 * @return the launcher's absolute path
	 */
	private Path launcher(Platform platform)
	{
		if (platform.isWindows())
			return base.resolve("bin/cat-updater.cmd");
		return base.resolve("bin/cat-updater");
	}

	/**
	 * Removes a harness from the shared installation's policy.
	 *
	 * @param harness the harness
	 * @return true if the policy is absent or now records no harness, false if it records another harness, is a link
	 *         or cannot be read
	 * @throws IOException    if the policy cannot be read or replaced
	 * @throws TrustException if the remaining entries cannot form a policy
	 */
	private boolean removeFromPolicy(String harness) throws IOException, TrustException
	{
		if (!Files.exists(policy, NOFOLLOW_LINKS))
			return true;
		if (!Files.isRegularFile(policy, NOFOLLOW_LINKS))
			return false;
		TrustPolicy recorded;
		try
		{
			recorded = TrustPolicy.read(policy);
		}
		catch (TrustException _)
		{
			return false;
		}
		Map<String, Path> remaining = new HashMap<>(recorded.harnesses());
		boolean removed = remaining.remove(harness) != null;
		if (remaining.isEmpty())
			return true;
		if (removed)
		{
			// Earlier builds wrote policies without harness programs, which is the form this writes
			TrustPolicy.of(recorded.keys(), recorded.releaseBaseUrl(), recorded.minimum(), recorded.maximumMajor(),
				remaining).write(policy, replacer);
		}
		return false;
	}
}
