package com.holdmyspot.cat.common.updater.cli.internal.test;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.updater.engine.test.Releases;
import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import com.holdmyspot.cat.common.updater.trust.TrustPolicy;
import com.holdmyspot.cat.common.updater.trust.TrustedKey;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Creates the extracted updater archive of release 1.2, from which setup runs, and a local plugin tree of the same
 * release.
 */
final class SetupArchive
{
	/**
	 * The release of the archive and the plugin tree.
	 */
	static final String VERSION = "1.2";
	/**
	 * The requirement that both the updater and the plugin tree declare, which the archive's runtime satisfies.
	 */
	private static final String REQUIREMENT = "{\"format\": 1, \"java_feature\": 25, \"modules\": [\"java.base\"]}";

	/**
	 * Prevents construction.
	 */
	private SetupArchive()
	{
	}

	/**
	 * Creates the extracted archive: a Java runtime and the updater's version directory.
	 *
	 * @param parent the directory that receives the archive
	 * @param keys   the release keys of the archive's default policy, empty for a development build
	 * @return the archive's top directory
	 * @throws IOException if the archive cannot be written
	 */
	static Path create(Path parent, List<TrustedKey> keys) throws IOException
	{
		Path archive = parent.resolve("cat-updater-v" + VERSION + "-linux-x64");
		Files.createDirectories(archive.resolve("runtime/bin"));
		Files.writeString(archive.resolve("runtime/bin/java"), "java");
		Releases.runtime(archive);
		Path app = Files.createDirectories(archive.resolve(VERSION + "/app"));
		Files.writeString(app.resolve("updater.jar"), "jar");
		Files.writeString(archive.resolve(VERSION + "/VERSION"), VERSION + "\n");
		Files.writeString(archive.resolve(VERSION + "/runtime-requirement.json"), REQUIREMENT);
		writeDefaultPolicy(archive, keys);
		return archive;
	}

	/**
	 * Replaces the archive's default policy.
	 *
	 * @param archive the archive's top directory
	 * @param keys    the release keys, empty for a development build
	 * @throws IOException if the policy cannot be written
	 */
	static void writeDefaultPolicy(Path archive, List<TrustedKey> keys) throws IOException
	{
		try
		{
			TrustPolicy defaults = TrustPolicy.of(keys, URI.create("https://releases.example/cat/releases/download"),
				ReleaseVersion.parse("1.0"), 1, Map.of());
			Path file = archive.resolve(VERSION + "/conf/cat-updater/default-policy.json");
			Files.createDirectories(file.getParent());
			defaults.write(file, new FileReplacer(Platform.current(), () ->
			{
			}, Clock.systemUTC()));
		}
		catch (TrustException e)
		{
			throw new AssertionError(e);
		}
	}

	/**
	 * Creates a local plugin tree of the archive's release, as a developer build produces it.
	 *
	 * @param tree the directory to create
	 * @return the tree
	 * @throws IOException if the tree cannot be written
	 */
	static Path pluginTree(Path tree) throws IOException
	{
		Files.createDirectories(tree.resolve(".harness-a"));
		Files.writeString(tree.resolve(".harness-a/plugin.json"), "{\"version\":\"" + VERSION + "\"}");
		Files.createDirectories(tree.resolve("client"));
		Files.writeString(tree.resolve("client/VERSION"), VERSION);
		Files.writeString(tree.resolve("client/runtime-requirement.json"), REQUIREMENT);
		Files.createDirectories(tree.resolve("bin"));
		Files.writeString(tree.resolve("bin/plugin-info"), "local");
		Files.setPosixFilePermissions(tree.resolve("bin/plugin-info"), PosixFilePermissions.fromString("rwxr-xr-x"));
		Files.createDirectories(tree.resolve("install/migrations"));
		Files.writeString(tree.resolve("install/migrations/registry.tsv"), "# No migration\n");
		Files.writeString(tree.resolve("version"), VERSION);
		return tree;
	}
}
