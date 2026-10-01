package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * The state that a candidate's {@link Overlay} gives one ordinary file, where it differs from the fork.
 */
public sealed interface OverlayEntry
{
	/**
	 * The file exists with the given content and mode. It edits a file the fork recorded, or creates one the fork did
	 * not.
	 *
	 * @param content the file's bytes
	 * @param permissions the file's mode
	 */
	record Write(FileContent content, Set<PosixFilePermission> permissions) implements OverlayEntry
	{
		/**
		 * Creates a write.
		 *
		 * @param content the file's bytes
		 * @param permissions the file's mode
		 * @throws NullPointerException if {@code content} or {@code permissions} are null
		 */
		public Write
		{
			requireThat(content, "content").isNotNull();
			permissions = Set.copyOf(permissions);
		}
	}

	/**
	 * The file that the fork recorded does not exist.
	 */
	record Delete() implements OverlayEntry
	{
	}
}
