package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Stores complete immutable evaluation suites once for each content hash.
 */
public final class SuiteStore
{
	private static final String PUBLICATION_LOCK = ".publication.lock";

	/**
	 * Prevents construction of this static utility.
	 */
	private SuiteStore()
	{
	}

	/**
	 * Copies a complete suite directory to its content-addressed location.
	 *
	 * @param workRoot the CAT work root that owns reusable suite copies
	 * @param suiteRoot the scenario-suite root
	 * @return the root in the immutable stored suite
	 * @throws IOException if the source or stored suite is unsafe or cannot be copied
	 */
	public static Path retain(Path workRoot, Path suiteRoot) throws IOException
	{
		if (workRoot == null)
			throw new NullPointerException("workRoot must not be null");
		if (suiteRoot == null)
			throw new NullPointerException("suiteRoot must not be null");
		Path source = suiteRoot.toAbsolutePath().normalize();
		if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source))
			throw new IOException("Evaluation suite root is not a regular directory: " + source);
		Path manifestName = Path.of("suite.json");
		String fingerprint = fingerprint(source);
		Path root = workRoot.toAbsolutePath().normalize().resolve(WorkDirectories.SUITE_ARTIFACTS);
		Path destination = root.resolve(fingerprint);
		Path retainedSuite = destination;
		Files.createDirectories(root);
		try (FileChannel channel = FileChannel.open(root.resolve(PUBLICATION_LOCK), StandardOpenOption.CREATE,
			StandardOpenOption.WRITE); FileLock lock = channel.lock())
		{
			if (!lock.isValid())
				throw new IOException("Cannot lock evaluation-suite publication: " + root);
			if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
			{
				verify(destination, fingerprint, manifestName);
				return retainedSuite;
			}
			Path staging = Files.createTempDirectory(root, "." + fingerprint + "-");
			try
			{
				copy(source, staging);
				verify(staging, fingerprint, manifestName);
				try
				{
					Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
				}
				catch (java.nio.file.AtomicMoveNotSupportedException _)
				{
					Files.move(staging, destination);
				}
				return retainedSuite;
			}
			finally
			{
				if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS))
					TemporaryDirectories.deleteRecursively(staging);
			}
		}
	}

	/**
	 * Collects unreferenced suite copies after retaining the newest configured number.
	 *
	 * @param workRoot the CAT work root that owns the store
	 * @param referencedManifests suite manifests protected by retained records
	 * @param retainedUnreferencedBudget the newest unreferenced suite copies to retain
	 * @param dryRun whether to report candidates without deleting them
	 * @return the collection decision
	 * @throws IOException if a protected suite is invalid or collection cannot complete
	 */
	public static Collection collect(Path workRoot, Set<Path> referencedManifests, int retainedUnreferencedBudget,
		boolean dryRun) throws IOException
	{
		if (workRoot == null)
			throw new NullPointerException("workRoot must not be null");
		if (referencedManifests == null)
			throw new NullPointerException("referencedManifests must not be null");
		if (retainedUnreferencedBudget < 0)
			throw new IllegalArgumentException("retainedUnreferencedBudget must not be negative");
		Path root = workRoot.toAbsolutePath().normalize().resolve(WorkDirectories.SUITE_ARTIFACTS);
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS))
			return new Collection(List.of(), 0, 0, dryRun);
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
			throw new IOException("Evaluation-suite store is not a regular directory: " + root);
		try (FileChannel channel = FileChannel.open(root.resolve(PUBLICATION_LOCK), StandardOpenOption.CREATE,
			StandardOpenOption.WRITE); FileLock lock = channel.lock())
		{
			if (!lock.isValid())
				throw new IOException("Cannot lock evaluation-suite collection: " + root);
			List<Path> referencedDirectories = referencedManifests.stream().map(path ->
			{
				if (path == null)
					throw new NullPointerException("referencedManifests must not contain null");
				Path manifest = path.toAbsolutePath().normalize();
				if (Files.isDirectory(manifest) && root.equals(manifest.getParent()) &&
					isFingerprint(manifest.getFileName().toString()))
				{
					return manifest;
				}
				Path directory = manifest.getParent();
				if (directory == null || !root.equals(directory.getParent()) ||
					!isFingerprint(directory.getFileName().toString()))
				{
					throw new IllegalArgumentException("Evaluation reference is not an immutable suite manifest: " + manifest);
				}
				return directory;
			}).toList();
			for (Path directory : referencedDirectories)
				verify(directory, directory.getFileName().toString(), referencedManifestName(referencedManifests, directory));
			List<Path> unreferenced;
			try (Stream<Path> entries = Files.list(root))
			{
				unreferenced = entries.filter(path -> !path.getFileName().toString().equals(PUBLICATION_LOCK)).
					filter(path -> !referencedDirectories.contains(path.toAbsolutePath().normalize())).
					sorted(Comparator.comparingLong(SuiteStore::lastModified)).toList();
			}
			for (Path suite : unreferenced)
				verifyStoredSuite(suite, suite.getFileName().toString());
			List<Path> collected = new ArrayList<>();
			for (int index = 0; index < unreferenced.size() - retainedUnreferencedBudget; ++index)
			{
				Path suite = unreferenced.get(index);
				collected.add(suite);
				if (!dryRun)
					TemporaryDirectories.deleteRecursively(suite);
			}
			return new Collection(List.copyOf(collected), referencedDirectories.size(),
				Math.min(retainedUnreferencedBudget, unreferenced.size()), dryRun);
		}
	}

	/**
	 * Finds the manifest protected inside one referenced stored suite.
	 *
	 * @param references the protected manifest paths
	 * @param directory the stored suite directory
	 * @return the protected manifest filename
	 * @throws IOException if no protected manifest belongs to the directory
	 */
	private static Path referencedManifestName(Set<Path> references, Path directory) throws IOException
	{
		Path reference = references.stream().map(path -> path.toAbsolutePath().normalize()).
			filter(path -> path.equals(directory) || path.getParent().equals(directory)).findFirst().
			orElseThrow(() -> new IOException("No stored suite manifest for " + directory));
		if (reference.equals(directory))
			return Path.of("suite.json");
		return reference.getFileName();
	}

	/**
	 * Reads the time used to retain the newest unreferenced suites.
	 *
	 * @param path the stored suite directory
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
	 * Copies every safe suite entry into an initially empty destination directory.
	 *
	 * @param source the source suite directory
	 * @param destination the destination directory
	 * @throws IOException if a suite entry is unsafe or cannot be copied
	 */
	private static void copy(Path source, Path destination) throws IOException
	{
		Files.walkFileTree(source, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException
			{
				if (Files.isSymbolicLink(directory))
					throw new IOException("Evaluation suite contains a symbolic-link directory: " + directory);
				Files.createDirectories(destination.resolve(source.relativize(directory)));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				if (!attributes.isRegularFile() || Files.isSymbolicLink(file))
					throw new IOException("Evaluation suite contains an unsupported entry: " + file);
				Files.copy(file, destination.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Confirms that a stored suite still has the content named by its directory.
	 *
	 * @param directory the stored suite directory
	 * @param fingerprint the required content hash
	 * @param manifestName the required manifest filename
	 * @throws IOException if the suite is unsafe, incomplete, or changed
	 */
	private static void verify(Path directory, String fingerprint, Path manifestName) throws IOException
	{
		if (!Files.isRegularFile(directory.resolve(manifestName), LinkOption.NOFOLLOW_LINKS))
		{
			throw new IOException("Stored evaluation suite does not match its content address: " + directory);
		}
		verifyStoredSuite(directory, fingerprint);
	}

	/**
	 * Confirms that a stored suite has the content named by its directory.
	 *
	 * @param directory the stored suite directory
	 * @param fingerprint the required content hash
	 * @throws IOException if the suite is unsafe or changed
	 */
	private static void verifyStoredSuite(Path directory, String fingerprint) throws IOException
	{
		if (!isFingerprint(fingerprint) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) ||
			Files.isSymbolicLink(directory) || !fingerprint(directory).equals(fingerprint))
		{
			throw new IOException("Stored evaluation suite does not match its content address: " + directory);
		}
	}

	/**
	 * Determines whether a directory name is a SHA-256 content address.
	 *
	 * @param value the directory name to inspect
	 * @return whether the name is a lowercase hexadecimal SHA-256 digest
	 */
	private static boolean isFingerprint(String value)
	{
		return value.matches("[0-9a-f]{64}");
	}

	/**
	 * Computes a stable hash of every suite-relative filename and its bytes.
	 *
	 * @param directory the suite directory
	 * @return the lowercase hexadecimal SHA-256 value
	 * @throws IOException if the suite cannot be read or SHA-256 is unavailable
	 */
	private static String fingerprint(Path directory) throws IOException
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (Stream<Path> paths = Files.walk(directory))
			{
				for (Path file : paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).
					sorted(Comparator.naturalOrder()).toList())
				{
					digest.update(directory.relativize(file).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
					digest.update(Files.readAllBytes(file));
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
	 * Reports one immutable-suite collection decision.
	 *
	 * @param collected the suite directories selected for collection
	 * @param referencedArtifacts the protected suite count
	 * @param retainedUnreferencedArtifacts the retained unreferenced suite count
	 * @param dryRun whether no suite was deleted
	 */
	public record Collection(List<Path> collected, int referencedArtifacts, int retainedUnreferencedArtifacts,
		boolean dryRun)
	{
		/**
		 * Validates the reported collection decision.
		 *
		 * @param collected the suite directories selected for collection
		 * @param referencedArtifacts the protected suite count
		 * @param retainedUnreferencedArtifacts the retained unreferenced suite count
		 * @param dryRun whether no suite was deleted
		 */
		public Collection
		{
			Objects.requireNonNull(collected, "collected must not be null");
			if (referencedArtifacts < 0)
				throw new IllegalArgumentException("referencedArtifacts must not be negative");
			if (retainedUnreferencedArtifacts < 0)
				throw new IllegalArgumentException("retainedUnreferencedArtifacts must not be negative");
		}
	}
}
