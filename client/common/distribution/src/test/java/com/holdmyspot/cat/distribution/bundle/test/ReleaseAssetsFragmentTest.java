package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.distribution.bundle.ReleaseAsset;
import com.holdmyspot.cat.distribution.bundle.ReleaseAssetsFragment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link ReleaseAssetsFragment}.
 */
public final class ReleaseAssetsFragmentTest
{
	/** Creates a test instance. */
	public ReleaseAssetsFragmentTest()
	{
	}

	/**
	 * Verifies that the platform's list describes each plugin archive by its name, kind, harness, platform, size,
	 * SHA-256 digest and top directory, in the order given.
	 */
	@Test
	public void describesEachPluginArchive() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-release-assets-"));
			Path first = Files.writeString(temporary.resolve("cat-harness-a-v1.0-linux-x64.tar.gz"), "abc");
			Path second = Files.writeString(temporary.resolve("cat-harness-b-v1.0-linux-x64.tar.gz"), "archive b");

			Path fragment = new ReleaseAssetsFragment(scope).write(Platform.LINUX_X64,
				List.of(ReleaseAsset.plugin("harness-a", Platform.LINUX_X64, first),
					ReleaseAsset.plugin("harness-b", Platform.LINUX_X64, second)), temporary.resolve("release"));

			requireThat(fragment, "fragment").isEqualTo(temporary.resolve("release/cat-release-assets-linux-x64.json"));
			JsonNode entries = JsonMapper.builder().build().readTree(Files.readString(fragment));
			requireThat(entries.size(), "entries.size()").isEqualTo(2);
			JsonNode entry = entries.get(0);
			requireThat(entry.propertyNames(), "propertyNames").containsExactly(List.of("name", "kind", "harness",
				"platform", "size", "sha256", "top_directory"));
			requireThat(entry.get("name").asString(), "name").isEqualTo("cat-harness-a-v1.0-linux-x64.tar.gz");
			requireThat(entry.get("kind").asString(), "kind").isEqualTo("plugin");
			requireThat(entry.get("harness").asString(), "harness").isEqualTo("harness-a");
			requireThat(entry.get("platform").asString(), "platform").isEqualTo("linux-x64");
			requireThat(entry.get("size").asLong(), "size").isEqualTo(3L);
			// The SHA-256 digest of "abc"
			requireThat(entry.get("sha256").asString(), "sha256").
				isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
			requireThat(entry.get("top_directory").asString(), "top_directory").isEqualTo("cat-harness-a-v1.0-linux-x64");
			requireThat(entries.get(1).get("name").asString(), "secondName").
				isEqualTo("cat-harness-b-v1.0-linux-x64.tar.gz");
			requireThat(entries.get(1).get("size").asLong(), "secondSize").isEqualTo(9L);
		}
	}

	/**
	 * Verifies that the updater's archive, which serves every harness, is listed with the kind {@code updater} and
	 * without a harness.
	 */
	@Test
	public void describesUpdaterArchiveWithoutHarness() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-release-assets-"));
			Path updater = Files.writeString(temporary.resolve("cat-updater-v1.0-macos-arm64.tar.gz"), "updater");

			Path fragment = new ReleaseAssetsFragment(scope).write(Platform.MACOS_ARM64,
				List.of(ReleaseAsset.updater(Platform.MACOS_ARM64, updater)), temporary);

			JsonNode entry = JsonMapper.builder().build().readTree(Files.readString(fragment)).get(0);
			requireThat(entry.propertyNames(), "propertyNames").containsExactly(List.of("name", "kind", "platform",
				"size", "sha256", "top_directory"));
			requireThat(entry.get("kind").asString(), "kind").isEqualTo("updater");
			requireThat(entry.get("platform").asString(), "platform").isEqualTo("macos-arm64");
			requireThat(entry.get("top_directory").asString(), "top_directory").isEqualTo("cat-updater-v1.0-macos-arm64");
		}
	}

	/**
	 * Verifies that an asset built for another platform is refused, so that no platform's list names another's
	 * archive.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void refusesAssetOfAnotherPlatform() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-release-assets-"));
			Path archive = Files.writeString(temporary.resolve("cat-harness-a-v1.0-linux-arm64.tar.gz"), "a");

			new ReleaseAssetsFragment(scope).write(Platform.LINUX_X64,
				List.of(ReleaseAsset.plugin("harness-a", Platform.LINUX_ARM64, archive)), temporary);
		}
	}
}
