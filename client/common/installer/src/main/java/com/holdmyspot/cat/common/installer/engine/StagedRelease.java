package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustException.Reason;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks a staged plugin's release identity and refuses updates that require installer migrations. It never executes
 * staged files. The caller supplies an extracted tree that it exclusively owns until activation finishes.
 */
public final class StagedRelease
{
	/**
	 * Caps a plugin manifest at one MiB, permitting whole-document JSON validation with bounded memory.
	 */
	private static final int MANIFEST_LIMIT = 1024 * 1024;
	/**
	 * Parses provider-owned plugin manifests without permitting duplicate keys to hide the release version.
	 */
	private static final JsonMapper MAPPER = JsonMapper.builder().
		enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).
		enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

	/**
	 * Checks that an extracted top directory of a signed release contains the plugin version that the release names,
	 * and only internal symbolic links. Plugin manifests are limited to one MiB and bin/VERSION to 128 bytes, with at
	 * most one trailing line ending. Windows releases require a regular bin/plugin-info.cmd file; POSIX releases
	 * require a regular executable bin/plugin-info file.
	 *
	 * @param tree         extracted top directory
	 * @param version      the authenticated plugin version that the signed release manifest names, which the plugin
	 *                     manifest and bin/VERSION must state
	 * @param manifestPath provider-supplied relative path to its plugin manifest
	 * @param platform     release platform, such as linux-x64 or windows-x64
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if any of {@code manifestPath} or {@code platform} are blank
	 * @throws TrustException           if the tree lacks matching versions or a regular platform launcher file, or
	 *                                  contains an escaping link or invalid manifest
	 * @throws IOException              if a staged file cannot be read
	 */
	public static void validate(Path tree, PluginVersion version, String manifestPath, String platform)
		throws IOException, TrustException
	{
		requireThat(version, "version").isNotNull();
		validate(tree, version.release(), version, manifestPath, platform);
	}

	/**
	 * Checks that a local plugin tree, which no signed release describes, contains a plugin of the selected release and
	 * only internal symbolic links. Its plugin manifest and bin/VERSION must state the same version: the release, or a
	 * full plugin version of the release whatever its build. The limits and launcher checks of
	 * {@link #validate(Path, PluginVersion, String, String)} apply.
	 *
	 * @param tree         the local plugin tree
	 * @param version      the selected release version, spelled as two or three canonical decimal components
	 * @param manifestPath provider-supplied relative path to its plugin manifest
	 * @param platform     release platform, such as linux-x64 or windows-x64
	 * @return the version that the tree states: {@code version}, or a full plugin version of it
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if any of {@code manifestPath} or {@code platform} are blank
	 * @throws TrustException           if {@code version} is malformed, the tree lacks matching versions or a regular
	 *                                  platform launcher file, or contains an escaping link or invalid manifest
	 * @throws IOException              if a staged file cannot be read
	 */
	public static String validate(Path tree, String version, String manifestPath, String platform)
		throws IOException, TrustException
	{
		requireThat(version, "version").isNotNull();
		return validate(tree, version, null, manifestPath, platform);
	}

	/**
	 * Checks a staged plugin tree.
	 *
	 * @param tree         the tree
	 * @param release      the release version
	 * @param exact        the plugin version that the tree must state, or null to accept the release or any full
	 *                     plugin version of it, provided that the manifest and bin/VERSION agree
	 * @param manifestPath provider-supplied relative path to its plugin manifest
	 * @param platform     release platform, such as linux-x64 or windows-x64
	 * @return the version that the tree states
	 * @throws TrustException if the tree is refused
	 * @throws IOException    if a staged file cannot be read
	 */
	private static String validate(Path tree, String release, PluginVersion exact, String manifestPath,
		String platform) throws IOException, TrustException
	{
		requireThat(tree, "tree").isNotNull();
		requireThat(manifestPath, "manifestPath").isNotBlank();
		requireThat(platform, "platform").isNotBlank();
		ReleaseVersion.parse(release);
		if (!Files.isDirectory(tree, NOFOLLOW_LINKS))
			throw refusal("The extracted release top directory is missing or is a symbolic link: " + tree);
		Path root = tree.toRealPath();
		checkLinks(root);
		Path runtimeVersion = root.resolve(PluginDirectory.VERSION);
		String version;
		String required;
		if (exact != null)
		{
			version = exact.toString();
			required = "plugin version " + version;
		}
		else
		{
			version = localVersion(runtimeVersion, release);
			required = "release " + release;
		}
		Path manifest = root.resolve(manifestPath).normalize();
		if (!manifest.startsWith(root) || !Files.isRegularFile(manifest))
			throw refusal("The staged plugin manifest is missing or outside the release: " + manifestPath);
		JsonNode parsed;
		try
		{
			parsed = MAPPER.readTree(readBounded(manifest, MANIFEST_LIMIT));
		}
		catch (JacksonException e)
		{
			TrustException failure = refusal("The staged plugin manifest is not valid JSON: " + manifest);
			failure.initCause(e);
			throw failure;
		}
		if (parsed == null || !parsed.isObject())
			throw refusal("The staged plugin manifest must contain a JSON object: " + manifest);
		JsonNode declared = parsed.get("version");
		if (declared == null || !declared.isString() || !declared.stringValue().equals(version))
			throw refusal("The staged plugin manifest version must equal " + required + ": " + manifest);
		if (!Files.isRegularFile(runtimeVersion) || !readVersion(runtimeVersion).equals(version))
			throw refusal("The staged " + PortablePaths.text(PluginDirectory.VERSION) + " must equal " + required);
		boolean windows = platform.equals("windows-x64");
		String launcher = "bin/plugin-info";
		if (windows)
			launcher = "bin/plugin-info.cmd";
		Path pluginInfo = root.resolve(launcher);
		if (!Files.isRegularFile(pluginInfo, NOFOLLOW_LINKS))
			throw refusal("The staged launcher must be a regular file: " + root.relativize(pluginInfo));
		if (!windows && !Files.isExecutable(pluginInfo))
			throw refusal("The staged bin/plugin-info must be executable on POSIX platforms");
		return version;
	}

	/**
	 * Refuses registered migrations newer than the recorded migration version and no newer than this release.
	 * An absent recorded migration version uses the active release version. Comments and blank registry lines
	 * are skipped.
	 * Each registry line is limited to 4096 characters and the recorded version to 128 bytes.
	 *
	 * @param tree                 validated staged top directory
	 * @param migrationVersionFile installation's recorded migration version file
	 * @param activeVersion        active release version, used when the recorded version file is absent
	 * @param newVersion           authenticated release version
	 * @throws NullPointerException if any argument is null
	 * @throws TrustException       if the registry is absent or malformed, any release version is malformed, or a
	 *                              pending migration requires explicit installation
	 * @throws IOException          if a migration file cannot be read
	 */
	public static void requireNoPendingMigration(Path tree, Path migrationVersionFile, String activeVersion,
		String newVersion) throws IOException, TrustException
	{
		ReleaseVersion recorded = recorded(tree, migrationVersionFile, activeVersion, newVersion);
		requireNoPendingMigration(tree, recorded, ReleaseVersion.parse(newVersion));
	}

	/**
	 * Returns the recorded migration version.
	 *
	 * @param tree                 validated staged top directory
	 * @param migrationVersionFile installation's recorded migration version file
	 * @param activeVersion        active release version, used when the recorded version file is absent
	 * @param newVersion           authenticated release version
	 * @return the recorded migration version
	 * @throws NullPointerException if any argument is null
	 * @throws TrustException       if a version is malformed
	 * @throws IOException          if the migration version file cannot be read
	 */
	private static ReleaseVersion recorded(Path tree, Path migrationVersionFile, String activeVersion,
		String newVersion) throws IOException, TrustException
	{
		requireThat(tree, "tree").isNotNull();
		requireThat(migrationVersionFile, "migrationVersionFile").isNotNull();
		requireThat(activeVersion, "activeVersion").isNotNull();
		requireThat(newVersion, "newVersion").isNotNull();
		ReleaseVersion active = ReleaseVersion.parse(activeVersion);
		ReleaseVersion.parse(newVersion);
		if (Files.exists(migrationVersionFile))
			return ReleaseVersion.parse(readVersion(migrationVersionFile));
		return active;
	}

	/**
	 * Refuses registered migrations newer than {@code recorded} and no newer than {@code version}.
	 *
	 * @param tree     validated staged top directory
	 * @param recorded the recorded migration version
	 * @param version  authenticated release version
	 * @throws TrustException if the registry is absent or malformed, or a pending migration requires explicit
	 *                        installation
	 * @throws IOException    if the registry cannot be read
	 */
	private static void requireNoPendingMigration(Path tree, ReleaseVersion recorded, ReleaseVersion version)
		throws IOException, TrustException
	{
		for (Migration migration : migrations(tree))
		{
			ReleaseVersion target = migration.version();
			if (target.compareTo(recorded) > 0 && target.compareTo(version) <= 0)
			{
				throw refusal("Release " + version + " requires data migration " + target +
					"; run the release's explicit installation command instead of routine update");
			}
		}
	}

	/**
	 * Returns the data migrations that a release registers after an older release, up to and including a newer one.
	 *
	 * @param tree  a validated plugin tree
	 * @param older the older release
	 * @param newer the newer release
	 * @return the migrations' script names, in registry order
	 * @throws NullPointerException if any of the arguments are null
	 * @throws TrustException       if the registry is absent or malformed
	 * @throws IOException          if the registry cannot be read
	 */
	public static List<String> migrationsAfter(Path tree, ReleaseVersion older, ReleaseVersion newer)
		throws IOException, TrustException
	{
		requireThat(tree, "tree").isNotNull();
		requireThat(older, "older").isNotNull();
		requireThat(newer, "newer").isNotNull();
		List<String> scripts = new ArrayList<>();
		for (Migration migration : migrations(tree))
		{
			ReleaseVersion target = migration.version();
			if (target.compareTo(older) > 0 && target.compareTo(newer) <= 0)
				scripts.add(migration.script());
		}
		return scripts;
	}

	/**
	 * Reads a release's migration registry. Comments and blank lines are skipped.
	 *
	 * @param tree a validated plugin tree
	 * @return the registered migrations, in registry order
	 * @throws TrustException if the registry is absent or malformed
	 * @throws IOException    if the registry cannot be read
	 */
	private static List<Migration> migrations(Path tree) throws IOException, TrustException
	{
		Path registry = tree.resolve("install/migrations/registry.tsv");
		if (!Files.isRegularFile(registry))
			throw refusal("The staged release lacks install/migrations/registry.tsv; use explicit installation");
		List<Migration> migrations = new ArrayList<>();
		try (BufferedReader reader = Files.newBufferedReader(registry, UTF_8))
		{
			for (String line = readRegistryLine(reader); line != null; line = readRegistryLine(reader))
			{
				if (line.isBlank() || line.startsWith("#"))
					continue;
				String[] fields = line.split("\t", -1);
				if (fields.length != 2 || fields[1].isBlank())
					throw refusal("A migration registry line must contain a version, TAB, and script name");
				migrations.add(new Migration(ReleaseVersion.parse(fields[0]), fields[1]));
			}
		}
		return migrations;
	}

	/**
	 * A data migration that a release's registry names.
	 *
	 * @param version the release whose data structures the migration produces
	 * @param script  the script's path relative to the registry's directory
	 */
	private record Migration(ReleaseVersion version, String script)
	{
	}

	/**
	 * Prevents instantiation.
	 */
	private StagedRelease()
	{
	}

	/**
	 * Checks every symbolic link without following linked directories during traversal.
	 *
	 * @param root real extracted top directory
	 * @throws TrustException if a symbolic link escapes or cannot resolve
	 * @throws IOException    if the tree cannot be traversed
	 */
	static void checkLinks(Path root) throws IOException, TrustException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			Iterator<Path> iterator = paths.iterator();
			while (iterator.hasNext())
			{
				Path path = iterator.next();
				if (!Files.isSymbolicLink(path))
					continue;
				Path target;
				try
				{
					target = path.toRealPath();
				}
				catch (IOException e)
				{
					TrustException failure = refusal("The staged symbolic link cannot resolve: " + path);
					failure.initCause(e);
					throw failure;
				}
				if (!target.startsWith(root))
					throw refusal("The staged symbolic link resolves outside the release: " + path);
			}
		}
	}

	/**
	 * Reads a small metadata file, detecting excess bytes even if it grows during the read.
	 *
	 * @param file  metadata file
	 * @param limit maximum byte count
	 * @return metadata bytes
	 * @throws TrustException if the file exceeds the limit
	 * @throws IOException    if the file cannot be read
	 */
	private static byte[] readBounded(Path file, int limit) throws IOException, TrustException
	{
		try (InputStream input = Files.newInputStream(file))
		{
			byte[] bytes = input.readNBytes(limit + 1);
			if (bytes.length > limit)
				throw refusal("The staged metadata exceeds " + limit + " bytes: " + file);
			return bytes;
		}
	}

	/**
	 * Returns the version that a local plugin tree must state: the full plugin version that its bin/VERSION states
	 * when that is a build of the release, otherwise the release.
	 *
	 * @param runtimeVersion the tree's bin/VERSION
	 * @param release        the release version
	 * @return the version
	 * @throws TrustException if bin/VERSION exceeds its size limit
	 * @throws IOException    if bin/VERSION cannot be read
	 */
	private static String localVersion(Path runtimeVersion, String release) throws IOException, TrustException
	{
		if (!Files.isRegularFile(runtimeVersion))
			return release;
		String declared = readVersion(runtimeVersion);
		try
		{
			if (PluginVersion.parse(declared).release().equals(release))
				return declared;
		}
		catch (IllegalArgumentException _)
		{
			// A tree that states no full plugin version must state the release
		}
		return release;
	}

	/**
	 * Reads a version with at most one trailing line ending and at most 128 bytes.
	 *
	 * @param file version file
	 * @return exact version spelling without the trailing line ending
	 * @throws TrustException if the file exceeds 128 bytes
	 * @throws IOException    if the file cannot be read
	 */
	private static String readVersion(Path file) throws IOException, TrustException
	{
		String text = new String(readBounded(file, 128), UTF_8);
		if (text.endsWith("\r\n"))
			return text.substring(0, text.length() - 2);
		if (text.endsWith("\n"))
			return text.substring(0, text.length() - 1);
		return text;
	}

	/**
	 * Reads one registry record using at most 4096 characters, independently of the registry's total size.
	 *
	 * @param reader registry reader
	 * @return next record, or null at end of input
	 * @throws TrustException if a record exceeds 4096 characters
	 * @throws IOException    if the registry cannot be read
	 */
	private static String readRegistryLine(BufferedReader reader) throws IOException, TrustException
	{
		StringBuilder line = new StringBuilder();
		for (int character = reader.read(); character != -1; character = reader.read())
		{
			if (character == '\n')
				return line.toString();
			if (line.length() == 4096)
				throw refusal("A migration registry line exceeds 4096 characters");
			line.append((char) character);
		}
		if (line.isEmpty())
			return null;
		return line.toString();
	}

	/**
	 * Creates a staged release refusal.
	 *
	 * @param message failed staged release requirement
	 * @return refusal
	 */
	private static TrustException refusal(String message)
	{
		return new TrustException(Reason.MALFORMED_MANIFEST, message);
	}
}
