package io.github.cowwoc.cat.common.evaluation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Stores immutable evaluation files once for each content hash.
 */
public final class FileStore
{
	private static final String PUBLICATION_LOCK = ".publication.lock";

	/**
	 * Prevents construction of this static utility.
	 */
	private FileStore()
	{
	}

	/**
	 * Copies an input file to its content-addressed location, reusing an equal existing copy.
	 *
	 * @param workRoot the CAT work root that owns reusable input copies
	 * @param source the regular input file to retain
	 * @return the immutable stored file
	 * @throws IOException if the source or stored copy is unsafe, changes, or cannot be copied
	 */
	public static Path retain(Path workRoot, Path source) throws IOException
	{
		return retain(workRoot, source, WorkDirectories.INPUT_ARTIFACTS, "input");
	}

	/**
	 * Copies terminal execution evidence to its content-addressed location, reusing an equal existing copy.
	 *
	 * <p>
	 * A retained evaluation record refers to these copies after handoff. Keeping results outside a per-run workspace
	 * lets the evaluator remove isolated harness state without making a later report, review, or comparison lose the
	 * evidence it needs.
	 *
	 * @param workRoot the CAT work root that owns reusable evaluation evidence
	 * @param source the regular evidence file to retain
	 * @return the immutable stored file
	 * @throws IOException if the source or stored copy is unsafe, changes, or cannot be copied
	 */
	public static Path retainExecutionEvidence(Path workRoot, Path source) throws IOException
	{
		return retain(workRoot, source, WorkDirectories.EVIDENCE_ARTIFACTS, "evidence");
	}

