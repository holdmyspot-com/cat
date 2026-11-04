package com.holdmyspot.cat.common.updater.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The observable outcome of one updater operation. The CLI writes enum names in lowercase and record names in
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
 */
public record UpdateResult(Result result, String harness, Optional<String> version, Optional<String> previousVersion,
	String message, List<Path> paths, boolean consistent, List<String> keyIds)
{
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
	}

	/**
	 * Updater outcomes and the shell exit status that each requires.
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
		 * The previous activation was reversed.
		 */
		ROLLED_BACK(0),
		/**
		 * Installation state was read without writes.
		 */
		STATUS(0),
		/**
		 * Setup completed.
		 */
		SET_UP(0),
		/**
		 * The plugin was added where the harness had none.
		 */
		INSTALLED(0),
		/**
		 * CAT was removed from the harness; the stable launcher deletes the updater's data directory.
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
		 * Activation failed and its changes were reversed.
		 */
		RESTORED(5),
		/**
		 * Reversal failed; the recovery journal remains.
		 */
		NOT_RESTORED(6),
		/**
		 * Uninstall stopped before CAT was removed completely; its progress is kept so that the next uninstall
		 * continues with the step that failed.
		 */
		NOT_REMOVED(7),
		/**
		 * The stable launcher could not put the Java runtime that an operation staged in place and restored the
		 * replaced one; the next updater operation undoes the rest of the operation. Only the stable launcher prints
		 * this outcome.
		 */
		NOT_SWAPPED(8),
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
