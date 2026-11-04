package com.holdmyspot.cat.common.updater.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.updater.engine.LinkSafeDeletion;
import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

/**
 * The protected locations of one harness's installation, selected at explicit setup: its updater data directory,
 * which is named after the harness, and its policy.
 *
 * @param image  the real directory of the running updater image
 * @param data   the protected data directory of the installation
 * @param policy the protected policy file
 */
public record Locations(Path image, Path data, Path policy)
{
	/**
	 * The image-relative file in which setup records the protected policy path.
	 */
	public static final Path POLICY_ANCHOR = Path.of("conf/cat-updater/policy-path");
	/**
	 * The largest supported encoded policy path, including its final newline.
	 */
	private static final int MAXIMUM_ANCHOR_BYTES = 65_536;
	/**
	 * The largest supported encoded updater version selector.
	 */
	private static final int MAXIMUM_CURRENT_BYTES = 128;

	/**
	 * Resolves the locations of one harness's installation from the user's setup environment and the running image.
	 *
	 * @param image       the running image directory
	 * @param platform    the current platform
	 * @param environment the explicit setup environment
	 * @param harness     the installation's harness
	 * @return the installation locations
	 * @throws IOException if the required absolute home or local application-data directory is absent, or the
	 *                     locations lie inside the user's {@code .cat} directory
	 */
	public static Locations forSetup(Path image, Platform platform, Map<String, String> environment, String harness)
		throws IOException
	{
		requireThat(image, "image").isDirectory();
		requireThat(platform, "platform").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(harness, "harness").isNotBlank();
		Path data;
		Path policy;
		if (platform.isWindows())
		{
			data = absoluteVariable(environment, "LOCALAPPDATA").resolve("cat-updater").resolve(harness);
			policy = data.resolve("policy.json");
		}
		else
		{
			Path home = absoluteVariable(environment, "HOME");
			data = xdg(environment, "XDG_DATA_HOME", home.resolve(".local/share")).resolve("cat-updater").
				resolve(harness);
			policy = xdg(environment, "XDG_CONFIG_HOME", home.resolve(".config")).resolve("cat-updater").
				resolve(harness).resolve("policy.json");
			Path catHome = resolveExistingAncestor(home.resolve(".cat"));
			if (resolveExistingAncestor(data).startsWith(catHome) ||
				resolveExistingAncestor(policy).startsWith(catHome))
			{
				throw new IOException("Updater data and policy must be outside the user's .cat directory.");
			}
		}
		// The directories that hold the data directory and the policy may be reached through links; the data directory
		// and the policy's own directory belong to CAT and are never followed
		return new Locations(image.toRealPath(), resolveParent(data), resolveParent(policy.getParent()).
			resolve(policy.getFileName()));
	}

	/**
	 * Returns the instruction to set a harness's installation up again with the CAT entry command.
	 *
	 * @param harness the harness
	 * @return the instruction, a sentence fragment that starts with a verb
	 */
	public static String rerunEntryCommand(String harness)
	{
		requireThat(harness, "harness").isNotBlank();
		return "run the CAT entry command again: cat.sh --harness " + harness + " (in PowerShell: cat.ps1 -Harness " +
			harness + ")";
	}

