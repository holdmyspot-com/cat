package com.holdmyspot.cat.common.installer.trust;

import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * One plugin archive of a release, as its verified manifest declares it. The installer downloads the archive by its
 * name and accepts it only when its size and SHA-256 digest match.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ReleaseAsset
{
	/**
	 * The largest archive that a manifest may declare: 512 MiB.
	 */
	public static final long MAXIMUM_SIZE = 512L * 1024 * 1024;
	/**
	 * The fields of an asset entry. The manifest is refused when an entry has another field.
	 */
	private static final Set<String> FIELDS = Set.of("name", "kind", "harness", "platform", "size", "sha256",
		"top_directory");
	/**
	 * A platform's spelling in asset names, such as {@code linux-x64}: an operating system and an architecture.
	 */
	private static final Pattern PLATFORM = Pattern.compile("[a-z0-9]+-[a-z0-9]+");
	/**
	 * A SHA-256 digest in lowercase hexadecimal, so that each digest has one spelling.
	 */
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	/**
	 * The suffix of every asset's name, which its {@code top_directory} lacks.
	 */
	private static final String ARCHIVE_SUFFIX = ".tar.gz";
	/**
	 * The only value of an asset's {@code kind} field: every archive of a release is a plugin, which ships its
	 * installer.
	 */
	private static final String PLUGIN_KIND = "plugin";
	private final String name;
	private final String harness;
	private final String platform;
	private final long size;
	private final String sha256;
	private final String topDirectory;

	/**
	 * Reads an asset entry of a verified manifest.
	 *
	 * @param entry   the entry
	 * @param path    the entry's location in the manifest, such as {@code assets[0]}
	 * @param json    the manifest's field reader
	 * @param version the release's version, which the asset's name contains
	 * @return the asset
	 * @throws TrustException if the entry breaks a rule of the manifest format
	 */
	static ReleaseAsset parse(JsonNode entry, String path, TrustJson json, ReleaseVersion version)
		throws TrustException
	{
		if (!entry.isObject())
			throw json.refusal("The release manifest's " + path + " must be a JSON object");
		json.requireOnly(entry, path, FIELDS);
		String name = json.requiredString(entry, path, "name");
		String kind = json.requiredString(entry, path, "kind");
		if (!kind.equals(PLUGIN_KIND))
		{
			throw json.refusal(json.field(path, "kind") + " must be \"" + PLUGIN_KIND + "\": \"" +
				TrustText.excerpt(kind) + "\"");
		}
		String harness = json.requiredString(entry, path, "harness");
		if (!HarnessIdentifier.SYNTAX.matcher(harness).matches())
		{
			throw json.refusal(json.field(path, "harness") + " must be a lowercase letter followed by at most 31 " +
				"lowercase letters, digits or hyphens: \"" + TrustText.excerpt(harness) + "\"");
		}
		String platform = json.requiredString(entry, path, "platform");
		if (!PLATFORM.matcher(platform).matches())
		{
			throw json.refusal(json.field(path, "platform") + " must be an operating system and an architecture " +
				"joined by a hyphen, such as linux-x64: \"" + TrustText.excerpt(platform) + "\"");
		}
		long size = json.requiredInteger(entry, path, "size", 1, MAXIMUM_SIZE);
		String sha256 = json.requiredString(entry, path, "sha256");
		if (!SHA256.matcher(sha256).matches())
		{
			throw json.refusal(json.field(path, "sha256") + " must be 64 lowercase hexadecimal digits: \"" +
				TrustText.excerpt(sha256) + "\"");
		}
		String topDirectory = json.requiredString(entry, path, "top_directory");

		String expectedName = "cat-" + harness + "-" + platform + "-" + version.tag() + ARCHIVE_SUFFIX;
		if (!name.equals(expectedName))
		{
			throw json.refusal(json.field(path, "name") + " must be \"" + expectedName + "\", which its harness, " +
				"platform and the release version determine: \"" + TrustText.excerpt(name) + "\"");
		}
		String expectedTopDirectory = name.substring(0, name.length() - ARCHIVE_SUFFIX.length());
		if (!topDirectory.equals(expectedTopDirectory))
		{
			throw json.refusal(json.field(path, "top_directory") + " must be \"" + expectedTopDirectory +
				"\", the asset's name without " + ARCHIVE_SUFFIX + ": \"" + TrustText.excerpt(topDirectory) + "\"");
		}
		return new ReleaseAsset(name, harness, platform, size, sha256, topDirectory);
	}

	/**
	 * Creates an asset.
	 *
	 * @param name         the archive's file name
	 * @param harness      the harness that the plugin is for
	 * @param platform     the platform that the archive runs on
	 * @param size         the archive's size in bytes
	 * @param sha256       the archive's SHA-256 digest in lowercase hexadecimal
	 * @param topDirectory the one directory at the top of the archive
	 */
	private ReleaseAsset(String name, String harness, String platform, long size, String sha256, String topDirectory)
	{
		this.name = name;
		this.harness = harness;
		this.platform = platform;
		this.size = size;
		this.sha256 = sha256;
		this.topDirectory = topDirectory;
	}

	/**
	 * Returns the archive's file name, under which the release publishes it.
	 *
	 * @return the name, such as {@code cat-harness-a-linux-x64-v1.0.tar.gz}
	 */
	public String name()
	{
		return name;
	}

	/**
	 * Returns the harness that the plugin archive is for.
	 *
	 * @return the harness identifier
	 */
	public String harness()
	{
		return harness;
	}

	/**
	 * Returns the platform that the archive runs on.
	 *
	 * @return the platform, such as {@code linux-x64}
	 */
	public String platform()
	{
		return platform;
	}

	/**
	 * Returns the archive's size.
	 *
	 * @return the size in bytes, from 1 to {@link #MAXIMUM_SIZE}
	 */
	public long size()
	{
		return size;
	}

	/**
	 * Returns the archive's SHA-256 digest.
	 *
	 * @return 64 lowercase hexadecimal digits
	 */
	public String sha256()
	{
		return sha256;
	}

	/**
	 * Returns the one directory at the top of the archive.
	 *
	 * @return the archive's name without {@code .tar.gz}
	 */
	public String topDirectory()
	{
		return topDirectory;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
