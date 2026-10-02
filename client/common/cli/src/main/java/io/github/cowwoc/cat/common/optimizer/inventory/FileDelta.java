package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.Objects;

/**
 * The exact working and author bytes needed to compare and reduce one authorized file change.
 *
 * @param path the resolved file path
 * @param before the working bytes, or {@code null} for a create
 * @param after the author bytes, or {@code null} for a delete
 * @param permissions the unchanged mode, or the new file's mode for a create
 */
public record FileDelta(Path path, FileContent before, FileContent after, Set<PosixFilePermission> permissions)
{
	/**
	 * Creates a file change.
	 *
	 * @param path the resolved file path
	 * @param before the working bytes, or {@code null} for a create
	 * @param after the author bytes, or {@code null} for a delete
	 * @param permissions the file mode
	 * @throws NullPointerException if {@code path} or {@code permissions} are null
	 * @throws IllegalArgumentException if the path is invalid or the bytes are unchanged
	 */
	public FileDelta
	{
		FileInventory.requireInventoryPath(path, "path");
		requireThat(permissions, "permissions").isNotNull();
		if (Objects.equals(before, after))
			throw new IllegalArgumentException("A file delta must change its bytes: " + path);
		permissions = Set.copyOf(permissions);
	}
}
