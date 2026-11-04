package com.holdmyspot.cat.common.installer.trust;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * One archive of a release, as its verified manifest declares it. The installer downloads the archive by its name and
 * accepts it only when its size and SHA-256 digest match.
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
	private final String name;
	private final AssetKind kind;
	/**
	 * The harness that a plugin asset is for, or {@code null} for the installer.
	 */
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
		AssetKind kind = parseKind(json, path, json.requiredString(entry, path, "kind"));
		String harness = parseHarness(entry, path, json, kind);
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

		String expectedName = expectedName(kind, harness, version, platform);
		if (!name.equals(expectedName))
		{
			throw json.refusal(json.field(path, "name") + " must be \"" + expectedName + "\", which its kind, " +
				"harness, platform and the release version determine: \"" + TrustText.excerpt(name) + "\"");
		}
		String expectedTopDirectory = name.substring(0, name.length() - ARCHIVE_SUFFIX.length());
		if (!topDirectory.equals(expectedTopDirectory))
		{
			throw json.refusal(json.field(path, "top_directory") + " must be \"" + expectedTopDirectory +
				"\", the asset's name without " + ARCHIVE_SUFFIX + ": \"" + TrustText.excerpt(topDirectory) + "\"");
		}
		return new ReleaseAsset(name, kind, harness, platform, size, sha256, topDirectory);
	}

	/**
	 * Reads an asset's kind.
	 *
	 * @param json  the manifest's field reader
	 * @param path  the entry's location in the manifest
	 * @param value the value of the {@code kind} field
	 * @return the kind
	 * @throws TrustException if the value is neither {@code plugin} nor {@code installer}
	 */
	private static AssetKind parseKind(TrustJson json, String path, String value) throws TrustException
	{
		return switch (value)
		{
			case "plugin" -> AssetKind.PLUGIN;
			case "updater" -> AssetKind.INSTALLER;
			default -> throw json.refusal(json.field(path, "kind") + " must be \"plugin\" or \"updater\": \"" +
				TrustText.excerpt(value) + "\"");
		};
	}

	/**
	 * Reads the harness of an asset entry.
	 *
	 * @param entry the entry
	 * @param path  the entry's location in the manifest
	 * @param json  the manifest's field reader
	 * @param kind  the asset's kind
	 * @return the harness of a plugin, or {@code null} for the installer
	 * @throws TrustException if a plugin lacks a harness identifier, or the installer has a {@code harness} field
	 */
	private static String parseHarness(JsonNode entry, String path, TrustJson json, AssetKind kind)
		throws TrustException
	{
		if (kind == AssetKind.INSTALLER)
		{
			if (entry.has("harness"))
				throw json.refusal(json.field(path, "harness") + " must be absent for the installer");
			return null;
		}
		String harness = json.requiredString(entry, path, "harness");
		if (!HarnessIdentifier.SYNTAX.matcher(harness).matches())
		{
			throw json.refusal(json.field(path, "harness") + " must be a lowercase letter followed by at most 31 " +
				"lowercase letters, digits or hyphens: \"" + TrustText.excerpt(harness) + "\"");
		}
		return harness;
	}

	/**
	 * Returns the only name that an asset may have: {@code cat-<harness>-<platform>-v<version>.tar.gz} for a plugin and
	 * {@code cat-updater-v<version>-<platform>.tar.gz} for the installer.
	 *
	 * @param kind     the asset's kind
	 * @param harness  the harness of a plugin, or {@code null} for the installer
	 * @param version  the release's version
	 * @param platform the asset's platform
	 * @return the name
	 */
	private static String expectedName(AssetKind kind, String harness, ReleaseVersion version, String platform)
	{
		if (kind == AssetKind.PLUGIN)
			return "cat-" + harness + "-" + platform + "-" + version.tag() + ARCHIVE_SUFFIX;
		return "cat-updater-" + version.tag() + "-" + platform + ARCHIVE_SUFFIX;
	}

	/**
	 * Creates an asset.
	 *
	 * @param name         the archive's file name
	 * @param kind         what the archive installs
	 * @param harness      the harness of a plugin, or {@code null} for the installer
	 * @param platform     the platform that the archive runs on
	 * @param size         the archive's size in bytes
	 * @param sha256       the archive's SHA-256 digest in lowercase hexadecimal
	 * @param topDirectory the one directory at the top of the archive
	 */
	private ReleaseAsset(String name, AssetKind kind, String harness, String platform, long size, String sha256,
		String topDirectory)
	{
		this.name = name;
		this.kind = kind;
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
	 * Returns what the archive installs.
	 *
	 * @return the kind
	 */
	public AssetKind kind()
	{
		return kind;
	}

	/**
	 * Returns the harness that a plugin archive is for.
	 *
	 * @return the harness identifier, or empty for the installer
	 */
	public Optional<String> harness()
	{
		return Optional.ofNullable(harness);
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
