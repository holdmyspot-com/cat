package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The observable outcome of one installer operation. The CLI writes enum names in lowercase and record names in
 * snake_case. Missing versions mean that no corresponding installation was recorded.
 *
 * @param result          the outcome and exit status
 * @param harness         the provider's identifier
 * @param version         the active version, or empty when absent
 * @param previousVersion the recorded previous version, or empty when absent
 * @param message         one sentence describing the outcome or recovery action
 * @param paths           affected paths, including every recovery path on an activation failure
 * @param consistent      indicates whether the provider's installation copies agree
 * @param keyIds          the policy's trusted key identifiers
 * @param aotCache        indicates whether the operation wrote the startup cache of every plugin directory that it
 *                        activated, or empty when the operation activated no plugin directory
 * @param exitDeletions   the folders to delete once the installer no longer needs its own files: the folders that
 *                        the running installer belongs to and that the operation left for deletion
 */
public record UpdateResult(Result result, String harness, Optional<String> version, Optional<String> previousVersion,
	String message, List<Path> paths, boolean consistent, List<String> keyIds, Optional<Boolean> aotCache,
	List<Path> exitDeletions)
{
	/**
	 * Creates an outcome of an operation that activated no plugin directory, copying the path and key lists.
	 *
	 * @param result          the outcome
	 * @param harness         the provider identifier
	 * @param version         active version, or empty
	 * @param previousVersion previous version, or empty
	 * @param message         user-facing explanation
	 * @param paths           affected paths
	 * @param consistent      indicates whether installation copies agree
	 * @param keyIds          trusted key identifiers
	 * @throws NullPointerException if any argument or list element is null
	 * @throws IllegalArgumentException if {@code harness} or {@code message} are blank
	 */
	public UpdateResult(Result result, String harness, Optional<String> version, Optional<String> previousVersion,
		String message, List<Path> paths, boolean consistent, List<String> keyIds)
	{
		this(result, harness, version, previousVersion, message, paths, consistent, keyIds, Optional.empty(),
			List.of());
	}

	/**
	 * Creates an outcome, copying the path and key lists.
	 *
	 * @param result          the outcome
	 * @param harness         the provider identifier
	 * @param version         active version, or empty
	 * @param previousVersion previous version, or empty
	 * @param message         user-facing explanation
	 * @param paths           affected paths
	 * @param consistent      indicates whether installation copies agree
	 * @param keyIds          trusted key identifiers
	 * @param aotCache        indicates whether every activated plugin directory got its startup cache, or empty
	 * @param exitDeletions   the folders to delete once the installer no longer needs its own files
	 * @throws NullPointerException if any argument or list element is null
	 * @throws IllegalArgumentException if {@code harness} or {@code message} are blank
	 */
	public UpdateResult
	{
		requireThat(result, "result").isNotNull();
		requireThat(harness, "harness").isNotBlank();
		requireThat(version, "version").isNotNull();
		requireThat(previousVersion, "previousVersion").isNotNull();
		requireThat(message, "message").isNotBlank();
		paths = List.copyOf(paths);
		keyIds = List.copyOf(keyIds);
		requireThat(aotCache, "aotCache").isNotNull();
		exitDeletions = List.copyOf(exitDeletions);
	}

	/**
	 * Returns this outcome with the result of writing the startup caches of the plugin directories that it activated.
	 *
	 * @param written {@code true} if every activated plugin directory got its startup cache
	 * @return the outcome
	 */
	public UpdateResult withAotCache(boolean written)
	{
		return new UpdateResult(result, harness, version, previousVersion, message, paths, consistent, keyIds,
			Optional.of(written), exitDeletions);
	}

	/**
	 * Returns this outcome with the folders to delete once the installer no longer needs its own files.
	 *
	 * @param folders the folders
	 * @return the outcome
	 * @throws NullPointerException if {@code folders} or any of its elements are null
	 */
	public UpdateResult withExitDeletions(List<Path> folders)
	{
		return new UpdateResult(result, harness, version, previousVersion, message, paths, consistent, keyIds,
			aotCache, folders);
	}

	/**
	 * Installer outcomes and the shell exit status that each requires.
	 */
	public enum Result
	{
		/**
		 * The approved release became active.
		 */
		UPDATED(0),
		/**
		 * The approved release was already consistently active.
		 */
		UP_TO_DATE(0),
		/**
		 * Installation state was read without writes.
		 */
		STATUS(0),
		/**
		 * The plugin was added where the harness had none.
		 */
		INSTALLED(0),
		/**
		 * CAT was removed from the harness.
		 */
		UNINSTALLED(0),
		/**
		 * Usage was invalid.
		 */
		USAGE(2),
		/**
		 * Policy or validation refused the operation.
		 */
		REFUSED(3),
		/**
		 * Release transport failed.
		 */
		DOWNLOAD_FAILED(4),
		/**
		 * Uninstall stopped before CAT was removed completely; its progress is kept so that running the same
		 * uninstall command again continues with the step that failed.
		 */
		UNINSTALL_INCOMPLETE(9),
		/**
		 * An error stopped an operation after it started to change the harness's installation; running the command
		 * that the message names repairs it, or, when the message names none, running the same install again.
		 */
		FAILED(10),
		/**
		 * Another operation held the lock beyond the wait limit.
		 */
		LOCKED(75),
		/**
		 * State or activation paths were not writable.
		 */
		NOT_WRITABLE(77);

		private final int exitStatus;

		/**
		 * Creates an outcome.
		 *
		 * @param exitStatus its shell exit status
		 */
		Result(int exitStatus)
		{
			this.exitStatus = exitStatus;
		}

		/**
		 * Returns the shell exit status.
		 *
		 * @return the exit status
		 */
		public int exitStatus()
		{
			return exitStatus;
		}
	}
}
