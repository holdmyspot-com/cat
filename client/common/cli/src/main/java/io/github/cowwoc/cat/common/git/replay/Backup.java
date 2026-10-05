package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Creates, verifies, and removes the recorded recovery branch for one topic replay.
 */
final class Backup
{
	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").
		withZone(ZoneOffset.UTC);
	private final Git git;
	private final Clock clock;

	/**
	 * Creates a backup lifecycle owner for one repository.
	 *
	 * @param git the repository access boundary
	 * @param clock the clock that supplies the time, which each backup branch name shows in UTC
	 */
	Backup(Git git, Clock clock)
	{
		this.git = git;
		this.clock = clock;
	}

	/**
	 * Creates one backup branch at the current repository tip and records it beside the replay manifest.
	 *
	 * @param manifestFile the replay manifest
	 * @return the created backup branch name
	 * @throws IOException if a backup was already recorded or Git cannot create the branch
	 */
	String create(Path manifestFile) throws IOException
	{
		Path inventory = inventory(manifestFile);
		if (Files.exists(inventory))
		{
			throw new IOException("Topic-replay backup inventory already exists: " + inventory + "; reuse its recorded " +
				"backup for recovery or finalize that replay before creating another backup");
		}
		String tip = git.run("rev-parse", "HEAD").trim();
		String prefix = "backup/topic-replay-" + TIMESTAMP.format(clock.instant());
		String backup = uniqueBranch(prefix);
		git.lines("branch", backup, tip);
		Files.writeString(inventory, backup + " " + tip + System.lineSeparator(), UTF_8);
		return backup;
	}

	/**
	 * Returns whether the manifest has any recorded backup inventory.
	 *
	 * @param manifestFile the replay manifest
	 * @return whether a backup inventory is present
	 */
	boolean hasInventory(Path manifestFile)
	{
		return Files.exists(inventory(manifestFile));
	}

	/**
	 * Returns the existing recorded backup branch after confirming its manifest record and Git reference are usable.
	 *
	 * @param manifestFile the replay manifest
	 * @return the existing backup branch
	 * @throws IOException if the recorded backup is absent or malformed
	 */
	String verify(Path manifestFile) throws IOException
	{
		Path inventory = inventory(manifestFile);
		String[] fields = record(inventory).split(" ", 2);
		if (fields.length != 2 || !isFullHash(fields[1]) || !branchExists(fields[0]))
		{
			throw new IOException("Topic-replay backup inventory is unusable: " + inventory + "; restore or inspect " +
				"the repository, then begin a new replay manifest");
		}
		return fields[0];
	}

	/**
	 * Deletes the recorded branch after its caller has verified replay finalization.
	 *
	 * @param manifestFile the replay manifest
	 * @return the deleted backup branch
	 * @throws IOException if the inventory is missing, malformed, or its branch cannot be removed
	 */
	String delete(Path manifestFile) throws IOException
	{
		Path inventory = inventory(manifestFile);
		if (!Files.isRegularFile(inventory))
		{
			throw new IOException("Topic-replay backup inventory is missing: " + inventory + "; do not delete backup " +
				"branches manually because this command can remove only a recorded branch");
		}
		String[] fields = record(inventory).split(" ", 2);
		if (fields.length != 2 || !isFullHash(fields[1]))
		{
			throw new IOException("Topic-replay backup inventory contains an invalid branch record: " + inventory +
				"; restore the recorded backup manually and begin a new replay manifest");
		}
		String backup = fields[0];
		if (!branchExists(backup))
		{
			throw new IOException("Topic-replay recorded backup branch is missing: " + backup + "; preserve the manifest " +
				"and investigate the repository before accepting this rewrite");
		}
		git.lines("branch", "-D", backup);
		if (branchExists(backup))
			throw new IOException("Topic-replay could not remove its backup branch: " + backup);
		return backup;
	}

	/**
	 * Returns the inventory stored alongside one replay manifest.
	 *
	 * @param manifestFile the replay manifest
	 * @return the manifest-local inventory
	 */
	private Path inventory(Path manifestFile)
	{
		return manifestFile.resolveSibling(manifestFile.getFileName() + ".backups");
	}

	/**
	 * Chooses an unused local branch name below one timestamped prefix.
	 *
	 * @param prefix the timestamped backup prefix
	 * @return an unused branch name
	 * @throws IOException if Git cannot inspect branches
	 */
	private String uniqueBranch(String prefix) throws IOException
	{
		String backup = prefix;
		int suffix = 1;
		while (branchExists(backup))
		{
			backup = prefix + "-" + suffix;
			suffix += 1;
		}
		return backup;
	}

	/**
	 * Reads the one branch-and-tip record stored in an inventory.
	 *
	 * @param inventory the manifest-local inventory
	 * @return the single record
	 * @throws IOException if the inventory does not contain exactly one record
	 */
	private String record(Path inventory) throws IOException
	{
		try (BufferedReader reader = Files.newBufferedReader(inventory, UTF_8))
		{
			String record = reader.readLine();
			if (record == null || reader.readLine() != null)
			{
				throw new IOException("Topic-replay backup inventory must contain exactly one branch and tip: " + inventory);
			}
			return record;
		}
	}

	/**
	 * Returns whether a local branch with an exact name exists.
	 *
	 * @param branch the local branch name
	 * @return whether the branch exists
	 * @throws IOException if Git cannot inspect local references
	 */
	private boolean branchExists(String branch) throws IOException
	{
		return !git.lines("for-each-ref", "--format=%(refname)", "refs/heads/" + branch).isEmpty();
	}

	/**
	 * Returns whether a value is a full Git object hash.
	 *
	 * @param value the candidate hash
	 * @return whether the value is a lowercase 40-digit hexadecimal hash
	 */
	private boolean isFullHash(String value)
	{
		return value.matches("[0-9a-f]{40}");
	}
}
