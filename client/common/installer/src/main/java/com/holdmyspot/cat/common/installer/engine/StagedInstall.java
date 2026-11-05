package com.holdmyspot.cat.common.installer.engine;

import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Installs a plugin from its extracted archive, once the archive has been verified against its release's signed
 * manifest.
 */
@FunctionalInterface
public interface StagedInstall
{
	/**
	 * Installs the plugin.
	 *
	 * @param plugin the extracted plugin folder, the archive's top directory, which is deleted once the install returns
	 * @return the outcome
	 * @throws IOException    if the install fails
	 * @throws TrustException if the install is refused
	 */
	UpdateResult install(Path plugin) throws IOException, TrustException;
}
