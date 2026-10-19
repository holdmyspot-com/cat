package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.Operation;

import java.nio.file.Path;

/**
 * An edit that a prompt author reported a requirement needs but that it did not make, because the edit seemed to lie
 * outside its edit authority. The driver checks each one against the authority.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param requirement the identifier of the requirement that needs the edit
 * @param path the path at which the edit is needed, relative to the project directory or absolute
 * @param operation the needed operation
 * @param target the link's resolved target as the prompt author read it where {@code path} is a symbolic link,
 *   otherwise {@code null}; CAT resolves the path using the files recorded when the campaign copied the base project
 */
public record NeededEdit(String requirement, Path path, Operation operation, Path target)
{
	/**
	 * Creates a needed edit.
	 *
	 * @param requirement the identifier of the requirement that needs the edit
	 * @param path the path at which the edit is needed, relative to the project directory or absolute
	 * @param operation the needed operation
	 * @param target the link's resolved target as the prompt author read it where {@code path} is a symbolic link,
	 *   otherwise {@code null}
	 * @throws NullPointerException if {@code requirement}, {@code path} or {@code operation} is null
	 * @throws IllegalArgumentException if {@code requirement} is blank, or if {@code path} or {@code target} is
	 *   empty, not normalized or starts with {@code ..}
	 */
	public NeededEdit
	{
		requireThat(requirement, "requirement").isNotBlank();
		FileInventory.requireInventoryPath(path, "path");
		requireThat(operation, "operation").isNotNull();
		if (target != null)
			FileInventory.requireInventoryPath(target, "target");
	}
}