	/**
	 * Resolves routine-operation authority solely from the running image and its protected setup record.
	 *
	 * @param image the running image directory
	 * @return the installation locations
	 * @throws IOException if setup has not recorded one absolute policy path, or the anchor is a symbolic link
	 */
	public static Locations installed(Path image) throws IOException
	{
		image = image.toRealPath();
		Path anchor = image.resolve(POLICY_ANCHOR);
		String harness = image.getParent().getFileName().toString();
		if (!Files.isRegularFile(anchor, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Updater setup is missing; " + rerunEntryCommand(harness) + ".");
		String text = BoundedInput.readUtf8(anchor, MAXIMUM_ANCHOR_BYTES, "The updater policy path");
		if (!text.endsWith("\n") || text.indexOf('\n') != text.length() - 1 || text.indexOf('\r') >= 0)
		{
			throw new IOException("The protected updater policy path must contain one absolute path line; " +
				rerunEntryCommand(harness) + ".");
		}
		Path policy = Path.of(text.substring(0, text.length() - 1));
		if (!policy.isAbsolute() || !policy.normalize().equals(policy))
		{
			throw new IOException("The protected updater policy path must be absolute and normalized; " +
				rerunEntryCommand(harness) + ".");
		}
		return new Locations(image, image.getParent(), policy);
	}

	/**
	 * Checks the whole stable-launcher selection before any routine operation reads policy or state.
	 *
	 * @param image the actual running image directory
	 * @throws IOException if current is not a regular file containing exactly the image version and a
	 *                     newline
	 * @throws TrustException if the image directory does not name a supported numeric release
	 */
	public static void validateCurrent(Path image) throws IOException, TrustException
	{
		image = image.toRealPath();
		String version = image.getFileName().toString();
		ReleaseVersion.parse(version);
		Path current = image.getParent().resolve("current");
		if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS) ||
			!BoundedInput.readUtf8(current, MAXIMUM_CURRENT_BYTES, "The current updater version").equals(version + "\n"))
		{
			throw new IOException("The current updater version must contain exactly the running image version " +
				"and one newline; " + rerunEntryCommand(image.getParent().getFileName().toString()) + ".");
		}
	}

	/**
	 * Resolves the directory that holds a path, keeping the path's own name.
	 *
	 * @param path an absolute path whose parent may not exist yet
	 * @return the path below the real location of its parent
	 * @throws IOException if an existing ancestor cannot be resolved
	 */
	private static Path resolveParent(Path path) throws IOException
	{
		Path normalized = path.normalize();
		return LinkSafeDeletion.resolveRoot(normalized.getParent()).resolve(normalized.getFileName());
	}

	/**
	 * Resolves existing ancestors so a symlink cannot disguise a protected path inside an excluded directory.
	 *
	 * @param path the absolute path, which may not exist yet
	 * @return the path below its nearest real ancestor
	 * @throws IOException if an existing ancestor cannot be resolved
	 */
	public static Path resolveExistingAncestor(Path path) throws IOException
	{
		return LinkSafeDeletion.resolveRoot(path);
	}

	/**
	 * Creates the protected locations value.
	 *
	 * @param image  the real directory of the running updater image
	 * @param data   the protected data directory containing the image
	 * @param policy the protected policy file
	 */
	public Locations
	{
		requireThat(image, "image").isNotNull();
		requireThat(data, "data").isNotNull();
		requireThat(policy, "policy").isNotNull();
	}

	/**
	 * Returns the harness of the installation, which names its data directory.
	 *
	 * @return the harness
	 */
	public String harness()
	{
		return data.getFileName().toString();
	}

	/**
	 * Returns the policy file of the single updater installation that earlier builds shared between harnesses, kept
	 * directly in the directory that now holds one installation per harness.
	 *
	 * @return the absolute policy file, the sibling of the directory that holds this installation's policy
	 */
	public Path legacySharedPolicy()
	{
		return policy.getParent().resolveSibling("policy.json");
	}

	/**
	 * Returns the stable launcher path for the platform.
	 *
	 * @param platform the current platform
	 * @return the absolute launcher path
	 */
	public Path launcher(Platform platform)
	{
		if (platform.isWindows())
			return data.resolve("bin/cat-updater.ps1");
		return data.resolve("bin/cat-updater");
	}

	/**
	 * Uses an absolute XDG value, ignoring a relative or absent value.
	 *
	 * @param environment the setup environment
	 * @param name        the XDG variable
	 * @param fallback    the standard home-relative location
	 * @return the selected absolute directory
	 */
	private static Path xdg(Map<String, String> environment, String name, Path fallback)
	{
		String value = environment.get(name);
		if (value == null || value.isBlank())
			return fallback;
		Path candidate = Path.of(value);
		if (candidate.isAbsolute())
			return candidate;
		return fallback;
	}

	/**
	 * Returns a required absolute directory named by the setup environment.
	 *
	 * @param environment the setup environment
	 * @param name        the variable naming the directory
	 * @return the absolute directory
	 * @throws IOException if the variable is absent, blank or relative
	 */
	private static Path absoluteVariable(Map<String, String> environment, String name) throws IOException
	{
		String value = environment.get(name);
		if (value == null || value.isBlank() || !Path.of(value).isAbsolute())
			throw new IOException(name + " must name an absolute directory for updater setup.");
		return Path.of(value);
	}
}
