package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.PortablePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;


/**
 * Records and verifies the files that can change a packaged harness runner's behavior.
 *
 * <p>
 * The manifest deliberately excludes model-facing rules, skills, and agent instructions. A prompt revision must
 * remain comparable with its prior revision, while a changed application module, launcher, hook, or harness
 * registration must start a new evaluation environment. The package builder writes this file from the authoritative
 * runtime roots; evaluators verify every listed path before using its hash as the runner identity.
 * <p>
 * The plugin's {@link RuntimeBinding runtime binding} is written by each installation, so it is not a packaged entry:
 * binding the same package to another installation keeps its packaged identity. The Java runtime that the binding
 * names is part of the execution identity instead, so a changed runtime also starts a new evaluation environment.
 * The installation also writes the plugin's {@link StartupCache startup cache}, which only speeds up the start of the
 * same programs, so neither identity includes it.
 */
public final class RunnerRuntimeManifest
{
	/**
	 * The artifact-relative location of the packaged manifest.
	 */
	public static final Path PATH = Path.of("client/runner-runtime.manifest");
	private static final String HEADER = "CAT runner runtime manifest v3";
	private static final Set<Path> DIRECTORY_ROOTS = Set.of(Path.of("bin"), Path.of("client"), Path.of("hooks"));
	private static final Set<Path> FILE_ROOTS = Set.of(Path.of(".claude-plugin/plugin.json"),
		Path.of(".codex-plugin/plugin.json"));

	/**
	 * Writes the manifest for an assembled artifact.
	 *
	 * @param artifact the assembled plugin directory
	 *
	 * @throws IOException if a runner-relevant artifact cannot be listed or hashed
	 */
	public static void write(Path artifact) throws IOException
	{
		Path root = root(artifact);
		Path manifest = root.resolve(PATH);
		if (Files.exists(manifest, NOFOLLOW_LINKS))
			throw new IOException("Runner runtime manifest already exists: " + manifest);
		List<Entry> entries = entries(root);
		List<String> lines = new ArrayList<>(entries.size() + 1);
		lines.add(HEADER);
		for (Entry entry : entries)
			lines.add(entry.kind() + "\t" + entry.sha256() + "\t" + entry.path());
		Files.createDirectories(manifest.getParent());
		Files.write(manifest, lines, UTF_8);
	}

	/**
	 * Returns the execution identity of an installed plugin: its packaged identity combined with the identity of the
	 * Java runtime that it is bound to, after verifying both.
	 *
	 * @param artifact the assembled plugin directory
	 *
	 * @return the SHA-256 identity of every verified packaged entry and of the bound runtime's manifest
	 *
	 * @throws IOException if a runtime input is missing, changed, omitted, or malformed, the plugin has no valid
	 *   runtime binding, or the bound runtime does not match its manifest. The message names the file at fault.
	 */
	public static String fingerprint(Path artifact) throws IOException
	{
		Path root = root(artifact);
		List<Entry> entries = verifiedEntries(root);
		String runtime = InstalledRuntime.identity(RuntimeBinding.read(root));
		MessageDigest digest = identityDigest(entries);
		digest.update(("\nruntime\t" + runtime).getBytes(UTF_8));
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Returns the identity of one exact packaged runner, which is the same wherever the package is installed.
	 *
	 * <p>
	 * A lifecycle check establishes behavior of the assembled package itself, so its identity depends only on the
	 * packaged entries and not on the installation's runtime binding.
	 *
	 * @param artifact the assembled plugin directory
	 *
	 * @return the SHA-256 identity of every verified packaged entry
	 *
	 * @throws IOException if a runtime input is missing, changed, omitted, or malformed
	 */
	public static String packagedFingerprint(Path artifact) throws IOException
	{
		return HexFormat.of().formatHex(identityDigest(verifiedEntries(root(artifact))).digest());
	}

	/**
	 * Reads the manifest and verifies every runtime entry before an identity is derived.
	 *
	 * @param root the normalized assembled plugin directory
	 *
	 * @return the verified manifest entries
	 *
	 * @throws IOException if a runtime input is missing, changed, omitted, or malformed
	 */
	private static List<Entry> verifiedEntries(Path root) throws IOException
	{
		Path manifest = root.resolve(PATH);
		if (!Files.isRegularFile(manifest, NOFOLLOW_LINKS))
			throw new IOException("Runner artifact is missing its packaged runtime manifest: " + manifest);
		List<String> lines = Files.readAllLines(manifest, UTF_8);
		if (lines.isEmpty() || !lines.getFirst().equals(HEADER))
			throw new IOException("Runner runtime manifest has an unsupported header: " + manifest);
		List<Entry> declared = new ArrayList<>();
		for (int index = 1; index < lines.size(); ++index)
			declared.add(parse(lines.get(index), manifest, index + 1));
		List<Entry> actual = entries(root);
		if (!declared.equals(actual))
		{
			throw new IOException("Runner runtime manifest does not match the packaged runtime, hooks, and harness " +
				"registration: " + manifest);
		}
		return declared;
	}

	/**
	 * Parses one manifest entry without permitting a path outside the package-declared runtime surface.
	 *
	 * @param line the manifest line
	 *
	 * @param manifest the manifest being read
	 *
	 * @param lineNumber the one-based source line number
	 *
	 * @return the validated entry
	 *
	 * @throws IOException if the entry is malformed or points outside the runtime surface
	 */
	private static Entry parse(String line, Path manifest, int lineNumber) throws IOException
	{
		String[] fields = line.split("\\t", -1);
		if (fields.length != 3 || (!fields[0].equals("file") && !fields[0].equals("link")) ||
			!fields[1].matches("[0-9a-f]{64}"))
		{
			throw new IOException("Malformed runner runtime manifest entry at " + manifest + ":" + lineNumber);
		}
		Path path = Path.of(fields[2]);
		if (path.isAbsolute() || path.normalize().startsWith("..") || !isRuntimePath(path))
			throw new IOException("Unsafe runner runtime manifest path at " + manifest + ":" + lineNumber);
		return new Entry(fields[0], fields[1], PortablePaths.text(path));
	}

	/**
	 * Lists the package-declared files that can change harness runner behavior, except the manifest itself, the
	 * installation-specific runtime binding and the installation-written startup cache.
	 *
	 * @param root the assembled artifact directory
	 *
	 * @return sorted runtime entries
	 *
	 * @throws IOException if an entry cannot be inspected
	 */
	private static List<Entry> entries(Path root) throws IOException
	{
		List<Entry> result = new ArrayList<>();
		for (Path directory : DIRECTORY_ROOTS)
		{
			Path source = root.resolve(directory);
			if (!Files.isDirectory(source, NOFOLLOW_LINKS))
				continue;
			try (Stream<Path> paths = Files.walk(source))
			{
				for (Path path : paths.filter(path -> !path.equals(root.resolve(PATH)) &&
					!path.equals(root.resolve(RuntimeBinding.PATH)) &&
					!path.startsWith(root.resolve(StartupCache.DIRECTORY))).
					filter(path -> Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)).sorted().
				toList())
				{
			result.add(entry(root, path));
				}
			}
		}
		for (Path file : FILE_ROOTS)
		{
			Path source = root.resolve(file);
			if (Files.isRegularFile(source, NOFOLLOW_LINKS) || Files.isSymbolicLink(source))
				result.add(entry(root, source));
		}
		return result.stream().sorted().toList();
	}

