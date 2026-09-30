package io.github.cowwoc.cat.common.optimizer.candidate;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** An immutable, exact-byte snapshot of prompt files beneath one project. */
public final class PromptSnapshot
{
	private final String id;
	private final Map<Path, Entry> entries;
	private final Map<String, byte[]> blobs;

	/**
	 * Creates a snapshot from validated entries.
	 *
	 * @param entries normalized entries
	 */
	private PromptSnapshot(Map<Path, Entry> entries)
	{
		this.entries = Map.copyOf(entries);
		Map<String, byte[]> capturedBlobs = new TreeMap<>();
		for (Entry entry: entries.values())
			capturedBlobs.put(entry.hash(), entry.bytes());
		blobs = Map.copyOf(capturedBlobs);
		id = manifestHash(entries);
	}

	/**
	 * Captures regular prompt files beneath confirmed project-relative paths.
	 *
	 * @param project project root
	 * @param paths confirmed prompt paths
	 * @return exact-byte snapshot
	 * @throws IOException if an artifact is unsafe or unsupported
	 */
	public static PromptSnapshot capture(Path project, Collection<Path> paths) throws IOException
	{
		Objects.requireNonNull(project, "project");
		Objects.requireNonNull(paths, "paths");
		Path normalizedProject = project.toAbsolutePath().normalize();
		if (!Files.isDirectory(normalizedProject) || Files.isSymbolicLink(normalizedProject))
			throw new IOException("Project must be a real directory: " + project);
		Map<Path, Entry> entries = new TreeMap<>(Comparator.comparing(PromptSnapshot::portablePath));
		for (Path path: paths)
			capturePath(normalizedProject, resolve(normalizedProject, path), entries);
		return new PromptSnapshot(entries);
	}

	/**
	 * Restores a snapshot only after independently validating all manifest and blob identities.
	 *
	 * @param manifest persisted manifest
	 * @param suppliedBlobs blobs reached by that manifest
	 * @return validated snapshot
	 * @throws IOException if a persisted identity is invalid
	 */
	static PromptSnapshot restore(SnapshotManifest manifest, Map<String, byte[]> suppliedBlobs) throws IOException
	{
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(suppliedBlobs, "suppliedBlobs");
		Map<Path, Entry> restored = new TreeMap<>(Comparator.comparing(PromptSnapshot::portablePath));
		for (SnapshotEntry entry: manifest.entries())
		{
			Path relative = normalizedRelative(entry.relativePath());
			if (restored.containsKey(relative))
				throw new IOException("Snapshot manifest contains a duplicate path: " + relative);
			byte[] bytes = suppliedBlobs.get(entry.contentSha256());
			if (bytes == null)
				throw new IOException("Snapshot blob is missing: " + entry.contentSha256());
			if (!sha256(bytes).equals(entry.contentSha256()))
				throw new IOException("Snapshot blob does not match its content identity: " + entry.contentSha256());
			restored.put(relative, new Entry(entry.kind(), entry.contentSha256(), bytes));
		}
		PromptSnapshot snapshot = new PromptSnapshot(restored);
		if (!snapshot.id().equals(manifest.candidateId()))
			throw new IOException("Snapshot candidate id does not match manifest entries");
		return snapshot;
	}

	/**
	 * Returns the content-addressed candidate identity.
	 *
	 * @return candidate identity
	 */
	public String id()
	{
		return id;
	}

	/**
	 * Returns immutable entries keyed by normalized project-relative path.
	 *
	 * @return immutable entries
	 */
	public Map<Path, Entry> entries()
	{
		return entries;
	}

	/**
	 * Derives an exact-byte prompt snapshot from selected paths in this retained project snapshot.
	 *
	 * @param paths project-relative files or directory roots to retain
	 * @return immutable snapshot containing only matching retained entries
	 * @throws IOException if a selection path is unsafe or noncanonical
	 */
	public PromptSnapshot select(Collection<Path> paths) throws IOException
	{
		Objects.requireNonNull(paths, "paths");
		Set<Path> selectedPaths = new HashSet<>();
		for (Path path: paths)
		{
			Path normalized = normalizedRelative(Objects.requireNonNull(path, "path"));
			if (!path.equals(normalized) || path.toString().isBlank())
				throw new IOException("Snapshot selection path is noncanonical: " + path);
			selectedPaths.add(path);
		}
		Map<Path, Entry> selected = new TreeMap<>(Comparator.comparing(PromptSnapshot::portablePath));
		for (Map.Entry<Path, Entry> entry: entries.entrySet())
			if (selectedPaths.stream().anyMatch(path -> entry.getKey().equals(path) || entry.getKey().startsWith(path)))
				selected.put(entry.getKey(), entry.getValue());
		return new PromptSnapshot(selected);
	}

	/**
	 * Returns an exact-byte blob identified by hash.
	 *
	 * @param hash blob content identity
	 * @return blob when reachable by this snapshot
	 */
	public Optional<byte[]> blob(String hash)
	{
		byte[] blob = blobs.get(hash);
		if (blob == null)
			return Optional.empty();
		return Optional.of(blob.clone());
	}

	/**
	 * Creates this snapshot's durable manifest.
	 *
	 * @return immutable manifest
	 */
	public SnapshotManifest manifest()
	{
		return new SnapshotManifest(SnapshotManifest.SCHEMA_VERSION, id, entries.entrySet().stream().
			map(entry -> new SnapshotEntry(entry.getKey(), entry.getValue().kind(), entry.getValue().hash())).toList());
	}

