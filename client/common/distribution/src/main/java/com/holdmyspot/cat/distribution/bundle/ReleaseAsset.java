package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * One archive that a CAT release publishes for a platform, as the release's signed manifest lists it.
 *
 * @param name         the archive's file name, such as {@code cat-claude-linux-x64-v1.0.tar.gz}
 * @param kind         what the archive holds
 * @param harness      the harness whose plugin the archive holds, or empty for an archive that serves every harness
 * @param platform     the platform that the archive runs on
 * @param size         the archive's size in bytes
 * @param sha256       the lowercase hexadecimal SHA-256 digest of the archive
 * @param topDirectory the one directory at the top of the archive
 */
public record ReleaseAsset(String name, Kind kind, Optional<String> harness, Platform platform, long size,
	String sha256, String topDirectory)
{
	private static final String ARCHIVE_SUFFIX = ".tar.gz";

	/**
	 * Creates an asset.
	 *
	 * @param name         the archive's file name, such as {@code cat-claude-linux-x64-v1.0.tar.gz}
	 * @param kind         what the archive holds
	 * @param harness      the harness whose plugin the archive holds, or empty for an archive that serves every
	 *                     harness
	 * @param platform     the platform that the archive runs on
	 * @param size         the archive's size in bytes
	 * @param sha256       the lowercase hexadecimal SHA-256 digest of the archive
	 * @param topDirectory the one directory at the top of the archive
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code name} does not end in {@code .tar.gz}, {@code size} is negative,
	 *                                  {@code sha256} is not 64 lowercase hexadecimal digits, {@code topDirectory} is
	 *                                  blank, or a plugin has no harness or an installer has one
	 */
	public ReleaseAsset
	{
		requireThat(name, "name").endsWith(ARCHIVE_SUFFIX);
		requireThat(kind, "kind").isNotNull();
		requireThat(harness, "harness").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(size, "size").isNotNegative();
		requireThat(sha256, "sha256").matches("[0-9a-f]{64}");
		requireThat(topDirectory, "topDirectory").isNotBlank();
		requireThat(harness.isPresent(), "harness.isPresent()").isEqualTo(kind == Kind.PLUGIN);
	}

	/**
	 * Describes a harness's plugin archive.
	 *
	 * @param harness  the harness whose plugin the archive holds
	 * @param platform the platform that the archive runs on
	 * @param archive  the archive, whose file name without {@code .tar.gz} is its top directory
	 * @return the asset
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code harness} is blank or the archive's name does not end in
	 *                                  {@code .tar.gz}
	 * @throws IOException              if the archive cannot be read
	 */
	public static ReleaseAsset plugin(String harness, Platform platform, Path archive) throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		return of(Kind.PLUGIN, Optional.of(harness), platform, archive);
	}

	/**
	 * Describes the trusted installer's archive, which serves every harness.
	 *
	 * @param platform the platform that the archive runs on
	 * @param archive  the archive, whose file name without {@code .tar.gz} is its top directory
	 * @return the asset
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if the archive's name does not end in {@code .tar.gz}
	 * @throws IOException              if the archive cannot be read
	 */
	public static ReleaseAsset installer(Platform platform, Path archive) throws IOException
	{
		return of(Kind.INSTALLER, Optional.empty(), platform, archive);
	}

	/**
	 * Describes an archive from its file.
	 *
	 * @param kind     what the archive holds
	 * @param harness  the harness whose plugin the archive holds, or empty
	 * @param platform the platform that the archive runs on
	 * @param archive  the archive
	 * @return the asset
	 * @throws IOException if the archive cannot be read
	 */
	private static ReleaseAsset of(Kind kind, Optional<String> harness, Platform platform, Path archive)
		throws IOException
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(archive, "archive").isNotNull();
		String name = archive.getFileName().toString();
		requireThat(name, "name").endsWith(ARCHIVE_SUFFIX);
		return new ReleaseAsset(name, kind, harness, platform, Files.size(archive), ReleasePackager.sha256(archive),
			name.substring(0, name.length() - ARCHIVE_SUFFIX.length()));
	}

	/**
	 * What a release archive holds.
	 */
	public enum Kind
	{
		/**
		 * A harness's CAT plugin.
		 */
		PLUGIN("plugin"),
		/**
		 * The trusted installer.
		 */
		INSTALLER("updater");

		private final String id;

		/**
		 * Creates a kind.
		 *
		 * @param id the kind's name in the release manifest
		 */
		Kind(String id)
		{
			this.id = id;
		}

		/**
		 * Returns the kind's name in the release manifest.
		 *
		 * @return the name, such as {@code plugin}
		 */
		public String id()
		{
			return id;
		}
	}
}
