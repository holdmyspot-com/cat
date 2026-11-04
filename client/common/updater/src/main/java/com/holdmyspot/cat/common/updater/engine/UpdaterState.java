package com.holdmyspot.cat.common.updater.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.updater.trust.HarnessIdentifier;
import java.nio.file.Path;

/**
 * Locates one harness's updater state. Constructing this value creates nothing; mutating operations create the
 * directory under the updater lock. The caller supplies the protected updater data directory explicitly.
 *
 * @param directory the absolute directory containing this harness's state
 */
public record UpdaterState(Path directory)
{
	/**
	 * Locates state below an updater data directory.
	 *
	 * @param dataDirectory the protected updater data directory
	 * @param harness       a valid provider identifier
	 * @return the state location
	 * @throws NullPointerException if either argument is null
	 * @throws IllegalArgumentException if {@code harness} is not a valid identifier
	 */
	public static UpdaterState of(Path dataDirectory, String harness)
	{
		requireThat(dataDirectory, "dataDirectory").isNotNull();
		requireThat(harness, "harness").isNotNull();
		if (!HarnessIdentifier.SYNTAX.matcher(harness).matches())
			throw new IllegalArgumentException("The harness identifier is not valid: " + harness);
		return new UpdaterState(dataDirectory.toAbsolutePath().normalize().resolve("state").resolve(harness));
	}

	/**
	 * Creates a state location without filesystem writes.
	 *
	 * @param directory the absolute, normalized directory
	 * @throws NullPointerException if {@code directory} is null
	 * @throws IllegalArgumentException if {@code directory} is relative or not normalized
	 */
	public UpdaterState
	{
		requireThat(directory, "directory").isAbsolute().isEqualTo(directory.normalize(), "directory.normalize()");
	}

	/**
	 * Returns the exclusive-operation lock file.
	 *
	 * @return the lock path
	 */
	public Path lock()
	{
		return directory.resolve("lock");
	}

	/**
	 * Returns the journal of an unfinished activation or undo.
	 *
	 * @return the journal path
	 */
	public Path activation()
	{
		return directory.resolve("activation.json");
	}

	/**
	 * Returns the journal of the last successful activation.
	 *
	 * @return the retained journal path
	 */
	public Path lastActivation()
	{
		return directory.resolve("last-activation.json");
	}

	/**
	 * Returns the append-only operation log.
	 *
	 * @return the log path
	 */
	public Path log()
	{
		return directory.resolve("update.log");
	}

	/**
	 * Returns the file that records the permission entries that setup added to the harness's configuration.
	 *
	 * @return the file, beside the harness's state directory
	 */
	public Path permissions()
	{
		return directory.resolveSibling("permissions").resolve(directory.getFileName() + ".json");
	}

	/**
	 * Returns backups of files that CAT owned before activation.
	 *
	 * @return the backup directory
	 */
	public Path previousFiles()
	{
		return directory.resolve("previous-files");
	}
}
