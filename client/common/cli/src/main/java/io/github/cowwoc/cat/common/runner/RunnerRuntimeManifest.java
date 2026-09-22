package io.github.cowwoc.cat.common.runner;

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
 * remain comparable with its prior revision, while a changed Java runtime, hook, or harness registration must start a
 * new evaluation environment. The package builder writes this file from the authoritative runtime roots; evaluators
 * verify every listed path before using its hash as the runner identity.
 */
public final class RunnerRuntimeManifest
{
	/**
	 * The artifact-relative location of the packaged manifest.
	 */
	public static final Path PATH = Path.of("client/runner-runtime.manifest");
	private static final String HEADER = "CAT runner runtime manifest v2";
	private static final Set<Path> DIRECTORY_ROOTS = Set.of(Path.of("client"), Path.of("hooks"));
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
	 * Verifies that an artifact has the exact runtime manifest the package builder produced.
	 *
	 * @param artifact the assembled plugin directory
	 *
	 * @return the identity hash formed from runner configuration and declared build inputs, not generated runtime bytes
	 *
	 * @throws IOException if a runtime input is missing, changed, omitted, or malformed
	 */
	public static String fingerprint(Path artifact) throws IOException
	{
		return identityFingerprint(verifiedEntries(artifact), false);
	}

	/**
	 * Returns the identity of one exact packaged runner, including generated runtime bytes.
	 *
	 * <p>
	 * A lifecycle check establishes behavior of the assembled package itself. Unlike a prompt-comparison identity, it
	 * must change when the generated runtime changes so a prior check cannot authorize a later package.
	 *
	 * @param artifact the assembled plugin directory
	 *
	 * @return the SHA-256 identity of every verified runtime entry
	 *
	 * @throws IOException if a runtime input is missing, changed, omitted, or malformed
	 */
	public static String packagedFingerprint(Path artifact) throws IOException
	{
		return identityFingerprint(verifiedEntries(artifact), true);
	}

	/**
	 * Reads the manifest and verifies every runtime entry before an identity is derived.
	 *
	 * @param artifact the assembled plugin directory
	 *
	 * @return the verified manifest entries
	 *
	 * @throws IOException if a runtime input is missing, changed, omitted, or malformed
	 */
	private static List<Entry> verifiedEntries(Path artifact) throws IOException
	{
		Path root = root(artifact);
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
		if (fields.length != 3 || (!fields[0].equals("file") && !fields[0].equals("generated-file") &&
			!fields[0].equals("link")) ||
			!fields[1].matches("[0-9a-f]{64}"))
		{
			throw new IOException("Malformed runner runtime manifest entry at " + manifest + ":" + lineNumber);
		}
		Path path = Path.of(fields[2]);
		if (path.isAbsolute() || path.normalize().startsWith("..") || !isRuntimePath(path))
			throw new IOException("Unsafe runner runtime manifest path at " + manifest + ":" + lineNumber);
		return new Entry(fields[0], fields[1], path.toString().replace(path.getFileSystem().getSeparator(), "/"));
	}

	/**
	 * Lists the package-declared files that can change native runner behavior.
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
				for (Path path : paths.filter(path -> !path.equals(root.resolve(PATH))).
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
			fingerprint = sha256(Files.readSymbolicLink(file).toString());
		}
		else
		{
			kind = "file";
			if (generatedRuntimePath(root.relativize(file)))
				kind = "generated-file";
			fingerprint = sha256(file);
		}
		return new Entry(kind, fingerprint, root.relativize(file).toString().replace(file.getFileSystem().
				getSeparator(),
			"/"));
	}

	/**
	 * Determines whether a packaged path is regenerated by jlink rather than a project-controlled runner input.
	 *
	 * @param path the artifact-relative path
	 *
	 * @return {@code true} if the path verifies the assembled image but does not by itself define a comparison identity
	 */
	private static boolean generatedRuntimePath(Path path)
	{
		return path.startsWith(Path.of("client", "lib")) || path.startsWith(Path.of("client", "conf")) ||
			path.equals(Path.of("client", "release"));
	}

	/**
	 * Computes the stable comparison identity after all packaged output has been verified.
	 *
	 * <p>
	 * jlink can regenerate its own runtime and startup-optimization bytes from unchanged CAT inputs. Those bytes must
	 * still match this package's manifest so corruption is detected, but using them as the comparison identity would
	 * falsely make equivalent evaluations incomparable. The build-plan input fingerprint records the project-controlled
	 * inputs that determine them.
	 *
	 * @param entries the verified manifest entries
	 *
	 * @param includeGenerated whether generated runtime bytes define this identity
	 *
	 * @return the SHA-256 identity of stable runner configuration entries
	 *
	 * @throws IOException if SHA-256 is unavailable
	 */
	private static String identityFingerprint(List<Entry> entries, boolean includeGenerated) throws IOException
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(HEADER.getBytes(UTF_8));
			for (Entry entry : entries)
			{
				if (!includeGenerated && entry.kind().equals("generated-file"))
					continue;
				digest.update((byte) '\n');
				digest.update(entry.kind().getBytes(UTF_8));
				digest.update((byte) '\t');
				digest.update(entry.sha256().getBytes(UTF_8));
				digest.update((byte) '\t');
				digest.update(entry.path().getBytes(UTF_8));
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Determines whether a relative path belongs to the packaging-owned runtime surface.
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
	 * @param file the file to hash
	 *
	 * @return its SHA-256 value
	 *
	 * @throws IOException if SHA-256 is unavailable or the file cannot be read
	 */
	private static String sha256(Path file) throws IOException
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Hashes one symbolic-link target.
	 *
	 * @param value the link target
	 *
	 * @return its SHA-256 value
	 *
	 * @throws IOException if SHA-256 is unavailable
	 */
	private static String sha256(String value) throws IOException
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * One ordered runtime file or link entry.
	 *
	 * @param kind whether the path is a regular file or link
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
