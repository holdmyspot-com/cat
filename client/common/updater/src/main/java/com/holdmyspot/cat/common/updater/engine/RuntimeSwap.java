package com.holdmyspot.cat.common.updater.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Locale;

/**
 * A replacement of the installation's Java runtime that the updater requested and the stable launcher finishes after
 * the updater's JVM has exited, because the updater runs on that runtime.
 *
 * @param operation   the operation that requested the replacement
 * @param fromVersion the version that the installation's {@code current} file selected when it was requested
 * @param toVersion   the version that the {@code current} file selects once the replacement is finished
 */
public record RuntimeSwap(Operation operation, String fromVersion, String toVersion)
{
	/**
	 * Creates a replacement.
	 *
	 * @param operation   the operation that requested the replacement
	 * @param fromVersion the version selected when it was requested
	 * @param toVersion   the version selected once it is finished
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if a version is blank, or both versions are equal
	 */
	public RuntimeSwap
	{
		requireThat(operation, "operation").isNotNull();
		requireThat(fromVersion, "fromVersion").isNotBlank();
		requireThat(toVersion, "toVersion").isNotBlank().isNotEqualTo(fromVersion, "fromVersion");
	}

	/**
	 * The operations that replace the runtime.
	 */
	public enum Operation
	{
		/**
		 * An update stages the release's runtime as {@code runtime.next} and keeps the replaced one as
		 * {@code runtime.previous}.
		 */
		UPDATE,
		/**
		 * A rollback restores {@code runtime.previous} and sets the replaced one aside as {@code runtime.next}, which
		 * the launcher then deletes.
		 */
		ROLLBACK;

		/**
		 * Returns the name that the swap request and results use.
		 *
		 * @return the lowercase name
		 */
		public String id()
		{
			return name().toLowerCase(Locale.ROOT);
		}
	}
}
