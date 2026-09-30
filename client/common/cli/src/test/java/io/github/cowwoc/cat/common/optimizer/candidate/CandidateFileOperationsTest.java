package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.testng.annotations.Test;

/** Tests the JDK file boundary used by candidate delivery. */
public final class CandidateFileOperationsTest
{
	private final CandidateFileOperations operations = new JdkCandidateFileOperations();

	/** Creates the test class. */
	public CandidateFileOperationsTest()
	{
	}

	/** A new stage stores exact bytes and a second creation cannot replace it. */
	@Test
	public void stagesFreshContentWithoutReplacement() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("candidate-stage-"));
			Path stage = directory.resolve("stage");
			byte[] bytes = {0, (byte) 0xff, 42};
			operations.writeAndForceNew(stage, bytes);
			requireThat(Files.readAllBytes(stage), "stageBytes").isEqualTo(bytes);
			requireThat(operations.inspect(stage).contentSha256().orElseThrow(), "stageHash").isEqualTo(hash(bytes));
			try
			{
				operations.writeAndForceNew(stage, new byte[]{7});
				throw new AssertionError("Expected stage collision");
			}
			catch (FileAlreadyExistsException _)
			{
				requireThat(Files.readAllBytes(stage), "unchangedStage").isEqualTo(bytes);
			}
		}
	}

	/** Atomic capture moves a target to a private sibling and reports the moved bytes. */
	@Test
	public void capturesTargetIntoAbsentPrivateSibling() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("candidate-capture-"));
			Path target = directory.resolve("target");
			Path privatePath = directory.resolve("private");
			byte[] bytes = "original".getBytes(StandardCharsets.UTF_8);
			Files.write(target, bytes);
			PathInspection captured = operations.captureAtomically(target, privatePath);
			requireThat(Files.exists(target), "targetExists").isFalse();
			requireThat(Files.readAllBytes(privatePath), "capturedBytes").isEqualTo(bytes);
			requireThat(captured.contentSha256().orElseThrow(), "capturedHash").isEqualTo(hash(bytes));
		}
	}

	/** Capture refuses a private-path collision without changing either pathname. */
	@Test
	public void refusesCapturePrivateCollision() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("candidate-capture-collision-"));
			Path target = directory.resolve("target");
			Path privatePath = directory.resolve("private");
			Files.write(target, new byte[]{1});
			Files.write(privatePath, new byte[]{2});
			try
			{
				operations.captureAtomically(target, privatePath);
				throw new AssertionError("Expected private collision");
			}
			catch (FileAlreadyExistsException _)
			{
				requireThat(Files.readAllBytes(target), "targetBytes").isEqualTo(new byte[]{1});
				requireThat(Files.readAllBytes(privatePath), "privateBytes").isEqualTo(new byte[]{2});
			}
		}
	}

	/** Missing observed targets cannot create private evidence. */
	@Test
	public void refusesMissingCaptureTarget() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("candidate-capture-missing-"));
			Path target = directory.resolve("missing");
			Path privatePath = directory.resolve("private");
			try
			{
				operations.captureAtomically(target, privatePath);
				throw new AssertionError("Expected missing target refusal");
			}
			catch (NoSuchFileException _)
			{
				requireThat(Files.exists(privatePath), "privateExists").isFalse();
			}
		}
	}

	/** Publishing verifies the source hash and preserves an existing target. */
	@Test
	public void linksOnlyMatchingSourceIntoAbsentTarget() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("candidate-link-"));
			Path source = directory.resolve("source");
			Path target = directory.resolve("target");
			byte[] bytes = {4, 5, 6};
			operations.writeAndForceNew(source, bytes);
			String expectedHash = hash(bytes);
			try
			{
				operations.linkNew(source, target, hash(new byte[]{9}));
				throw new AssertionError("Expected hash mismatch");
			}
			catch (IOException _)
			{
				requireThat(Files.exists(target), "targetExists").isFalse();
			}
			LinkPublication publication = operations.linkNew(source, target, expectedHash);
			requireThat(publication.expectedSha256(), "publishedHash").isEqualTo(expectedHash);
			requireThat(operations.sameFile(source, target), "sharedInode").isTrue();
			try
			{
				operations.linkNew(source, target, expectedHash);
				throw new AssertionError("Expected target collision");
			}
			catch (FileAlreadyExistsException _)
			{
				requireThat(Files.readAllBytes(target), "targetBytes").isEqualTo(bytes);
			}
		}
	}

	/** Inspection distinguishes missing and regular files and refuses a symbolic link. */
	@Test
	public void inspectsWithoutFollowingSymbolicLinks() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("candidate-inspect-"));
			Path target = directory.resolve("target");
			Path link = directory.resolve("link");
			requireThat(operations.inspect(target), "missingInspection").isEqualTo(
				new PathInspection(DeliveryPresence.ABSENT, Optional.empty(), Optional.empty()));
			Files.write(target, new byte[]{8});
			Files.createSymbolicLink(link, target);
			try
			{
				operations.inspect(link);
				throw new AssertionError("Expected symbolic-link refusal");
			}
			catch (IOException _)
			{
				requireThat(Files.readAllBytes(target), "targetBytes").isEqualTo(new byte[]{8});
			}
		}
	}

	/** A directory is outside the supported exact-byte file kind. */
	@Test
	public void refusesDirectoryInspection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("candidate-inspect-directory-"));
			try
			{
				operations.inspect(directory);
				throw new AssertionError("Expected directory refusal");
			}
			catch (IOException _)
			{
				requireThat(Files.isDirectory(directory), "directoryExists").isTrue();
			}
		}
	}

	/** Alias checks require live distinct regular pathnames. */
	@Test
	public void checksLiveDistinctRegularAliases() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("candidate-alias-"));
			Path first = directory.resolve("first");
			Path alias = directory.resolve("alias");
			Path different = directory.resolve("different");
			Files.write(first, new byte[]{1});
			Files.createLink(alias, first);
			Files.write(different, new byte[]{1});
			requireThat(operations.sameFile(first, alias), "alias").isTrue();
			requireThat(operations.sameFile(first, different), "different").isFalse();
			requireThat(operations.sameFile(first, first), "samePathname").isFalse();
			Files.delete(first);
			requireThat(operations.sameFile(first, first), "missingSamePathname").isFalse();
		}
	}

	/** Private cleanup leaves a user target alone and owned directories require emptiness. */
	@Test
	public void deletesOnlyNamedPrivateArtifacts() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("candidate-delete-"));
			Path owned = directory.resolve("owned");
			operations.createDirectoryNew(owned);
			Path privatePath = owned.resolve("private");
			Path target = directory.resolve("target");
			Files.write(target, new byte[]{3});
			Files.write(privatePath, new byte[]{2});
			operations.unlinkPrivate(privatePath);
			requireThat(Files.readAllBytes(target), "targetBytes").isEqualTo(new byte[]{3});
			Files.write(privatePath, new byte[]{2});
			operations.deletePrivate(privatePath);
			operations.deleteEmptyOwnedDirectory(owned);
			requireThat(Files.exists(owned), "ownedExists").isFalse();
		}
	}

	/** Nonempty owned directories are not recursively removed. */
	@Test
	public void refusesNonemptyDirectoryDeletion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("candidate-nonempty-"));
			Path owned = directory.resolve("owned");
			operations.createDirectoryNew(owned);
			Files.write(owned.resolve("child"), new byte[]{1});
			try
			{
				operations.deleteEmptyOwnedDirectory(owned);
				throw new AssertionError("Expected nonempty directory refusal");
			}
			catch (IOException _)
			{
				requireThat(Files.readAllBytes(owned.resolve("child")), "childBytes").isEqualTo(new byte[]{1});
			}
		}
	}

	/** A force request succeeds for an owned directory and reports a missing one. */
	@Test
	public void forcesDirectoryOrReportsFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("candidate-force-"));
			operations.forceDirectory(directory);
			try
			{
				operations.forceDirectory(directory.resolve("missing"));
				throw new AssertionError("Expected missing directory failure");
			}
			catch (NoSuchFileException _)
			{
				requireThat(Files.isDirectory(directory), "directoryExists").isTrue();
			}
		}
	}

	/**
	 * Computes the expected exact-byte SHA-256 for a fixture.
	 *
	 * @param bytes fixture bytes
	 * @return lowercase SHA-256
	 */
	private static String hash(byte[] bytes)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("SHA-256 unavailable", e);
		}
	}
}
