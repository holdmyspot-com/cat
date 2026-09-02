package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimization.development.CandidateBundle;

import java.io.IOException;
import java.io.InputStream;
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
import java.nio.file.attribute.FileTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Stores each verified packaged runner and prompt bundle once under its content address.
 */
public final class RunnerArtifactStore
{
	private static final String PUBLICATION_LOCK = ".publication.lock";
	private static final String ARTIFACT_DIRECTORY = "optimization-runner-artifacts";

	/**
	 * Prevents construction of this static utility.
	 */
	private RunnerArtifactStore()
	{
	}

	/**
	 * Retains one verified runner artifact under the CAT work root and returns its content-addressed location.
	 *
	 * @param workRoot the CAT-owned root for reusable workflow artifacts
	 * @param source the verified packaged runner artifact
	 * @return the immutable content-addressed artifact directory
	 * @throws IOException if the source is unsafe, changes during publication, or cannot be retained
	 */
	public static Path retain(Path workRoot, Path source) throws IOException
	{
		return retain(workRoot, source, null);
	}

	/**
	 * Retains one runner artifact after replacing its selected rules with one immutable candidate bundle.
	 *
	 * @param workRoot the CAT work root that owns reusable input copies
	 * @param source the verified packaged runner artifact
	 * @param candidate the candidate bundle, or {@code null} when the packaged rules are the candidate
	 * @return the immutable content-addressed artifact directory
	 * @throws IOException if the source or candidate is unsafe, changes during publication, or cannot be retained
	 */
	public static Path retain(Path workRoot, Path source, CandidateBundle.Candidate candidate)
		throws IOException
	{
		Path root = workRoot.toAbsolutePath().normalize().resolve(ARTIFACT_DIRECTORY);
		Path artifact = source.toAbsolutePath().normalize();
		if (!Files.isDirectory(artifact, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(artifact))
			throw new IOException("Runner artifact is not a regular directory: " + artifact);
		Files.createDirectories(root);
		try (FileChannel channel = FileChannel.open(root.resolve(PUBLICATION_LOCK), StandardOpenOption.CREATE,
			StandardOpenOption.WRITE);
			FileLock lock = channel.lock())
		{
			if (!lock.isValid())
				throw new IOException("Cannot lock runner artifact publication: " + root);
			Path staging = Files.createTempDirectory(root, ".candidate-");
			try
			{
				copy(artifact, staging);
				if (candidate != null)
					CandidateBundle.apply(candidate, staging);
				String fingerprint = artifactFingerprint(staging);
				Path destination = root.resolve(fingerprint);
				if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
				{
					verifyStoredArtifact(destination, fingerprint);
					return destination;
				}
				verifyStoredArtifact(staging, fingerprint);
				return publish(staging, destination);
			}
			finally
			{
				if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS))
					TemporaryDirectories.deleteRecursively(staging);
			}
		}
	}

	/**
	 * Identifies unreferenced immutable artifacts that exceed the configured retained-artifact budget and optionally
	 * removes them.
	 *
	 * <p>
	 * References are supplied by the evaluation-record owner, because only that owner can determine which retained,
	 * resumable records still need an artifact. This store owns only content addressing, immutability verification, and
	 * safe deletion of copies it created.
	 *
	 * @param workRoot the CAT-owned root for reusable workflow artifacts
	 * @param referencedArtifacts immutable artifact paths named by retained evaluation records
	 * @param retainedUnreferencedBudget the number of newest unreferenced artifacts to retain
	 * @param dryRun whether to report candidates without deleting them
	 * @return the collected paths and the protection counts used for the decision
	 * @throws IOException if a reference or stored artifact is invalid, or collection cannot complete safely
	 */
	public static Collection collect(Path workRoot, Set<Path> referencedArtifacts, int retainedUnreferencedBudget,
		boolean dryRun) throws IOException
	{
		if (workRoot == null)
			throw new NullPointerException("workRoot must not be null");
		if (referencedArtifacts == null)
			throw new NullPointerException("referencedArtifacts must not be null");
		if (retainedUnreferencedBudget < 0)
			throw new IllegalArgumentException("retainedUnreferencedBudget must not be negative");
		Path root = workRoot.toAbsolutePath().normalize().resolve(ARTIFACT_DIRECTORY);
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS))
			return new Collection(List.of(), 0, 0, dryRun);
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
			throw new IOException("Runner artifact store is not a regular directory: " + root);
		try (FileChannel channel = FileChannel.open(root.resolve(PUBLICATION_LOCK), StandardOpenOption.CREATE,
			StandardOpenOption.WRITE); FileLock lock = channel.lock())
		{
			if (!lock.isValid())
				throw new IOException("Cannot lock runner artifact collection: " + root);
			Set<Path> references = referencedArtifacts.stream().map(path ->
			{
				if (path == null)
					throw new NullPointerException("referencedArtifacts must not contain null");
				return path.toAbsolutePath().normalize();
			}).collect(java.util.stream.Collectors.toUnmodifiableSet());
			for (Path reference : references)
				verifyReference(root, reference);
			List<StoredArtifact> unreferenced = new ArrayList<>();
			try (Stream<Path> entries = Files.list(root))
			{
				for (Path entry : entries.toList())
				{
					String name = entry.getFileName().toString();
					if (name.equals(PUBLICATION_LOCK))
						continue;
					if (name.startsWith("."))
					{
						if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry))
							throw new IOException("Runner artifact store contains an unsafe staging entry: " + entry);
						if (!dryRun)
							TemporaryDirectories.deleteRecursively(entry);
						continue;
					}
					if (!isFingerprint(name))
						throw new IOException("Runner artifact store contains an unknown entry: " + entry);
					verifyStoredArtifact(entry, name);
					if (!references.contains(entry.toAbsolutePath().normalize()))
						unreferenced.add(new StoredArtifact(entry, Files.getLastModifiedTime(entry)));
				}
			}
			unreferenced.sort(Comparator.comparing(StoredArtifact::modified).reversed().thenComparing(
				artifact -> artifact.path().toString()));
			List<Path> collected = new ArrayList<>();
			for (int index = retainedUnreferencedBudget; index < unreferenced.size(); ++index)
			{
				Path artifact = unreferenced.get(index).path();
				collected.add(artifact);
				if (!dryRun)
					TemporaryDirectories.deleteRecursively(artifact);
			}
			return new Collection(List.copyOf(collected), references.size(), Math.min(retainedUnreferencedBudget,
				unreferenced.size()), dryRun);
		}
	}

	/**
	 * Publishes one fully verified staging directory without exposing a partial artifact to another evaluation.
	 *
	 * @param staging the verified content to publish
	 * @param destination the initially absent content-addressed destination
	 * @return the normalized published path
	 * @throws IOException if publication cannot complete
	 */
	private static Path publish(Path staging, Path destination) throws IOException
	{
		try
		{
			return Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE).toAbsolutePath().normalize();
		}
		catch (java.nio.file.AtomicMoveNotSupportedException _)
		{
			return Files.move(staging, destination).toAbsolutePath().normalize();
		}
	}

	/**
	 * Verifies one retained evaluation reference names a present artifact in this store.
	 *
	 * @param root the store root
	 * @param reference the record-owned artifact path
	 * @throws IOException if the reference is outside this store or does not identify its immutable artifact
	 */
	private static void verifyReference(Path root, Path reference) throws IOException
	{
		Path parent = reference.getParent();
		if (!root.equals(parent) || !isFingerprint(reference.getFileName().toString()))
			throw new IOException("Evaluation reference is not an immutable runner artifact: " + reference);
		verifyStoredArtifact(reference, reference.getFileName().toString());
	}

	/**
	 * Determines whether one directory name is a SHA-256 content address.
	 *
	 * @param value the candidate directory name
	 * @return whether the value is a lowercase hexadecimal SHA-256 digest
	 */
	private static boolean isFingerprint(String value)
	{
		if (value.length() != 64)
			return false;
		for (int index = 0; index < value.length(); ++index)
		{
			char character = value.charAt(index);
			if (!(character >= '0' && character <= '9') && !(character >= 'a' && character <= 'f'))
				return false;
		}
		return true;
	}

	/**
	 * Verifies that a published artifact still represents the required immutable runtime.
	 *
	 * @param artifact the stored artifact directory
	 * @param fingerprint the expected runtime fingerprint
	 * @throws IOException if the stored artifact is unavailable or changed
	 */
	private static void verifyStoredArtifact(Path artifact, String fingerprint) throws IOException
	{
		if (!Files.isDirectory(artifact, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(artifact) ||
			!artifactFingerprint(artifact).equals(fingerprint))
		{
			throw new IOException("Stored runner artifact does not match its content address: " + artifact);
		}
	}

	/**
	 * Computes the content address for an immutable runner copy.
	 *
	 * <p>
	 * The runtime manifest proves the native runner, hooks, and registration match the package. Prompt files are
	 * intentionally absent from that runtime identity so a prompt revision remains comparable with its predecessor.
	 * They must nevertheless contribute to this store's address: a nested runner reads them, so reusing a copy with an
	 * older prompt bundle would make an evaluation execute guidance other than the assembled receipt records.
	 *
	 * @param artifact the packaged runner directory
	 * @return the address of the complete runner and prompt bundle
	 * @throws IOException if the artifact cannot be verified or read
	 */
	private static String artifactFingerprint(Path artifact) throws IOException
	{
		String runtimeFingerprint = RunnerRuntimeManifest.packagedFingerprint(artifact);
		String promptFingerprint = promptBundleFingerprint(artifact);
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update("CAT immutable runner artifact v1\n".getBytes(StandardCharsets.UTF_8));
			digest.update(runtimeFingerprint.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) '\n');
			digest.update(promptFingerprint.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Fingerprints every packaged prompt file that a nested runner can read.
	 *
	 * @param artifact the packaged runner directory
	 * @return the prompt-bundle fingerprint, or a fixed absence value for a non-packaging test fixture
	 * @throws IOException if a prompt artifact is unsafe or cannot be read
	 */
	private static String promptBundleFingerprint(Path artifact) throws IOException
	{
		List<Path> roots = List.of("agents", "rules", "skills").stream().map(artifact::resolve).
			filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
		if (roots.isEmpty())
			return "not-packaged";
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (Path root : roots)
			{
				try (Stream<Path> paths = Files.walk(root))
				{
					for (Path path : paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
						Files.isSymbolicLink(path)).sorted().toList())
					{
						updatePromptDigest(digest, artifact, path);
					}
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Adds one packaged prompt path and content to a bundle digest.
	 *
	 * @param digest the destination digest
	 * @param artifact the artifact root
	 * @param path the regular file or symbolic link to record
	 * @throws IOException if the path cannot be read
	 */
	private static void updatePromptDigest(MessageDigest digest, Path artifact, Path path) throws IOException
	{
		String relativePath = artifact.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
		digest.update(relativePath.getBytes(StandardCharsets.UTF_8));
		digest.update((byte) '\n');
		if (Files.isSymbolicLink(path))
		{
			digest.update("link\n".getBytes(StandardCharsets.UTF_8));
			digest.update(Files.readSymbolicLink(path).toString().getBytes(StandardCharsets.UTF_8));
			digest.update((byte) '\n');
			return;
		}
		digest.update("file\n".getBytes(StandardCharsets.UTF_8));
		try (InputStream input = Files.newInputStream(path))
		{
			byte[] buffer = new byte[8192];
			int count = input.read(buffer);
			while (count >= 0)
			{
				digest.update(buffer, 0, count);
				count = input.read(buffer);
			}
		}
		digest.update((byte) '\n');
	}

	/**
	 * Copies one packaged runtime while preserving only safe, artifact-local symbolic links.
	 *
	 * @param source the verified source artifact
	 * @param destination the initially empty staging directory
	 * @throws IOException if an entry is unsafe or cannot be copied
	 */
	private static void copy(Path source, Path destination) throws IOException
	{
		Files.walkFileTree(source, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException
			{
				Files.createDirectories(destination.resolve(source.relativize(directory)));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				Path copy = destination.resolve(source.relativize(file));
				if (Files.isSymbolicLink(file))
				{
					Path target = Files.readSymbolicLink(file);
					if (target.isAbsolute() || !file.getParent().resolve(target).normalize().startsWith(source))
						throw new IOException("Runner artifact link escapes its artifact: " + file);
					Files.createSymbolicLink(copy, target);
					return FileVisitResult.CONTINUE;
				}
				if (!attributes.isRegularFile())
					throw new IOException("Runner artifact contains an unsupported entry: " + file);
				Files.copy(file, copy, StandardCopyOption.COPY_ATTRIBUTES);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Associates one stored artifact with the time used to apply the unreferenced retention budget.
	 *
	 * @param path the immutable artifact directory
	 * @param modified the directory modification time observed while holding the store lock
	 */
	private record StoredArtifact(Path path, FileTime modified)
	{
	}

	/**
	 * Reports one immutable-runner collection decision.
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
