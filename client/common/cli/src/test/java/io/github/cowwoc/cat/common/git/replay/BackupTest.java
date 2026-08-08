package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.initializeRepository;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			Path manifest = repository.resolve("manifest.json");
			Backup backups = new Backup(git);

			String backup = backups.create(manifest);

			requireThat(backups.verify(manifest), "verifiedBackup").isEqualTo(backup);
			requireThat(backups.delete(manifest), "deletedBackup").isEqualTo(backup);
			requireThat(git.lines("for-each-ref", "--format=%(refname)", "refs/heads/" + backup), "backupReferences").
			isEmpty();
		}
	}
}