	/**
	 * Copies one immutable evaluation file to its selected content-addressed store.
	 *
	 * @param workRoot the CAT work root that owns reusable evaluation artifacts
	 * @param source the regular file to retain
	 * @param directory the store directory name
	 * @param kind the artifact kind used in diagnostics
	 * @return the immutable stored file
	 * @throws IOException if the source or stored copy is unsafe, changes, or cannot be copied
	 */
	private static Path retain(Path workRoot, Path source, String directory, String kind) throws IOException
	{
		if (workRoot == null)
			throw new NullPointerException("workRoot must not be null");
		if (source == null)
			throw new NullPointerException("source must not be null");
		Path file = source.toAbsolutePath().normalize();
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file))
			throw new IOException("Evaluation " + kind + " is not a regular file: " + file);
		String fingerprint = sha256(file);
		Path root = workRoot.toAbsolutePath().normalize().resolve(directory);
		Path destination = root.resolve(fingerprint);
		Files.createDirectories(root);
		try (FileChannel channel = FileChannel.open(root.resolve(PUBLICATION_LOCK), StandardOpenOption.CREATE,
			StandardOpenOption.WRITE); FileLock lock = channel.lock())
		{
			if (!lock.isValid())
				throw new IOException("Cannot lock evaluation " + kind + " publication: " + root);
			if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
			{
				verify(destination, fingerprint);
				return destination;
			}
			Path staging = Files.createTempFile(root, "." + fingerprint + "-", ".staging");
			try
			{
				Files.copy(file, staging, StandardCopyOption.REPLACE_EXISTING);
				verify(staging, fingerprint);
				try
				{
					return Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE).toAbsolutePath().normalize();
				}
				catch (java.nio.file.AtomicMoveNotSupportedException _)
				{
					return Files.move(staging, destination).toAbsolutePath().normalize();
				}
			}
			finally
			{
				Files.deleteIfExists(staging);
			}
		}
	}

	/**
	 * Collects unreferenced input copies after retaining the newest configured number.
	 *
	 * @param workRoot the CAT work root that owns the store
	 * @param referencedArtifacts stored input copies protected by retained records
	 * @param retainedUnreferencedBudget the newest unreferenced copies to retain
	 * @param dryRun whether to report candidates without deleting them
	 * @return the collection decision
	 * @throws IOException if a protected copy is invalid or collection cannot complete
	 */
	public static Collection collect(Path workRoot, Set<Path> referencedArtifacts, int retainedUnreferencedBudget,
		boolean dryRun) throws IOException
	{
		return collect(workRoot, referencedArtifacts, retainedUnreferencedBudget, dryRun,
			WorkDirectories.INPUT_ARTIFACTS, "input");
	}

	/**
	 * Collects unreferenced terminal-evidence copies after retaining the newest configured number.
	 *
	 * @param workRoot the CAT work root that owns the store
	 * @param referencedArtifacts stored evidence copies protected by retained records
	 * @param retainedUnreferencedBudget the newest unreferenced copies to retain
	 * @param dryRun whether to report candidates without deleting them
	 * @return the collection decision
	 * @throws IOException if a protected copy is invalid or collection cannot complete
	 */
	public static Collection collectExecutionEvidence(Path workRoot, Set<Path> referencedArtifacts,
		int retainedUnreferencedBudget, boolean dryRun) throws IOException
	{
		return collect(workRoot, referencedArtifacts, retainedUnreferencedBudget, dryRun,
			WorkDirectories.EVIDENCE_ARTIFACTS,
			"evidence");
	}

	/**
	 * Collects unreferenced copies from one immutable evaluation-file store.
	 *
	 * @param workRoot the CAT work root that owns the store
	 * @param referencedArtifacts stored copies protected by retained records
	 * @param retainedUnreferencedBudget the newest unreferenced copies to retain
	 * @param dryRun whether to report candidates without deleting them
	 * @param directory the selected content-addressed store directory
	 * @param kind the artifact kind used in diagnostics
	 * @return the collection decision
	 * @throws IOException if a protected copy is invalid or collection cannot complete
	 */
	private static Collection collect(Path workRoot, Set<Path> referencedArtifacts, int retainedUnreferencedBudget,
		boolean dryRun, String directory, String kind) throws IOException
	{
		if (workRoot == null)
			throw new NullPointerException("workRoot must not be null");
		if (referencedArtifacts == null)
			throw new NullPointerException("referencedArtifacts must not be null");
		if (retainedUnreferencedBudget < 0)
			throw new IllegalArgumentException("retainedUnreferencedBudget must not be negative");
		Path root = workRoot.toAbsolutePath().normalize().resolve(directory);
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS))
			return new Collection(List.of(), 0, 0, dryRun);
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
			throw new IOException("Evaluation " + kind + " store is not a regular directory: " + root);
		List<Path> references = referencedArtifacts.stream().map(path ->
		{
			if (path == null)
				throw new NullPointerException("referencedArtifacts must not contain null");
			return path.toAbsolutePath().normalize();
		}).toList();
		try (FileChannel channel = FileChannel.open(root.resolve(PUBLICATION_LOCK), StandardOpenOption.CREATE,
			StandardOpenOption.WRITE); FileLock lock = channel.lock())
		{
			if (!lock.isValid())
				throw new IOException("Cannot lock evaluation " + kind + " collection: " + root);
			for (Path reference : references)
				verifyReference(root, reference, kind);
			List<Path> unreferenced;
			try (Stream<Path> entries = Files.list(root))
			{
				unreferenced = entries.filter(path -> !path.getFileName().toString().equals(PUBLICATION_LOCK)).
					peek(entry -> verifyStoredEntry(entry, kind)).
					filter(path -> !references.contains(path.toAbsolutePath().normalize())).
					sorted(Comparator.comparingLong(FileStore::lastModified)).toList();
			}
			List<Path> collected = new ArrayList<>();
			for (int index = 0; index < unreferenced.size() - retainedUnreferencedBudget; ++index)
			{
				Path artifact = unreferenced.get(index);
				collected.add(artifact);
				if (!dryRun)
					Files.deleteIfExists(artifact);
			}
			return new Collection(List.copyOf(collected), references.size(),
				Math.min(retainedUnreferencedBudget, unreferenced.size()), dryRun);
		}
	}

	/**
	 * Reads the time used to retain the newest unreferenced copies.
	 *
	 * @param path the stored copy
	 * @return its last-modified time in milliseconds
	 */
	private static long lastModified(Path path)
	{
		try
		{
			return Files.getLastModifiedTime(path).toMillis();
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Confirms a record reference names an immutable evaluation artifact held by this store.
	 *
	 * @param root the selected evaluation-artifact store
	 * @param reference the record-owned artifact path
	 * @param kind the artifact kind used in diagnostics
	 * @throws IOException if the reference is outside the store or its content address is invalid
	 */
	private static void verifyReference(Path root, Path reference, String kind) throws IOException
	{
		if (!root.equals(reference.getParent()) || !isFingerprint(reference.getFileName().toString()))
			throw new IOException("Evaluation reference is not an immutable " + kind + " artifact: " + reference);
		verify(reference, reference.getFileName().toString());
	}

	/**
	 * Confirms that one store entry is a valid immutable evaluation artifact.
	 *
	 * @param entry the stored entry
	 * @param kind the artifact kind used in diagnostics
	 * @throws IllegalStateException if the entry is not safe to scan
	 */
	private static void verifyStoredEntry(Path entry, String kind)
	{
		String name = entry.getFileName().toString();
		if (!isFingerprint(name))
			throw new IllegalStateException("Evaluation " + kind + " store contains an unknown entry: " + entry);
		try
		{
			verify(entry, name);
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Determines whether a filename is a SHA-256 content address.
	 *
	 * @param value the filename to inspect
	 * @return whether the name is a lowercase hexadecimal SHA-256 digest
	 */
	private static boolean isFingerprint(String value)
	{
		return value.matches("[0-9a-f]{64}");
	}

	/**
	 * Confirms that a stored file still has the content named by its path.
	 *
	 * @param file the stored file
	 * @param fingerprint the required SHA-256 value
	 * @throws IOException if the file is unsafe or its content changed
	 */
	private static void verify(Path file, String fingerprint) throws IOException
	{
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file) ||
			!sha256(file).equals(fingerprint))
		{
			throw new IOException("Stored evaluation input does not match its content address: " + file);
		}
	}

	/**
	 * Computes the SHA-256 value of one regular file's bytes.
	 *
	 * @param file the file to hash
	 * @return the lowercase hexadecimal SHA-256 value
	 * @throws IOException if the file cannot be read or SHA-256 is unavailable
	 */
	private static String sha256(Path file) throws IOException
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(file))
			{
				byte[] buffer = new byte[8_192];
				int count = input.read(buffer);
				while (count >= 0)
				{
					digest.update(buffer, 0, count);
					count = input.read(buffer);
				}
			}
			return java.util.HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Reports one immutable-artifact collection decision.
	 *
	 * @param collected the artifacts selected for collection
	 * @param referencedArtifacts the number protected by retained evaluation records
	 * @param retainedUnreferencedArtifacts the number preserved by the unreferenced-artifact budget
	 * @param dryRun whether no selected artifact was deleted
	 */
	public record Collection(List<Path> collected, int referencedArtifacts, int retainedUnreferencedArtifacts,
		boolean dryRun)
	{
		/**
		 * Validates the reported collection decision.
		 *
		 * @param collected the artifacts selected for collection
		 * @param referencedArtifacts the number protected by retained evaluation records
		 * @param retainedUnreferencedArtifacts the number preserved by the unreferenced-artifact budget
		 * @param dryRun whether no selected artifact was deleted
		 */
		public Collection
		{
			if (collected == null)
				throw new NullPointerException("collected must not be null");
			if (referencedArtifacts < 0)
				throw new IllegalArgumentException("referencedArtifacts must not be negative");
			if (retainedUnreferencedArtifacts < 0)
				throw new IllegalArgumentException("retainedUnreferencedArtifacts must not be negative");
		}
	}
}