	/**
	 * Builds one content-addressed runtime entry.
	 *
	 * @param root the assembled artifact directory
	 *
	 * @param file a regular file or symbolic link beneath the declared runtime surface
	 *
	 * @return the entry
	 *
	 * @throws IOException if the file cannot be read
	 */
	private static Entry entry(Path root, Path file) throws IOException
	{
		String kind;
		String fingerprint;
		if (Files.isSymbolicLink(file))
		{
			kind = "link";
			fingerprint = sha256(PortablePaths.text(Files.readSymbolicLink(file)));
		}
		else
		{
			kind = "file";
			fingerprint = sha256(file);
		}
		return new Entry(kind, fingerprint, PortablePaths.text(root.relativize(file)));
	}

	/**
	 * Starts the digest of a packaged identity after all packaged output has been verified.
	 *
	 * @param entries the verified manifest entries
	 *
	 * @return a SHA-256 digest that holds the manifest header and every entry
	 *
	 * @throws IOException if SHA-256 is unavailable
	 */
	private static MessageDigest identityDigest(List<Entry> entries) throws IOException
	{
		MessageDigest digest;
		try
		{
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
		digest.update(HEADER.getBytes(UTF_8));
		for (Entry entry : entries)
		{
			digest.update((byte) '\n');
			digest.update(entry.kind().getBytes(UTF_8));
			digest.update((byte) '\t');
			digest.update(entry.sha256().getBytes(UTF_8));
			digest.update((byte) '\t');
			digest.update(entry.path().getBytes(UTF_8));
		}
		return digest;
	}

	/**
	 * Indicates whether a relative path belongs to the packaging-owned runtime surface.
	 *
	 * @param path the artifact-relative path
	 *
	 * @return {@code true} if the package builder explicitly declares the path as runner behavior
	 */
	private static boolean isRuntimePath(Path path)
	{
		return DIRECTORY_ROOTS.stream().anyMatch(path::startsWith) || FILE_ROOTS.contains(path);
	}

	/**
	 * Validates and normalizes an artifact directory.
	 *
	 * @param artifact the caller-supplied artifact directory
	 *
	 * @return the normalized artifact directory
	 *
	 * @throws IOException if the directory does not exist
	 */
	private static Path root(Path artifact) throws IOException
	{
		Path result = artifact.toAbsolutePath().normalize();
		if (!Files.isDirectory(result, NOFOLLOW_LINKS))
			throw new IOException("Runner artifact directory does not exist: " + result);
		return result;
	}

	/**
	 * Hashes one binary or text file.
	 *
	 * @param input the file to hash
	 *
	 * @return its SHA-256 value
	 *
	 * @throws IOException if SHA-256 is unavailable or the file cannot be read
	 */
	private static String sha256(Path input) throws IOException
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Hashes one symbolic-link target.
	 *
	 * @param input the link target
	 *
	 * @return its SHA-256 value
	 *
	 * @throws IOException if SHA-256 is unavailable
	 */
	private static String sha256(String input) throws IOException
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(UTF_8)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * One ordered runtime file or link entry.
	 *
	 * @param kind indicates whether the path is a regular file or link
	 *
	 * @param sha256 the path content or link-target hash
	 *
	 * @param path the artifact-relative path
	 */
	private record Entry(String kind, String sha256, String path) implements Comparable<Entry>
	{
		@Override
		public int compareTo(Entry other)
		{
			return path.compareTo(other.path);
		}
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private RunnerRuntimeManifest()
	{
	}
}
