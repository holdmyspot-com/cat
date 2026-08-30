package io.github.cowwoc.cat.common.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates the immutable scenario-suite copy that an evaluation record retains for later trials.
 */
public final class SuiteSnapshot
{
	/**
	 * Supplies the shared fixture-copy and cleanup operations used while materializing a snapshot.
	 */
	public interface Access
	{
		/**
		 * Copies a complete fixture tree.
		 *
		 * @param source the source fixture tree
		 * @param destination the newly created fixture tree
		 * @throws IOException if the fixture cannot be copied
		 */
		void copyFixture(Path source, Path destination) throws IOException;

		/**
		 * Deletes a partially created fixture tree.
		 *
		 * @param root the fixture-tree root
		 * @throws IOException if cleanup fails
		 */
		void delete(Path root) throws IOException;
	}

	/**
	 * Prevents construction of this static snapshot owner.
	 */
	private SuiteSnapshot()
	{
	}

	/**
	 * Copies one validated scenario suite for an evaluation record.
	 *
	 * @param access fixture-copy and cleanup operations
	 * @param source the validated mutable scenario-suite root
	 * @param runPath the evaluation record whose sibling directory receives the copy
	 * @return the immutable copied scenario-suite root
	 * @throws IOException if the source is not a valid scenario suite or the copy cannot be created
	 */
	public static Path copy(Access access, Path source, Path runPath) throws IOException
	{
		SuiteContract.validate(source);
		Path parent = runPath.getParent();
		if (parent == null)
			throw new IOException("Output has no parent directory: " + runPath);
		Path destination = parent.resolve(runPath.getFileName() + ".suite");
		Path normalizedSource = source.toAbsolutePath().normalize();
		if (destination.toAbsolutePath().normalize().startsWith(normalizedSource))
		{
			Path sourceParent = normalizedSource.getParent();
			if (sourceParent == null)
				throw new IOException("Scenario suite has no parent directory: " + source);
			destination = Files.createTempDirectory(sourceParent, ".prompt-evaluation-suite-");
		}
		else
			Files.createDirectories(destination);
		try
		{
			access.copyFixture(source, destination);
			SuiteContract.validate(destination);
			return destination;
		}
		catch (IOException e)
		{
			access.delete(destination);
			throw e;
		}
	}
}
