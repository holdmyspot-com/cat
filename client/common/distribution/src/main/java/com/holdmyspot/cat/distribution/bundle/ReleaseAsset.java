package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One plugin archive that a CAT release publishes for a harness and a platform, as the release's signed manifest lists
 * it.
 *
 * @param name         the archive's file name, such as {@code cat-claude-linux-x64-v1.0.tar.gz}
 * @param harness      the harness whose plugin the archive holds
 * @param platform     the platform that the archive runs on
 * @param size         the archive's size in bytes
 * @param sha256       the lowercase hexadecimal SHA-256 digest of the archive
 * @param topDirectory the one directory at the top of the archive
 */
public record ReleaseAsset(String name, String harness, Platform platform, long size, String sha256,
	String topDirectory)
{
	private static final String ARCHIVE_SUFFIX = ".tar.gz";

	/**
	 * Creates an asset.
	 *
	 * @param name         the archive's file name, such as {@code cat-claude-linux-x64-v1.0.tar.gz}
	 * @param harness      the harness whose plugin the archive holds
	 * @param platform     the platform that the archive runs on
	 * @param size         the archive's size in bytes
	 * @param sha256       the lowercase hexadecimal SHA-256 digest of the archive
	 * @param topDirectory the one directory at the top of the archive
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code name} does not end in {@code .tar.gz}, {@code harness} is blank,
	 *                                  {@code size} is negative, {@code sha256} is not 64 lowercase hexadecimal digits
	 *                                  or {@code topDirectory} is blank
	 */
	public ReleaseAsset
	{
		requireThat(name, "name").endsWith(ARCHIVE_SUFFIX);
		requireThat(harness, "harness").isNotBlank();
		requireThat(platform, "platform").isNotNull();
		requireThat(size, "size").isNotNegative();
		requireThat(sha256, "sha256").matches("[0-9a-f]{64}");
		requireThat(topDirectory, "topDirectory").isNotBlank();
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
		requireThat(platform, "platform").isNotNull();
		requireThat(archive, "archive").isNotNull();
		String name = archive.getFileName().toString();
		requireThat(name, "name").endsWith(ARCHIVE_SUFFIX);
		return new ReleaseAsset(name, harness, platform, Files.size(archive), ReleasePackager.sha256(archive),
			name.substring(0, name.length() - ARCHIVE_SUFFIX.length()));
	}
}
