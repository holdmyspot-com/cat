package com.holdmyspot.cat.common.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.workspace.StagedSources;
import com.holdmyspot.cat.common.optimizer.workspace.WorkspaceException;

import java.nio.file.Path;
import java.util.List;

/**
 * Exposes the candidate file placements that {@link StagedSources} resolves from a recorded inventory.
 */
@FunctionalInterface
public interface StagedSourcesAccess
{
	/**
	 * A regular-file destination and the recorded source whose candidate bytes and mode it receives.
	 *
	 * @param destination the file's path in the staged source
	 * @param source the resolved recorded file's path in the inventory
	 */
	record Source(Path destination, Path source)
	{
		/**
		 * Creates a placement from a staged destination and its resolved recorded source.
		 *
		 * @param destination the file's path in the staged source
		 * @param source the resolved recorded file's path in the inventory
		 * @throws NullPointerException if either path is null
		 */
		public Source
		{
			requireThat(destination, "destination").isNotNull();
			requireThat(source, "source").isNotNull();
		}
	}

	/**
	 * Resolves the regular files that an inventory places after replacing symbolic links with their target files.
	 *
	 * @param inventory the recorded candidate inventory
	 * @return immutable file placements in staged-tree order, preserving duplicate destinations
	 * @throws NullPointerException if {@code inventory} is null
	 * @throws WorkspaceException if a symbolic link forms a cycle or leads to a missing target
	 */
	List<Source> sources(FileInventory inventory) throws WorkspaceException;
}
