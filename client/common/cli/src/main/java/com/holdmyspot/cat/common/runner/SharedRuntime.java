package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.PortablePaths;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Reads the shared Java runtime that CAT's plugins and updater run: its manifest, which identifies it, and its
 * description, which tells whether it satisfies an application's runtime requirement.
 *
 * <p>
 * The runtime directory holds {@code cat-runtime.json}, its description
 * {@code {"format": 1, "java_version": "...", "java_feature": N, "modules": [...]}}, and {@code cat-runtime.manifest},
 * whose first line is {@code CAT runtime manifest v1} and whose other lines are {@code <sha256>\t<path>}, one for each
 * regular file below the runtime except the manifest itself, sorted by their {@code /}-separated relative path. The
 * runtime holds no symbolic links. A requirement
 * {@code {"format": 1, "java_feature": N, "modules": [...]}} is satisfied by a runtime whose feature release is at
 * least {@code java_feature} and whose modules include every listed module.
 */
public final class SharedRuntime
{
	/**
	 * The runtime manifest's location, relative to the runtime directory.
	 */
	public static final Path MANIFEST = Path.of("cat-runtime.manifest");
	/**
	 * The runtime description's location, relative to the runtime directory.
	 */
	public static final Path DESCRIPTION = Path.of("cat-runtime.json");
	private static final String MANIFEST_HEADER = "CAT runtime manifest v1";
	/**
	 * The largest runtime manifest that CAT reads. A runtime image holds a few hundred files, so each manifest line
	 * fits easily within this bound.
	 */
	private static final int MAXIMUM_MANIFEST_BYTES = 16_777_216;
	/**
	 * The largest runtime description or requirement that CAT reads.
	 */
	private static final int MAXIMUM_DESCRIPTION_BYTES = 1_048_576;
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Writes the manifest of a complete runtime directory.
	 *
	 * @param runtime the runtime directory
	 * @throws NullPointerException if {@code runtime} is null
	 * @throws IOException          if the manifest already exists, the runtime holds a symbolic link, or a file cannot
	 *                              be listed or hashed
	 */
	public static void writeManifest(Path runtime) throws IOException
	{
		requireThat(runtime, "runtime").isNotNull();
		Path manifest = runtime.resolve(MANIFEST);
		if (Files.exists(manifest, NOFOLLOW_LINKS))
			throw new IOException("The runtime manifest already exists: " + manifest);
		List<String> lines = new ArrayList<>();
		lines.add(MANIFEST_HEADER);
		for (Path file : files(runtime))
			lines.add(sha256(file) + "\t" + PortablePaths.text(runtime.relativize(file)));
		Files.write(manifest, lines, UTF_8);
	}

	/**
	 * Returns the identity of a runtime: the SHA-256 digest of its manifest, after verifying that the manifest lists
	 * exactly the runtime's files with their current content.
	 *
	 * @param runtime the runtime directory
	 * @return the manifest's SHA-256 digest in lower-case hexadecimal
	 * @throws NullPointerException if {@code runtime} is null
	 * @throws IOException          if the manifest is missing or malformed, or does not match the runtime's files. The
	 *                              message names the manifest file.
	 */
	public static String identity(Path runtime) throws IOException
	{
		requireThat(runtime, "runtime").isNotNull();
		Path manifest = runtime.resolve(MANIFEST);
		if (!Files.isRegularFile(manifest, NOFOLLOW_LINKS))
		{
			throw new IOException("The Java runtime has no manifest " + manifest + ". Rerun the CAT entry command to " +
				"reinstall the shared Java runtime.");
		}
		byte[] bytes;
		try (InputStream in = Files.newInputStream(manifest))
		{
			bytes = BoundedInput.readBytes(in, manifest, MAXIMUM_MANIFEST_BYTES, "Runtime manifest");
		}
		Iterator<String> lines = new String(bytes, UTF_8).lines().iterator();
		if (!lines.hasNext() || !lines.next().equals(MANIFEST_HEADER))
			throw new IOException("The runtime manifest " + manifest + " has an unsupported header.");
		for (Path file : files(runtime))
		{
			String expected = sha256(file) + "\t" + PortablePaths.text(runtime.relativize(file));
			if (!lines.hasNext() || !lines.next().equals(expected))
				throw mismatch(manifest);
		}
		if (lines.hasNext())
			throw mismatch(manifest);
		return sha256(bytes);
	}

	/**
	 * Returns the failure that a manifest which does not list the runtime's current files reports.
	 *
	 * @param manifest the runtime manifest
	 * @return the failure
	 */
	private static IOException mismatch(Path manifest)
	{
		return new IOException("The runtime manifest " + manifest + " does not match the files of its Java " +
			"runtime. Rerun the CAT entry command to reinstall the shared Java runtime.");
	}

