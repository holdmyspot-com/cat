package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SpecialFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import io.github.cowwoc.cat.common.optimizer.inventory.PathState.Directory;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

/**
 * One file, link or directory change in a conflict-free {@link DeliveryPlan}.
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
	 * @return the resolved path, relative to the project directory
	 */
	Path target();

	/**
	 * Returns the state the target must have before the change.
	 *
	 * @return the regular file or symbolic link, or empty if the target must not exist
	 */
	Optional<PathState> preimage();

	/**
	 * Returns the state the target has after the change.
	 *
	 * @return the regular file, symbolic link or directory, or empty if the target does not exist
	 */
	Optional<PathState> postimage();

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
		public Optional<PathState> preimage()
		{
			return Optional.empty();
		}

		@Override
		public Optional<PathState> postimage()
		{
			return Optional.of(new RegularFile(content.hash(), permissions));
		}
	}

	/**
	 * Replaces the content of an existing file, or replaces a symbolic link with a file.
	 *
	 * @param sequence the zero-based position in the plan
	 * @param target the resolved path of the file, or the path of the link
	 * @param before the regular file or symbolic link that the target holds when delivery is planned
	 * @param content the file's new bytes
	 * @param permissions the file's new mode
	 */
	record Edit(int sequence, Path target, FileEntry before, FileContent content,
		Set<PosixFilePermission> permissions) implements DeliveryChange
	{
		/**
		 * Creates a change.
		 *
		 * @param sequence the zero-based position in the plan
		 * @param target the resolved path of the file, or the path of the link
		 * @param before the regular file or symbolic link that the target holds when delivery is planned
		 * @param content the file's new bytes
		 * @param permissions the file's new mode
		 * @throws NullPointerException if any of the arguments are null
		 * @throws IllegalArgumentException if {@code sequence} is negative or {@code before} is a {@link SpecialFile}
		 */
		public Edit
		{
			requireThat(sequence, "sequence").isNotNegative();
			requireThat(target, "target").isNotNull();
			requireThat(before, "before").isNotNull().isNotInstanceOf(SpecialFile.class);
			requireThat(content, "content").isNotNull();
			permissions = Set.copyOf(permissions);
		}

		@Override
		public Optional<PathState> preimage()
		{
			return Optional.of(before);
		}

		@Override
		public Optional<PathState> postimage()
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
		public Optional<PathState> preimage()
		{
			return Optional.of(before);
		}

		@Override
		public Optional<PathState> postimage()
		{
			return Optional.empty();
		}
	}

	/**
	 * Creates a symbolic link, replacing the regular file or symbolic link that the target holds, if any.
	 *
	 * @param sequence   the zero-based position in the plan
	 * @param target     the path of the link, relative to the project directory
	 * @param before     the regular file or symbolic link that the target holds when delivery is planned, or empty if
	 *                   the target must not exist
	 * @param linkTarget the link's target, relative to the link's directory
	 */
	record WriteLink(int sequence, Path target, Optional<FileEntry> before, Path linkTarget)
		implements DeliveryChange
	{
		/**
		 * Creates a change.
		 *
		 * @param sequence   the zero-based position in the plan
		 * @param target     the path of the link, relative to the project directory
		 * @param before     the regular file or symbolic link that the target holds when delivery is planned, or empty
		 *                   if the target must not exist
		 * @param linkTarget the link's target, relative to the link's directory
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code sequence} is negative, {@code before} holds a {@link SpecialFile}
		 *                                  or {@code linkTarget} is absolute
		 */
		public WriteLink
		{
			requireThat(sequence, "sequence").isNotNegative();
			requireThat(target, "target").isNotNull();
			requireThat(before, "before").isNotNull();
			if (before.isPresent())
				requireThat(before.get(), "before").isNotInstanceOf(SpecialFile.class);
			requireThat(linkTarget, "linkTarget").isNotNull();
			requireThat(linkTarget.isAbsolute(), "linkTarget.isAbsolute()").isFalse();
		}

		@Override
		public Optional<PathState> preimage()
		{
			return before.map(PathState.class::cast);
		}

		@Override
		public Optional<PathState> postimage()
		{
			return Optional.of(new SymbolicLink(linkTarget));
		}
	}

	/**
	 * Replaces a symbolic link with an empty directory, which later changes of the plan fill.
	 * <p>
	 * The link is deleted before the directory is created, so a crash in between leaves neither and stops the next
	 * delivery run.
	 *
	 * @param sequence the zero-based position in the plan
	 * @param target   the path of the link, relative to the project directory
	 * @param before   the symbolic link that the target holds when delivery is planned
	 */
	record ReplaceLinkWithDirectory(int sequence, Path target, SymbolicLink before) implements DeliveryChange
	{
		/**
		 * Creates a change.
		 *
		 * @param sequence the zero-based position in the plan
		 * @param target   the path of the link, relative to the project directory
		 * @param before   the symbolic link that the target holds when delivery is planned
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code sequence} is negative
		 */
		public ReplaceLinkWithDirectory
		{
			requireThat(sequence, "sequence").isNotNegative();
			requireThat(target, "target").isNotNull();
			requireThat(before, "before").isNotNull();
		}

		@Override
		public Optional<PathState> preimage()
		{
			return Optional.of(before);
		}

		@Override
		public Optional<PathState> postimage()
		{
			return Optional.of(new Directory());
		}
	}
}
