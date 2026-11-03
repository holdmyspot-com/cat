package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes the list of the release archives built for one platform, which the release workflow merges with the other
 * platforms' lists into the release's signed manifest.
 */
public final class ReleaseAssetsFragment
{
	private final JsonMapper json;

	/**
	 * Creates a writer.
	 *
	 * @param scope the process scope, whose JSON mapper writes the list
	 * @throws NullPointerException if {@code scope} is null
	 */
	public ReleaseAssetsFragment(JvmScope scope)
	{
		requireThat(scope, "scope").isNotNull();
		this.json = scope.json();
	}

	/**
	 * Replaces the platform's list in a release directory with a JSON array that holds one object per asset, in the
	 * given order, with the fields {@code name}, {@code kind}, {@code harness} (only for a plugin), {@code platform},
	 * {@code size}, {@code sha256} and {@code top_directory}.
	 *
	 * @param platform         the platform that every asset runs on
	 * @param assets           the archives built for the platform
	 * @param releaseDirectory the directory that receives {@code cat-release-assets-<platform>.json}
	 * @return the written file
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code assets} is empty or holds an asset of another platform
	 * @throws IOException              if the file cannot be written
	 */
	public Path write(Platform platform, List<ReleaseAsset> assets, Path releaseDirectory) throws IOException
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(assets, "assets").isNotEmpty();
		requireThat(releaseDirectory, "releaseDirectory").isNotNull();
		for (ReleaseAsset asset : assets)
			requireThat(asset.platform(), "asset.platform()").isEqualTo(platform);

		ArrayNode entries = json.createArrayNode();
		for (ReleaseAsset asset : assets)
		{
			ObjectNode entry = entries.addObject();
			entry.put("name", asset.name());
			entry.put("kind", asset.kind().id());
			asset.harness().ifPresent(harness -> entry.put("harness", harness));
			entry.put("platform", platform.id());
			entry.put("size", asset.size());
			entry.put("sha256", asset.sha256());
			entry.put("top_directory", asset.topDirectory());
		}
		Files.createDirectories(releaseDirectory);
		Path fragment = releaseDirectory.resolve("cat-release-assets-" + platform.id() + ".json");
		Files.writeString(fragment, json.writerWithDefaultPrettyPrinter().writeValueAsString(entries) + "\n");
		return fragment;
	}
}