	/**
	 * Verifies that a runtime satisfies an application's runtime requirement.
	 *
	 * @param runtime     the runtime directory, which holds the runtime description
	 * @param requirement the application's runtime requirement file
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the runtime description or the requirement is missing or malformed, or the
	 *                              runtime's feature release is lower than required or it lacks a required module. The
	 *                              message names the file or the unmet requirement.
	 */
	public static void requireSatisfies(Path runtime, Path requirement) throws IOException
	{
		requireThat(runtime, "runtime").isNotNull();
		requireThat(requirement, "requirement").isNotNull();
		Path description = runtime.resolve(DESCRIPTION);
		JsonNode runtimeJson = readObject(description, "The Java runtime " + runtime + " has no description " +
			description + ", so it is not a CAT runtime.", Set.of("format", "java_version", "java_feature", "modules"));
		JsonNode requirementJson = readObject(requirement, "The plugin has no runtime requirement " + requirement +
			".", Set.of("format", "java_feature", "modules"));
		JsonNode javaVersion = runtimeJson.get("java_version");
		if (javaVersion == null || !javaVersion.isString() || javaVersion.asString().isBlank())
			throw new IOException("The runtime description " + description + " has no java_version string.");
		int runtimeFeature = feature(runtimeJson, description);
		int requiredFeature = feature(requirementJson, requirement);
		Set<String> requiredModules = modules(requirementJson, requirement);
		Set<String> missingModules = new TreeSet<>(requiredModules);
		missingModules.removeAll(modules(runtimeJson, description));

		if (runtimeFeature < requiredFeature || !missingModules.isEmpty())
		{
			throw new IOException("The Java runtime " + runtime + " (Java " + runtimeFeature + ") does not satisfy " +
				requirement + ", which requires Java " + requiredFeature + " or later with the modules " +
				requiredModules + "; missing modules: " + missingModules + ". Rerun the entry command of a newer CAT " +
				"release to install a runtime that satisfies it.");
		}
	}

	/**
	 * Reads a format 1 JSON object.
	 *
	 * @param file    the JSON file
	 * @param missing the message of the failure when the file is missing
	 * @param fields  the fields that the object may hold
	 * @return the object
	 * @throws IOException if the file is missing, is not a format 1 JSON object, or holds another field
	 */
	private static JsonNode readObject(Path file, String missing, Set<String> fields) throws IOException
	{
		if (!Files.isRegularFile(file))
			throw new IOException(missing);
		JsonNode root;
		try
		{
			root = JSON.readTree(BoundedInput.readUtf8(file, MAXIMUM_DESCRIPTION_BYTES, "Runtime description"));
		}
		catch (JacksonException e)
		{
			throw new IOException(file + " is not valid JSON: " + e.getOriginalMessage(), e);
		}
		if (!root.isObject())
			throw new IOException(file + " is not a JSON object.");
		for (String name : root.propertyNames())
		{
			if (!fields.contains(name))
				throw new IOException(file + " holds the unsupported field " + name + ".");
		}
		JsonNode format = root.get("format");
		if (format == null || !format.isInt() || format.intValue() != 1)
			throw new IOException(file + " does not have format 1.");
		return root;
	}

	/**
	 * Returns the Java feature release that a description or requirement states.
	 *
	 * @param root the JSON object
	 * @param file the file that holds it
	 * @return the positive feature release
	 * @throws IOException if {@code java_feature} is not a positive integer
	 */
	private static int feature(JsonNode root, Path file) throws IOException
	{
		JsonNode feature = root.get("java_feature");
		if (feature == null || !feature.isInt() || feature.intValue() < 1)
			throw new IOException(file + " has no positive integer java_feature.");
		return feature.intValue();
	}

	/**
	 * Returns the modules that a description or requirement lists.
	 *
	 * @param root the JSON object
	 * @param file the file that holds it
	 * @return a sorted modifiable set of the module names
	 * @throws IOException if {@code modules} is not an array of non-blank strings
	 */
	private static Set<String> modules(JsonNode root, Path file) throws IOException
	{
		JsonNode modules = root.get("modules");
		if (modules == null || !modules.isArray())
			throw new IOException(file + " has no modules array.");
		Set<String> result = new TreeSet<>();
		for (JsonNode module : modules)
		{
			if (!module.isString() || module.asString().isBlank())
				throw new IOException(file + " lists a module that is not a non-blank string: " + module);
			result.add(module.asString());
		}
		return result;
	}

	/**
	 * Lists a runtime's files that its manifest covers.
	 *
	 * @param runtime the runtime directory
	 * @return the regular files below the runtime except its manifest, sorted by their {@code /}-separated relative
	 *   path
	 * @throws IOException if the runtime is not a directory, holds a symbolic link, or cannot be listed
	 */
	private static List<Path> files(Path runtime) throws IOException
	{
		if (!Files.isDirectory(runtime, NOFOLLOW_LINKS))
			throw new IOException("The Java runtime directory does not exist: " + runtime);
		Path manifest = runtime.resolve(MANIFEST);
		List<Path> result = new ArrayList<>();
		try (Stream<Path> paths = Files.walk(runtime))
		{
			for (Path path : (Iterable<Path>) paths::iterator)
			{
				if (Files.isSymbolicLink(path))
					throw new IOException("The Java runtime holds the symbolic link " + path + ".");
				if (Files.isRegularFile(path, NOFOLLOW_LINKS) && !path.equals(manifest))
					result.add(path);
			}
		}
		result.sort((first, second) -> PortablePaths.text(runtime.relativize(first)).compareTo(
			PortablePaths.text(runtime.relativize(second))));
		return result;
	}

	/**
	 * Returns the SHA-256 digest of a file.
	 *
	 * @param file the file
	 * @return the digest in lower-case hexadecimal
	 * @throws IOException if the file cannot be read
	 */
	private static String sha256(Path file) throws IOException
	{
		MessageDigest digest = newDigest();
		try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest))
		{
			in.transferTo(OutputStream.nullOutputStream());
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Returns the SHA-256 digest of bytes.
	 *
	 * @param bytes the bytes
	 * @return the digest in lower-case hexadecimal
	 */
	private static String sha256(byte[] bytes)
	{
		return HexFormat.of().formatHex(newDigest().digest(bytes));
	}

	/**
	 * Returns a SHA-256 digest.
	 *
	 * @return the digest
	 */
	private static MessageDigest newDigest()
	{
		try
		{
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("Every Java implementation must provide SHA-256", e);
		}
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private SharedRuntime()
	{
	}
}
