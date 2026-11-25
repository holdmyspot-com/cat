package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Finds the folders of the CAT plugin that a harness has installed, which are the only copies whose launchers start
 * sessions: the installer adds the sandbox exception that lets the installed plugin's request server run outside a
 * session's sandbox, and no other copy of CAT, such as an unpacked build or a copied runtime, has it.
 */
@FunctionalInterface
public interface InstalledCopies
{
	/**
	 * Returns the installed copies of CAT.
	 *
	 * @return the absolute folder of each installed copy, in the harness's record order; empty if the harness has no
	 *   installed CAT plugin
	 * @throws IOException if the harness's install records exist but cannot be read
	 */
	List<Path> find() throws IOException;
}
