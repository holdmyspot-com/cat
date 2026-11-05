package com.holdmyspot.cat.common.installer.trust;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException.Reason;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The declaration of one CAT release: its version, its tag, the commit that it was built from and the archives that it
 * publishes. The installer obtains a
 * manifest only from {@link ManifestVerifier}, after a trusted key verified the manifest's signature, so every value
 * that this class returns is one that the release's signer published.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ReleaseManifest
{
	/**
	 * The only product whose releases the installer installs.
	 */
	private static final String PRODUCT = "cat";
	/**
	 * The top-level fields of a manifest. The manifest is refused when it has another field.
	 */
	private static final Set<String> FIELDS = Set.of("format", "product", "version", "tag", "build", "published",
		"assets");
	private final ReleaseVersion version;
	private final PluginVersion pluginVersion;
	private final Instant published;
	private final List<ReleaseAsset> assets;

	/**
	 * Parses a manifest whose signature a trusted key verified.
	 *
	 * @param bytes the manifest's exact bytes
	 * @return the manifest
	 * @throws TrustException if the manifest breaks a rule of its format
	 */
	static ReleaseManifest parse(byte[] bytes) throws TrustException
	{
		TrustJson json = new TrustJson(Reason.MALFORMED_MANIFEST, "release manifest");
		JsonNode root = json.parseObject(bytes);
		json.requireOnly(root, "", FIELDS);
		json.requireFormat(root);

		String product = json.requiredString(root, "", "product");
		if (!product.equals(PRODUCT))
		{
			throw json.refusal("The release manifest's field \"product\" must be \"" + PRODUCT + "\": \"" +
				TrustText.excerpt(product) + "\"");
		}
		ReleaseVersion version;
		try
		{
			version = ReleaseVersion.parse(json.requiredString(root, "", "version"));
		}
		catch (TrustException e)
		{
			if (e.reason() != Reason.MALFORMED_VERSION)
				throw e;
			throw json.refusal("The release manifest's field \"version\" is invalid. " + e.getMessage());
		}
		String tag = json.requiredString(root, "", "tag");
		if (!tag.equals(version.tag()))
		{
			throw json.refusal("The release manifest's field \"tag\" must be \"" + version.tag() + "\", which is " +
				"\"v\" followed by its version: \"" + TrustText.excerpt(tag) + "\"");
		}
		PluginVersion pluginVersion = parseBuild(json, version, json.requiredString(root, "", "build"));
		Instant published = parsePublished(json, json.requiredString(root, "", "published"));
		List<ReleaseAsset> assets = parseAssets(json, json.requiredArray(root, "", "assets"), version);
		return new ReleaseManifest(version, pluginVersion, published, assets);
	}

	/**
	 * Reads the commit that a release was built from.
	 *
	 * @param json    the manifest's field reader
	 * @param version the release's version
	 * @param value   the value of the {@code build} field
	 * @return the plugin version of the release's plugins
	 * @throws TrustException if the value is not twelve lower-case hexadecimal digits
	 */
	private static PluginVersion parseBuild(TrustJson json, ReleaseVersion version, String value)
		throws TrustException
	{
		try
		{
			return new PluginVersion(version.toString(), value);
		}
		catch (IllegalArgumentException _)
		{
			throw json.refusal("The release manifest's field \"build\" must be the first twelve lower-case " +
				"hexadecimal digits of the release commit, such as 0123456789ab: \"" + TrustText.excerpt(value) + "\"");
		}
	}

	/**
	 * Reads the time at which a release was published.
	 *
	 * @param json  the manifest's field reader
	 * @param value the value of the {@code published} field
	 * @return the time
	 * @throws TrustException if the value is not an ISO-8601 instant
	 */
	private static Instant parsePublished(TrustJson json, String value) throws TrustException
	{
		try
		{
			return Instant.parse(value);
		}
		catch (DateTimeParseException _)
		{
			throw json.refusal("The release manifest's field \"published\" must be an ISO-8601 instant, such as " +
				"2026-11-03T13:44:35Z: \"" + TrustText.excerpt(value) + "\"");
		}
	}

	/**
	 * Reads a manifest's assets.
	 *
	 * @param json    the manifest's field reader
	 * @param array   the value of the {@code assets} field
	 * @param version the release's version
	 * @return the assets, in the manifest's order
	 * @throws TrustException if the array is empty, an entry breaks a rule of the format, or two entries have the same
	 *                        name
	 */
	private static List<ReleaseAsset> parseAssets(TrustJson json, JsonNode array, ReleaseVersion version)
		throws TrustException
	{
		if (array.isEmpty())
			throw json.refusal("The release manifest's field \"assets\" must list at least one asset");
		List<ReleaseAsset> assets = new ArrayList<>();
		// An asset's name is determined by its harness and platform, so unique names also make those pairs unique,
		// which lets findPlugin() return at most one asset.
		Set<String> names = new HashSet<>();
		for (int index = 0; index < array.size(); ++index)
		{
			String path = "assets[" + index + "]";
			ReleaseAsset asset = ReleaseAsset.parse(array.get(index), path, json, version);
			if (!names.add(asset.name()))
			{
				throw json.refusal("The release manifest lists the asset \"" + asset.name() + "\" more than once, " +
					"again in " + path);
			}
			assets.add(asset);
		}
		return assets;
	}

	/**
	 * Creates a manifest.
	 *
	 * @param version       the release's version
	 * @param pluginVersion the plugin version of the release's plugins
	 * @param published     the time at which the release was published
	 * @param assets        the release's archives
	 */
	private ReleaseManifest(ReleaseVersion version, PluginVersion pluginVersion, Instant published,
		List<ReleaseAsset> assets)
	{
		this.version = version;
		this.pluginVersion = pluginVersion;
		this.published = published;
		this.assets = List.copyOf(assets);
	}

	/**
	 * Returns the release's version.
	 *
	 * @return the version
	 */
	public ReleaseVersion version()
	{
		return version;
	}

	/**
	 * Returns the release's tag.
	 *
	 * @return {@code v} followed by the version
	 */
	public String tag()
	{
		return version.tag();
	}

	/**
	 * Returns the plugin version of the release's plugins: the release's version and the commit that it was built
	 * from. Two publications of the same release that were built from different commits have different plugin versions.
	 *
	 * @return the plugin version
	 */
	public PluginVersion pluginVersion()
	{
		return pluginVersion;
	}

	/**
	 * Returns the time at which the release was published.
	 *
	 * @return the time
	 */
	public Instant published()
	{
		return published;
	}

	/**
	 * Returns the release's archives.
	 *
	 * @return an unmodifiable list in the manifest's order
	 */
	public List<ReleaseAsset> assets()
	{
		return assets;
	}

	/**
	 * Finds the plugin archive of a harness for a platform.
	 *
	 * @param harness  the harness's identifier
	 * @param platform the platform, such as {@code linux-x64}
	 * @return the archive, or empty when the release publishes none for that harness and platform
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Optional<ReleaseAsset> findPlugin(String harness, String platform)
	{
		requireThat(harness, "harness").isNotNull();
		requireThat(platform, "platform").isNotNull();
		for (ReleaseAsset asset : assets)
		{
			if (asset.harness().equals(harness) && asset.platform().equals(platform))
				return Optional.of(asset);
		}
		return Optional.empty();
	}

	@Override
	public String toString()
	{
		return version.tag();
	}
}
