package com.holdmyspot.cat.common.git.replay.test;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.git.replay.GitTopicReplayEditor;
import com.holdmyspot.cat.common.git.replay.History;
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
	 * Returns an editor that keeps its state in a temporary directory inside its repository.
	 *
	 * @param repository the repository
	 * @return the editor
	 */
	static GitTopicReplayEditor editor(Path repository)
	{
		return new GitTopicReplayEditor(repository,
			repository.resolve(".cat/sessions/1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed/temp"), Yielding.fileReplacer());
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
