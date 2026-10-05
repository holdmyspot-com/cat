package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

/**
 * One file change in a conflict-free {@link DeliveryPlan}.
 * <p>
 * Each change records the live state its target must have before it is applied ({@link #preimage()}) and the state
 * it leaves ({@link #postimage()}). Before applying a change, delivery compares the live target with both: a target
 * that matches the postimage was already changed by an interrupted delivery and is skipped, a target that matches the
 * preimage is changed, and any other state stops delivery. Each change is therefore applied at most once.
 */
public sealed interface DeliveryChange
{
	/**
	 * Returns the change's position in the plan.
	 *
	 * @return the zero-based position; changes are applied in increasing order
	 */
	int sequence();

	/**
	 * Returns the changed file.
	 *
	 * @return the resolved path, relative to the project directory or absolute for an external target
	 */
	Path target();

	/**
	 * Returns the state the target must have before the change.
	 *
	 * @return the regular file, or empty if the target must not exist
	 */
	Optional<RegularFile> preimage();

	/**
	 * Returns the state the target has after the change.
	 *
	 * @return the regular file, or empty if the target does not exist
	 */
	Optional<RegularFile> postimage();

	/**
	 * Creates a file where none exists.
	 *
	 * @param sequence the zero-based position in the plan
	 * @param target the resolved path of the new file
	 * @param content the new file's bytes
	 * @param permissions the new file's mode
	 */
	record Create(int sequence, Path target, FileContent content, Set<PosixFilePermission> permissions)
		implements DeliveryChange
	{
		/**
		 * Creates a change.
		 *
		 * @param sequence the zero-based position in the plan
		 * @param target the resolved path of the new file
		 * @param content the new file's bytes
		 * @param permissions the new file's mode
		 * @throws NullPointerException if any of the arguments are null
		 * @throws IllegalArgumentException if {@code sequence} is negative
		 */
		public Create
		{
			requireThat(sequence, "sequence").isNotNegative();
			requireThat(target, "target").isNotNull();
			requireThat(content, "content").isNotNull();
			permissions = Set.copyOf(permissions);
		}

		@Override
		public Optional<RegularFile> preimage()
		{
			return Optional.empty();
		}

		@Override
		public Optional<RegularFile> postimage()
		{
			return Optional.of(new RegularFile(content.hash(), permissions));
		}
	}

	/**
	 * Replaces the content of an existing file.
	 *
	 * @param sequence the zero-based position in the plan
	 * @param target the resolved path of the file
	 * @param before the file that the target holds when delivery is planned
	 * @param content the file's new bytes
	 * @param permissions the file's new mode
	 */
	record Edit(int sequence, Path target, RegularFile before, FileContent content,
		Set<PosixFilePermission> permissions) implements DeliveryChange
	{
		/**
		 * Creates a change.
		 *
		 * @param sequence the zero-based position in the plan
		 * @param target the resolved path of the file
		 * @param before the file that the target holds when delivery is planned
		 * @param content the file's new bytes
		 * @param permissions the file's new mode
		 * @throws NullPointerException if any of the arguments are null
		 * @throws IllegalArgumentException if {@code sequence} is negative
		 */
		public Edit
		{
			requireThat(sequence, "sequence").isNotNegative();
			requireThat(target, "target").isNotNull();
			requireThat(before, "before").isNotNull();
			requireThat(content, "content").isNotNull();
			permissions = Set.copyOf(permissions);
		}

		@Override
		public Optional<RegularFile> preimage()
		{
			return Optional.of(before);
		}

		@Override
		public Optional<RegularFile> postimage()
		{
			return Optional.of(new RegularFile(content.hash(), permissions));
		}
	}

	/**
	 * Deletes a file that the fork recorded.
	 *
	 * @param sequence the zero-based position in the plan
	 * @param target the resolved path of the file
	 * @param before the file the fork recorded
	 */
	record Delete(int sequence, Path target, RegularFile before) implements DeliveryChange
	{
		/**
		 * Creates a change.
		 *
		 * @param sequence the zero-based position in the plan
		 * @param target the resolved path of the file
		 * @param before the file the fork recorded
		 * @throws NullPointerException if any of the arguments are null
		 * @throws IllegalArgumentException if {@code sequence} is negative
		 */
		public Delete
		{
			requireThat(sequence, "sequence").isNotNegative();
			requireThat(target, "target").isNotNull();
			requireThat(before, "before").isNotNull();
		}

		@Override
		public Optional<RegularFile> preimage()
		{
			return Optional.of(before);
		}

		@Override
		public Optional<RegularFile> postimage()
		{
			return Optional.empty();
		}
	}
}
