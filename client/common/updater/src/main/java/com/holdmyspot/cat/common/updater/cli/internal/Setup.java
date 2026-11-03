package com.holdmyspot.cat.common.updater.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import com.holdmyspot.cat.common.updater.trust.TrustException.Reason;
import com.holdmyspot.cat.common.updater.trust.TrustPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.AccessDeniedException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Performs the user's explicit updater setup, replacing the policy atomically and selecting the image last.
 * Only setup may change the protected policy-path record and the stable launcher.
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
	 * The stable launcher's active-image selection file, relative to the data directory.
	 */
	private static final Path CURRENT_FILE = Path.of("current");
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

	/**
	 * Creates explicit setup for one installed image.
	 *
	 * @param locations the user's selected protected locations
	 * @param platform  the current platform
	 * @param replacer  atomically replaces policy, anchor, launcher and current files
	 */
	public Setup(Locations locations, Platform platform, FileReplacer replacer)
	{
		requireThat(locations, "locations").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		this.locations = locations;
		this.platform = platform;
		this.replacer = replacer;
	}

	/**
	 * Records the harness home, installs the stable launcher and selects this image.
	 * Other recorded harness entries are retained; release authority comes from the image's default policy.
	 * Each replaced file is observed completely or in its previous form. The current selection changes last.
	 *
	 * @param harness the selected activation provider's identifier
	 * @param home    the existing absolute harness home
	 * @param cwd     the existing working directory from which the user explicitly ran setup
	 * @return the merged policy
	 * @throws TrustException if this build has no release key, or a protected path lies inside a harness home
	 *                        or cwd
	 * @throws IOException    if setup files cannot be read, written or atomically replaced
	 */
	public TrustPolicy setUp(String harness, Path home, Path cwd) throws TrustException, IOException
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(home, "home").isNotNull();
		requireThat(cwd, "cwd").isDirectory();
		if (!home.isAbsolute() || !Files.isDirectory(home))
			throw refusal("Setup --home must name an existing absolute harness directory.");
		home = home.toRealPath();
		TrustPolicy defaults = TrustPolicy.read(locations.image().resolve(DEFAULT_POLICY));
		if (defaults.keys().isEmpty())
			throw refusal("This build has no release key.");
		String version = version();
		Path data = Locations.resolveExistingAncestor(locations.data());
		Path policyPath = Locations.resolveExistingAncestor(locations.policy());
		if (!locations.image().getParent().equals(data) ||
			!locations.image().getFileName().toString().equals(version))
		{
			throw refusal("Setup must run from the versioned image inside the selected updater data directory.");
		}
		Map<String, Path> harnesses = new TreeMap<>();
		if (Files.exists(locations.policy(), LinkOption.NOFOLLOW_LINKS))
		{
			if (!Files.isRegularFile(locations.policy(), LinkOption.NOFOLLOW_LINKS))
				throw refusal("The protected policy must be a regular file, not a symbolic link.");
			harnesses.putAll(TrustPolicy.read(locations.policy()).harnesses());
		}
		harnesses.put(harness, home);
		refuseInside(data, policyPath, cwd.toRealPath());
		for (Path harnessHome : harnesses.values())
			refuseInside(data, policyPath, Locations.resolveExistingAncestor(harnessHome));
		if (data.toString().codePoints().anyMatch(Character::isISOControl) ||
			policyPath.toString().codePoints().anyMatch(Character::isISOControl))
		{
			throw refusal("Updater data and policy paths may not contain control characters.");
		}
		TrustPolicy policy = TrustPolicy.of(defaults.keys(), defaults.releaseBaseUrl(), defaults.minimum(),
			defaults.maximumMajor(), harnesses);

		requireWritable(locations.policy());
		requireWritable(locations.image().resolve(Locations.POLICY_ANCHOR));
		requireWritable(locations.launcher(platform));
		requireWritable(locations.data().resolve(CURRENT_FILE));
		Files.createDirectories(locations.policy().getParent());
		Files.createDirectories(locations.launcher(platform).getParent());
		policy.write(locations.policy(), replacer);
		setPermissions(locations.policy(), PRIVATE_FILE);
		write(locations.image().resolve(Locations.POLICY_ANCHOR), policyPath + "\n", PRIVATE_FILE);
		String resource = "stable-launcher.sh";
		if (platform.isWindows())
			resource = "stable-launcher.cmd";
		try (InputStream input = Setup.class.getResourceAsStream(
			"/com/holdmyspot/cat/common/updater/cli/" + resource))
		{
			if (input == null)
				throw new IOException("The updater image is missing its stable launcher template.");
			String launcher = BoundedInput.readUtf8(input, resource, 65_536, "The updater launcher template");
			write(locations.launcher(platform), launcher, PRIVATE_EXECUTABLE);
		}
		write(locations.data().resolve(CURRENT_FILE), version + "\n", PRIVATE_FILE);
		return policy;
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
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
		{
			if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
				throw refusal("Setup requires a regular file at " + path + ".");
			if (!Files.isWritable(path))
				throw new AccessDeniedException(path.toString());
			existing = path.getParent();
		}
		while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS))
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
}
