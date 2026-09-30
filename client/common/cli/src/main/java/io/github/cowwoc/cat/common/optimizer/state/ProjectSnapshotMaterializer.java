package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.candidate.PromptSnapshot;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * Restores a content-addressed source snapshot to a stable, runner-readable project directory.
 */
final class ProjectSnapshotMaterializer
{
	/**
	 * Prevents construction.
	 */
	private ProjectSnapshotMaterializer()
	{
	}

	/**
	 * Materializes once and checks the complete project tree on every use.
	 *
	 * @param snapshot validated source contents
	 * @param campaignDirectory pinned campaign storage root
	 * @return the exact restored project root
	 * @throws IOException if a materialization is incomplete or conflicts with the snapshot
	 */
	static Path materialize(PromptSnapshot snapshot, Path campaignDirectory) throws IOException
	{
		Path parent = campaignDirectory.resolve("materialized-project-snapshots");
		try
		{
			Files.createDirectory(parent);
		}
		catch (FileAlreadyExistsException _)
		{
			if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Project snapshot directory is redirected: " + parent);
		}
		Path destination = parent.resolve(snapshot.id());
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
		{
			verify(snapshot, destination);
			return destination;
		}

		Path staging = Files.createTempDirectory(parent, "staging-");
		try
		{
			for (Map.Entry<Path, PromptSnapshot.Entry> entry : snapshot.entries().entrySet())
			{
				Path file = staging.resolve(entry.getKey());
				Files.createDirectories(file.getParent());
				Files.write(file, entry.getValue().bytes(), StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
			}
			verify(snapshot, staging);
			try
			{
				Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (FileAlreadyExistsException | DirectoryNotEmptyException _)
			{
				verify(snapshot, destination);
			}
			verify(snapshot, destination);
			return destination;
		}
		finally
		{
			if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS))
				TemporaryDirectories.deleteRecursively(staging);
		}
	}

	/**
	 * Rechecks every file and rejects extra files, links, and redirected directories.
	 *
	 * @param snapshot expected immutable contents
	 * @param directory proposed materialization
	 * @throws IOException if its tree differs from the saved source
	 */
	private static void verify(PromptSnapshot snapshot, Path directory) throws IOException
	{
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Materialized project is not a real directory: " + directory);
		PromptSnapshot observed = PromptSnapshot.capture(directory, List.of(Path.of(".")));
		if (!observed.id().equals(snapshot.id()))
			throw new IOException("Materialized project conflicts with its retained snapshot: " + directory);
	}
}
