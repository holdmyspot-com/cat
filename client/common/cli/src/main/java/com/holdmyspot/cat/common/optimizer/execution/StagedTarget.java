package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.RegularFile;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.secrets.StagedTargetSecrets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Reads a staged target prompt only when its bytes match its frozen snapshot.
 */
public final class StagedTarget
{
	static
	{
		StagedTargetSecrets.setStagedTargetAccess(StagedTarget::classifyReadFailure);
	}

	/**
	 * Prevents instantiation.
	 */
	private StagedTarget()
	{
	}

	/**
	 * Reads the target prompt, following the snapshot's recorded symbolic links when finding its expected content.
	 *
	 * @param staged    the absolute staged project directory
	 * @param inventory the frozen snapshot
	 * @param target    the nonempty normalized relative target path, without parent traversal
	 * @return the matching target bytes, including valid empty content
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code staged} is relative, or {@code target} has an invalid form
	 * @throws SnapshotMismatchException if the target is missing, nonregular, unrecorded or has different bytes
	 * @throws IOException               if the target's attributes or bytes cannot be read
	 */
	public static FileContent read(Path staged, FileInventory inventory, Path target) throws IOException
	{
		requireThat(staged, "staged").isAbsolute();
		requireThat(inventory, "inventory").isNotNull();
		FileInventory.requireInventoryPath(target, "target");
		requireThat(target.isAbsolute(), "target.isAbsolute()").isFalse();

		Path file = staged.resolve(target);
		BasicFileAttributes attributes;
		try
		{
			attributes = Files.readAttributes(file, BasicFileAttributes.class);
		}
		catch (IOException e)
		{
			throw classifyReadFailure(file, e);
		}
		if (!attributes.isRegularFile())
			throw new SnapshotMismatchException(file, "The target prompt path is not a regular file", null);
		Path resolved = inventory.resolve(target).orElse(target);
		if (!(inventory.entries().get(resolved) instanceof RegularFile expected))
			throw new SnapshotMismatchException(file, "The snapshot records no regular target prompt file", null);
		FileContent content = new FileContent(Files.readAllBytes(file));
		if (!content.hash().equals(expected.contentHash()))
			throw new SnapshotMismatchException(file, "The target prompt bytes differ from the snapshot", null);
		return content;
	}

	/**
	 * Classifies a missing target as an integrity mismatch and preserves every other attribute-read failure.
	 *
	 * @param file    the absolute staged target path
	 * @param failure the failure from reading the target's attributes
	 * @return an integrity mismatch retaining a missing-file cause, or the unchanged failure otherwise
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code file} is relative
	 */
	private static IOException classifyReadFailure(Path file, IOException failure)
	{
		requireThat(file, "file").isAbsolute();
		requireThat(failure, "failure").isNotNull();
		if (failure instanceof NoSuchFileException)
			return new SnapshotMismatchException(file, "The target prompt file is missing", failure);
		return failure;
	}
}
