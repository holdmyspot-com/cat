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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Reads the Java runtime that one harness's CAT installation holds for its plugin and updater: its manifest, which
 * identifies it, and its description, which tells whether it satisfies an application's runtime requirement.
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
public final class InstalledRuntime
{
	/**
	 * The runtime manifest's location, relative to the runtime directory.
	 */
	public static final Path MANIFEST = Path.of("cat-runtime.manifest");
	/**
	 * The runtime description's location, relative to the runtime directory.
	 */
	public static final Path DESCRIPTION = RuntimeDescription.FILE;
	private static final String MANIFEST_HEADER = "CAT runtime manifest v1";
	/**
	 * The largest runtime manifest that CAT reads. A runtime image holds a few hundred files, so each manifest line
	 * fits easily within this bound.
	 */
	private static final int MAXIMUM_MANIFEST_BYTES = 16_777_216;

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
				"reinstall this harness's Java runtime.");
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
			"runtime. Rerun the CAT entry command to reinstall this harness's Java runtime.");
	}

	/**
	 * Verifies that a runtime satisfies an application's runtime requirement. Both files are read as strictly as the
	 * updater reads them before it activates a release.
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
		if (Files.notExists(description, NOFOLLOW_LINKS))
		{
			throw new IOException("The Java runtime " + runtime + " has no description " + description + ", so it is " +
				"not a CAT runtime.");
		}
		if (Files.notExists(requirement, NOFOLLOW_LINKS))
			throw new IOException("The plugin has no runtime requirement " + requirement + ".");
		RuntimeDescription runtimeDescription = RuntimeDescription.read(runtime);
		RuntimeRequirement required = RuntimeRequirement.read(requirement);
		if (!runtimeDescription.satisfies(required))
		{
			Set<String> missingModules = new TreeSet<>(required.modules());
			missingModules.removeAll(runtimeDescription.modules());
			throw new IOException("The Java runtime " + runtime + " (Java " + runtimeDescription.javaFeature() +
				") does not satisfy " + requirement + ", which requires Java " + required.javaFeature() + " or later " +
				"with the modules " + required.modules() + "; missing modules: " + missingModules + ". Rerun the entry " +
				"command of a newer CAT release to install a runtime that satisfies it.");
		}
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
	private InstalledRuntime()
	{
	}
}
