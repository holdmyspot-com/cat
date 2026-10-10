package io.github.cowwoc.cat.claude.secrets;

import io.github.cowwoc.cat.common.optimizer.contract.ProfileCatalog;

import java.io.IOException;
import java.io.InputStream;

/**
 * Exposes how the Claude profile inventory parses its resource to the module's tests.
 */
@FunctionalInterface
public interface ClaudeProfileInventoryAccess
{
	/**
	 * Parses the content of the inventory resource.
	 *
	 * @param in the content
	 * @return the inventory
	 * @throws NullPointerException if {@code in} is null
	 * @throws IOException if the content cannot be read or departs from the resource format
	 */
	ProfileCatalog parse(InputStream in) throws IOException;
}
