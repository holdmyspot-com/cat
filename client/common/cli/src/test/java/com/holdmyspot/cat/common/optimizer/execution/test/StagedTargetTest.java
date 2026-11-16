package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.execution.SnapshotMismatchException;
import com.holdmyspot.cat.common.optimizer.execution.StagedTarget;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.RegularFile;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.secrets.StagedTargetSecrets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests {@link StagedTarget} against the content recorded by a frozen inventory.
 */
public final class StagedTargetTest
{
	private static final Set<PosixFilePermission> PERMISSIONS = Set.copyOf(
		PosixFilePermissions.fromString("rw-r--r--"));

	/**
	 * A matching regular target returns its recorded bytes.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void readsMatchingTarget() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path staged = cleanup.add(Files.createTempDirectory("staged-target"));
			byte[] bytes = "prompt".getBytes(UTF_8);
			Files.write(staged.resolve("PROMPT.md"), bytes);
			FileInventory inventory = inventory(staged, Map.of(Path.of("PROMPT.md"), regular(bytes)));

			FileContent content = StagedTarget.read(staged, inventory, Path.of("PROMPT.md"));

			requireThat(content.bytes(), "content").isEqualTo(bytes);
		}
	}

	/**
	 * A zero-byte control target is valid when the snapshot records its empty content.
	*
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void readsMatchingEmptyTarget() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path staged = cleanup.add(Files.createTempDirectory("empty-staged-target"));
			Files.createFile(staged.resolve("PROMPT.md"));
			FileInventory inventory = inventory(staged, Map.of(Path.of("PROMPT.md"), regular(new byte[0])));

			FileContent content = StagedTarget.read(staged, inventory, Path.of("PROMPT.md"));

			requireThat(content.bytes(), "content").isEmpty();
		}
	}

	/**
	 * A target path that resolves through a recorded symbolic link uses the link destination's recorded content.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void followsRecordedSymbolicLink() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path staged = cleanup.add(Files.createTempDirectory("linked-staged-target"));
			byte[] bytes = "linked prompt".getBytes(UTF_8);
			Files.write(staged.resolve("actual.md"), bytes);
			Files.createSymbolicLink(staged.resolve("PROMPT.md"), Path.of("actual.md"));
			FileInventory inventory = inventory(staged, Map.of(Path.of("PROMPT.md"), new SymbolicLink(Path.of("actual.md")),
				Path.of("actual.md"), regular(bytes)));

			FileContent content = StagedTarget.read(staged, inventory, Path.of("PROMPT.md"));

			requireThat(content.bytes(), "content").isEqualTo(bytes);
		}
	}

	/**
	 * Changed staged bytes fail with the staged target path in the diagnostic.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test(expectedExceptions = SnapshotMismatchException.class,
		expectedExceptionsMessageRegExp = ".*PROMPT\\.md.*snapshot.*")
	public void refusesChangedBytes() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path staged = cleanup.add(Files.createTempDirectory("changed-staged-target"));
			Files.writeString(staged.resolve("PROMPT.md"), "changed", UTF_8);
			FileInventory inventory = inventory(staged,
				Map.of(Path.of("PROMPT.md"), regular("recorded".getBytes(UTF_8))));

			StagedTarget.read(staged, inventory, Path.of("PROMPT.md"));
		}
	}

	/**
	 * A missing staged target is an integrity mismatch and retains its filesystem cause.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test(expectedExceptions = SnapshotMismatchException.class,
		expectedExceptionsMessageRegExp = ".*PROMPT\\.md.*snapshot.*")
	public void refusesMissingTarget() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path staged = cleanup.add(Files.createTempDirectory("missing-staged-target"));
			FileInventory inventory = inventory(staged,
				Map.of(Path.of("PROMPT.md"), regular("recorded".getBytes(UTF_8))));

			StagedTarget.read(staged, inventory, Path.of("PROMPT.md"));
		}
	}

	/**
	 * A directory at the target path is rejected as a nonregular staged target.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test(expectedExceptions = SnapshotMismatchException.class)
	public void refusesNonregularTarget() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path staged = cleanup.add(Files.createTempDirectory("nonregular-staged-target"));
			Files.createDirectory(staged.resolve("PROMPT.md"));
			FileInventory inventory = inventory(staged,
				Map.of(Path.of("PROMPT.md"), regular("recorded".getBytes(UTF_8))));

			StagedTarget.read(staged, inventory, Path.of("PROMPT.md"));
		}
	}

	/**
	 * Existing bytes absent from the frozen inventory are rejected.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test(expectedExceptions = SnapshotMismatchException.class)
	public void refusesUnrecordedTarget() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path staged = cleanup.add(Files.createTempDirectory("unrecorded-staged-target"));
			Files.writeString(staged.resolve("PROMPT.md"), "prompt", UTF_8);
			FileInventory inventory = inventory(staged, Map.of());

			StagedTarget.read(staged, inventory, Path.of("PROMPT.md"));
		}
	}

	/**
	 * The attribute-read failure classifier preserves an ordinary {@link IOException} unchanged.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*PROMPT.*")
	public void preservesOrdinaryReadFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path staged = cleanup.add(Files.createTempFile("staged-target-file", ".tmp"));
			Path file = staged.resolve("PROMPT.md");
			IOException failure = new IOException("Cannot read " + file);

			try
			{
				throw StagedTargetSecrets.getStagedTargetAccess().classifyReadFailure(file, failure);
			}
			catch (IOException e)
			{
				requireThat(e, "e").isReferenceEqualTo(failure, "failure").isNotInstanceOf(SnapshotMismatchException.class);
				throw e;
			}
		}
	}

	/**
	 * Creates an inventory rooted at the staged directory.
	 *
	 * @param root the absolute project root
	 * @param entries the recorded paths
	 * @return the inventory
	 */
	private static FileInventory inventory(Path root, Map<Path, FileEntry> entries)
	{
		return new FileInventory(root, entries);
	}

	/**
	 * Records the bytes and permissions of one regular file.
	 *
	 * @param bytes the file's bytes
	 * @return its inventory entry
	 */
	private static RegularFile regular(byte[] bytes)
	{
		return new RegularFile(Sha256.of(bytes), PERMISSIONS);
	}
}