	/**
	 * Captures a present file or directory tree without links, or records no entry for an absent path.
	 *
	 * @param project normalized root
	 * @param path resolved prompt path
	 * @param entries destination entries
	 * @throws IOException if an artifact is unsafe
	 */
	private static void capturePath(Path project, Path path, Map<Path, Entry> entries) throws IOException
	{
		Path current = project;
		for (Path component: project.relativize(path))
		{
			current = current.resolve(component);
			if (Files.isSymbolicLink(current))
				throw new IOException("Symbolic links are not supported: " + current);
		}
		if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS))
			return;
		if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
		{
			captureFile(project, path, entries);
			return;
		}
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Unsupported prompt artifact: " + path);
		Files.walkFileTree(path, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				if (attributes.isSymbolicLink())
					throw new IOException("Symbolic links are not supported: " + file);
				if (!attributes.isRegularFile())
					throw new IOException("Unsupported prompt artifact: " + file);
				captureFile(project, file, entries);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Captures one regular file.
	 *
	 * @param project normalized root
	 * @param file regular file
	 * @param entries destination entries
	 * @throws IOException if reading fails
	 */
	private static void captureFile(Path project, Path file, Map<Path, Entry> entries) throws IOException
	{
		Path relative = normalizedRelative(project.relativize(file.toAbsolutePath().normalize()));
		byte[] bytes = Files.readAllBytes(file);
		entries.put(relative, new Entry(FileKind.REGULAR, sha256(bytes), bytes));
	}

	/**
	 * Resolves a project-contained path.
	 *
	 * @param project normalized root
	 * @param path supplied path
	 * @return contained resolved path
	 * @throws IOException if the path escapes
	 */
	private static Path resolve(Path project, Path path) throws IOException
	{
		Objects.requireNonNull(path, "path");
		Path resolved = project.resolve(path);
		if (path.isAbsolute())
			resolved = path;
		resolved = resolved.normalize();
		if (!resolved.startsWith(project))
			throw new IOException("Prompt path escapes the project: " + path);
		return resolved;
	}

	/**
	 * Validates a normalized project-relative path.
	 *
	 * @param path path to validate
	 * @return normalized relative path
	 * @throws IOException if it escapes
	 */
	private static Path normalizedRelative(Path path) throws IOException
	{
		Path normalized = path.normalize();
		if (normalized.isAbsolute() || normalized.getNameCount() == 0 || normalized.startsWith(".."))
			throw new IOException("Snapshot path escapes the project: " + path);
		return normalized;
	}

	/**
	 * Computes the identity from ordered portable path, kind, and hash tuples.
	 *
	 * @param entries snapshot entries
	 * @return candidate identity
	 */
	private static String manifestHash(Map<Path, Entry> entries)
	{
		MessageDigest digest = digest();
		entries.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(PromptSnapshot::portablePath))).
			forEach(entry -> updateIdentity(digest, entry));
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Adds one identity tuple to a digest.
	 *
	 * @param digest identity digest
	 * @param entry ordered entry
	 */
	private static void updateIdentity(MessageDigest digest, Map.Entry<Path, Entry> entry)
	{
		digest.update(portablePath(entry.getKey()).getBytes(UTF_8));
		digest.update((byte) 0);
		digest.update(entry.getValue().kind().name().getBytes(UTF_8));
		digest.update((byte) 0);
		digest.update(entry.getValue().hash().getBytes(UTF_8));
		digest.update((byte) 0);
	}

	/**
	 * Converts a path to its normalized portable spelling.
	 *
	 * @param path relative path
	 * @return forward-slash spelling
	 */
	private static String portablePath(Path path)
	{
		return path.toString().replace('\\', '/');
	}

	/**
	 * Computes an exact-byte SHA-256 identity.
	 *
	 * @param bytes exact content
	 * @return lower-case identity
	 */
	static String sha256(byte[] bytes)
	{
		return HexFormat.of().formatHex(digest().digest(bytes));
	}

	/**
	 * Checks a lower-case SHA-256 identity.
	 *
	 * @param value candidate value
	 * @return whether the value is valid
	 */
	static boolean isSha256(String value)
	{
		return value != null && value.matches("[0-9a-f]{64}");
	}

	/**
	 * Creates a SHA-256 digest.
	 *
	 * @return digest instance
	 */
	private static MessageDigest digest()
	{
		try
		{
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("SHA-256 is unavailable", e);
		}
	}

	/** The supported prompt file kind. */
	public enum FileKind
	{
		/** A byte-for-byte regular file. */
		REGULAR
	}

	/**
	 * One content-addressed snapshot entry.
	 *
	 * @param kind file kind
	 * @param hash exact-byte identity
	 * @param bytes exact file bytes
	 */
	public record Entry(FileKind kind, String hash, byte[] bytes)
	{
		/**
		 * Validates and defensively copies one entry.
		 *
		 * @param kind file kind
		 * @param hash exact-byte identity
		 * @param bytes exact file bytes
		 */
		public Entry
		{
			Objects.requireNonNull(kind, "kind");
			if (!isSha256(hash))
				throw new IllegalArgumentException("Entry hash must be a SHA-256 identity");
			bytes = Objects.requireNonNull(bytes, "bytes").clone();
			if (!sha256(bytes).equals(hash))
				throw new IllegalArgumentException("Entry bytes do not match hash");
		}

		@Override
		public byte[] bytes()
		{
			return bytes.clone();
		}
	}
}
