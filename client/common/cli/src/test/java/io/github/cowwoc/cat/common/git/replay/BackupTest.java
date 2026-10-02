package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.initializeRepository;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.testng.annotations.Test;

/**
 * Tests {@link Backup}.
 */
public final class BackupTest
{
	/**
	 * Creates the test fixture.
	 */
	public BackupTest()
	{
	}

	/**
	 * Verifies a replay backup is recorded, verified, and removed only through its manifest-local inventory.
	 *
	 * @throws IOException if the repository fixture or backup lifecycle fails
	 */
	@Test
	public void recordsAndDeletesBackupBranch() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			Path manifest = repository.resolve("manifest.json");
			Backup backups = new Backup(git);
			String sourceTip = git.lines("rev-parse", "HEAD").getFirst();
			git.lines("branch", "backup/unrelated");

			String backup = backups.create(manifest);

			requireThat(backup, "backup").isNotBlank();
			requireThat(backups.hasInventory(manifest), "recordedInventory").isTrue();
			Path inventory = manifest.resolveSibling("manifest.json.backups");
			requireThat(Files.readString(inventory), "inventory").isEqualTo(backup + " " + sourceTip +
				System.lineSeparator());
			requireThat(backups.verify(manifest), "verifiedBackup").isEqualTo(backup);
			git.lines("branch", "-D", backup);
			try
			{
				backups.delete(manifest);
				throw new AssertionError("Expected cleanup to reject the missing recorded backup");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("recorded backup branch is missing");
			}
			git.lines("branch", backup, sourceTip);
			requireThat(backups.delete(manifest), "deletedBackup").isEqualTo(backup);
			requireThat(git.lines("for-each-ref", "--format=%(refname)", "refs/heads/" + backup), "backupReferences").
			isEmpty();
			requireThat(git.lines("for-each-ref", "--format=%(refname:short)", "refs/heads/backup/unrelated"),
				"unrelatedBackup").isEqualTo(List.of("backup/unrelated"));
		}
	}
}
