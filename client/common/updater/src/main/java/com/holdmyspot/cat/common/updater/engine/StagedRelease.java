package com.holdmyspot.cat.common.updater.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import com.holdmyspot.cat.common.updater.trust.TrustException.Reason;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
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
	 * Checks that an extracted top directory contains the selected release and only internal symbolic links.
	 * Plugin manifests are limited to one MiB and client/VERSION to 128 bytes, with at most one trailing line ending.
	 * Windows releases require a regular bin/plugin-info.cmd file; POSIX releases require a regular executable
	 * bin/plugin-info file.
	 *
	 * @param tree         extracted top directory
	 * @param version      authenticated release version, spelled as two or three canonical decimal components
	 * @param manifestPath provider-supplied relative path to its plugin manifest
	 * @param platform     release platform, such as linux-x64 or windows-x64
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if any of {@code manifestPath} or {@code platform} are blank
	 * @throws TrustException           if {@code version} is malformed, the tree lacks matching versions or a regular
	 *                                  platform launcher file, or contains an escaping link or invalid manifest
	 * @throws IOException              if a staged file cannot be read
	 */
	public static void validate(Path tree, String version, String manifestPath, String platform)
		throws IOException, TrustException
	{
		requireThat(tree, "tree").isNotNull();
		requireThat(manifestPath, "manifestPath").isNotBlank();
		requireThat(version, "version").isNotNull();
		requireThat(platform, "platform").isNotBlank();
		ReleaseVersion.parse(version);
		if (!Files.isDirectory(tree, NOFOLLOW_LINKS))
			throw refusal("The extracted release top directory is missing or is a symbolic link: " + tree);
		Path root = tree.toRealPath();
		checkLinks(root);
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
			throw refusal("The staged plugin manifest version must equal release " + version + ": " + manifest);
		Path runtimeVersion = root.resolve("client/VERSION");
		if (!Files.isRegularFile(runtimeVersion) || !readVersion(runtimeVersion).equals(version))
			throw refusal("The staged client/VERSION must equal release " + version);
		boolean windows = platform.equals("windows-x64");
		String launcher = "bin/plugin-info";
		if (windows)
			launcher = "bin/plugin-info.cmd";
		Path pluginInfo = root.resolve(launcher);
		if (!Files.isRegularFile(pluginInfo, NOFOLLOW_LINKS))
			throw refusal("The staged launcher must be a regular file: " + root.relativize(pluginInfo));
		if (windows)
			return;
		if (!Files.isExecutable(pluginInfo))
			throw refusal("The staged bin/plugin-info must be executable on POSIX platforms");
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
		requireThat(tree, "tree").isNotNull();
		requireThat(migrationVersionFile, "migrationVersionFile").isNotNull();
		requireThat(activeVersion, "activeVersion").isNotNull();
		requireThat(newVersion, "newVersion").isNotNull();
		ReleaseVersion active = ReleaseVersion.parse(activeVersion);
		ReleaseVersion version = ReleaseVersion.parse(newVersion);
		Path registry = tree.resolve("install/migrations/registry.tsv");
		if (!Files.isRegularFile(registry))
			throw refusal("The staged release lacks install/migrations/registry.tsv; use explicit installation");
		ReleaseVersion recorded = active;
		if (Files.exists(migrationVersionFile))
			recorded = ReleaseVersion.parse(readVersion(migrationVersionFile));
		try (BufferedReader reader = Files.newBufferedReader(registry, UTF_8))
		{
			for (String line = readRegistryLine(reader); line != null; line = readRegistryLine(reader))
			{
				if (line.isBlank() || line.startsWith("#"))
					continue;
				String[] fields = line.split("\t", -1);
				if (fields.length != 2 || fields[1].isBlank())
					throw refusal("A migration registry line must contain a version, TAB, and script name");
				ReleaseVersion target = ReleaseVersion.parse(fields[0]);
				boolean pending = target.compareTo(recorded) > 0 && target.compareTo(version) <= 0;
				if (pending)
				{
					throw refusal("Release " + version + " requires data migration " + target +
						"; run the release's explicit installation command instead of routine update");
				}
			}
		}
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
