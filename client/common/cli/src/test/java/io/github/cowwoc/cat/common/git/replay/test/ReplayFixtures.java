package io.github.cowwoc.cat.common.git.replay.test;

import io.github.cowwoc.cat.common.git.replay.GitTopicReplayEditor;
import io.github.cowwoc.cat.common.git.replay.History;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the build-supplied CAT temporary root for topic-replay fixtures.
 */
final class ReplayFixtures
{
	/**
	 * Prevents construction of static fixture operations.
	 */
	private ReplayFixtures()
	{
	}

	/**
	 * Returns the normalized CAT temporary root supplied by the build.
	 *
	 * @return the build's {@code cat.work.temp} root
	 * @throws IOException if the temporary root cannot be created
	 */
	static Path temporaryRoot() throws IOException
	{
		Path root = Path.of(System.getProperty("cat.work.temp")).toAbsolutePath().normalize();
		Files.createDirectories(root);
		return root;
	}

	/**
	 * Returns an editor that keeps its state in a temporary directory inside its repository.
	 *
	 * @param repository the repository
	 * @return the editor
	 */
	static GitTopicReplayEditor editor(Path repository)
	{
		return new GitTopicReplayEditor(repository, repository.resolve(".cat/work/temp"));
	}

	/**
	 * Supplies one captured path addition to CAT's inventory interpretation.
	 *
	 * @param path the changed product path
	 * @param blob its immutable resulting contents identity
	 * @return the captured path addition
	 */
	static History.InventoryEntry addedPath(String path, String blob)
	{
		return new History.InventoryEntry("source:" + path, "source", path, "000000", "0".repeat(40), "100644", blob);
	}
}
