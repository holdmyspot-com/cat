package com.holdmyspot.cat.common.optimizer.port;

import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

import java.io.IOException;
import java.util.Optional;

/**
 * Supplies the files of each fork that a campaign has run on. A confirmed revision can move a campaign onto a fork
 * that holds more files, and the candidates of earlier lineages stay overlays on the fork that they ran on.
 */
@FunctionalInterface
public interface ForkVersions
{
	/**
	 * Supplies no fork.
	 */
	ForkVersions NONE = _ -> Optional.empty();

	/**
	 * Looks up the files of a fork that the campaign has run on.
	 *
	 * @param identity the fork's {@link FileInventory#identity() identity}
	 * @return the fork's files, or empty if the campaign never ran on such a fork
	 * @throws NullPointerException if {@code identity} is null
	 * @throws IOException if the stored forks cannot be read
	 */
	Optional<FileInventory> find(Sha256 identity) throws IOException;
}
