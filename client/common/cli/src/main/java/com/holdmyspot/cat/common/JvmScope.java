package com.holdmyspot.cat.common;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Owns the shared values of one CAT process.
 */
public interface JvmScope extends AutoCloseable
{
	/**
	 * Returns this process's JSON mapper.
	 *
	 * @return the shared mapper
	 * @throws IllegalStateException if this scope is closed
	 */
	JsonMapper json();

	/**
	 * Returns this process's YAML mapper.
	 *
	 * @return the shared mapper
	 * @throws IllegalStateException if this scope is closed
	 */
	YAMLMapper yaml();

	/**
	 * Returns this process's clock.
	 *
	 * @return the shared clock
	 * @throws IllegalStateException if this scope is closed
	 */
	Clock clock();

	/**
	 * Returns the absolute, normalized directory for process temporary files, creating it when necessary.
	 * The process owns its children; closing this scope does not remove the shared directory.
	 *
	 * @return the temporary directory
	 * @throws IllegalStateException if this scope is closed
	 * @throws IOException if the directory cannot be created
	 */
	Path temporaryDirectory() throws IOException;

	/**
	 * Indicates whether this scope is closed.
	 *
	 * @return {@code true} if closed
	 */
	boolean isClosed();

	/**
	 * Prevents further service access. Closing an already closed scope has no effect.
	 */
	@Override
	void close();
}
