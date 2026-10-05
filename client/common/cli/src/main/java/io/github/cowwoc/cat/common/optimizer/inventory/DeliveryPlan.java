package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.Create;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.Delete;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.Edit;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryConflict.Kind;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import io.github.cowwoc.cat.common.optimizer.inventory.OverlayEntry.Write;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Delivery preflight: a three-way comparison of the fork baseline, the current base and external state, and the
 * candidate.
 * <p>
 * Delivery stops before changing any file if any overlay target conflicts with the current state:
 * <ul>
 *   <li>the base or external state also changed or deleted a file the candidate changes;</li>
 *   <li>the current structure collides with a file the candidate writes;</li>
 *   <li>a symbolic link through which the fork reached a target was removed or retargeted;</li>
 *   <li>a target path now resolves to a different file.</li>
 * </ul>
 * Base-only changes to other paths survive delivery and are reported.
 * <p>
 * A plan may also write files that are not part of the candidate, such as a certification record. Such a file
 * replaces whatever regular file the current state holds at its path, and conflicts only if the candidate also writes
 * the path, the path resolves to a different file, or the current structure collides with it.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class DeliveryPlan
{
	/**
	 * The mode of a file that the plan writes beside the candidate where none exists.
	 */
	private static final Set<PosixFilePermission> NEW_FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-r--r--");
	private final List<DeliveryConflict> conflicts;
	private final List<DeliveryChange> changes;
	private final List<Path> baseOnlyChanges;

	/**
	 * Creates a plan.
	 *
	 * @param conflicts the conflicts, in target order
	 * @param changes the changes to apply, in sequence order
	 * @param baseOnlyChanges the paths that only the base or external state changed, in path order
	 */
	private DeliveryPlan(List<DeliveryConflict> conflicts, List<DeliveryChange> changes, List<Path> baseOnlyChanges)
	{
		this.conflicts = List.copyOf(conflicts);
		this.changes = List.copyOf(changes);
		this.baseOnlyChanges = List.copyOf(baseOnlyChanges);
	}

	/**
	 * Plans the delivery of a candidate.
	 *
	 * @param candidate the candidate, whose fork is the baseline
	 * @param current a fresh scan of the base worktree and of the same external paths as the fork
	 * @return the plan
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static DeliveryPlan of(Overlay candidate, FileInventory current)
	{
		return of(candidate, current, Map.of());
	}

	/**
	 * Plans the delivery of a candidate together with files that are not part of it.
	 *
	 * @param candidate the candidate, whose fork is the baseline
	 * @param current a fresh scan of the base worktree and of the same external paths as the fork
	 * @param additionalFiles the content of each file to write after the candidate's changes, keyed by its path
	 *   relative to the base worktree
	 * @return the plan
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a path in {@code additionalFiles} is absolute
	 */
	public static DeliveryPlan of(Overlay candidate, FileInventory current, Map<Path, FileContent> additionalFiles)
	{
		requireThat(candidate, "candidate").isNotNull();
		requireThat(current, "current").isNotNull();
		requireThat(additionalFiles, "additionalFiles").isNotNull();
		for (Path path : additionalFiles.keySet())
			requireThat(path.isAbsolute(), "additionalFiles.isAbsolute(" + path + ")").isFalse();
		FileInventory fork = candidate.fork();
		Map<Path, OverlayEntry> overlay = candidate.entries();
		Map<Path, FileContent> additions = new TreeMap<>(additionalFiles);

		List<DeliveryConflict> conflicts = new ArrayList<>();
		for (Path target : overlay.keySet())
			conflicts.addAll(conflictsOf(target, fork, current));
		for (Path target : additions.keySet())
			conflicts.addAll(additionConflictsOf(target, overlay, current));

		List<DeliveryChange> changes = new ArrayList<>();
		if (conflicts.isEmpty())
		{
			changes = changesOf(overlay, fork);
			for (Entry<Path, FileContent> entry : additions.entrySet())
				changes.add(additionOf(changes.size(), entry.getKey(), entry.getValue(), current));
		}

		SortedSet<Path> paths = new TreeSet<>(fork.entries().keySet());
		paths.addAll(current.entries().keySet());
		List<Path> baseOnlyChanges = new ArrayList<>();
		for (Path path : paths)
		{
			if (!overlay.containsKey(path) && !additions.containsKey(path) &&
				!Objects.equals(fork.entry(path), current.entry(path)))
			{
				baseOnlyChanges.add(path);
			}
		}
		return new DeliveryPlan(conflicts, changes, baseOnlyChanges);
	}

	/**
	 * Returns the conflicts that block a file that the plan writes beside the candidate.
	 *
	 * @param target the file's path
	 * @param overlay the candidate's overlay entries
	 * @param current the current files
	 * @return the conflicts, empty if the file can be written
	 */
	private static List<DeliveryConflict> additionConflictsOf(Path target, Map<Path, OverlayEntry> overlay,
		FileInventory current)
	{
		if (!current.resolve(target).equals(Optional.of(target)))
		{
			return List.of(new DeliveryConflict(Kind.TARGET_IDENTITY_MISMATCH, target,
				current.firstLinkPrefix(target).orElse(target)));
		}
		if (overlay.containsKey(target))
			return List.of(new DeliveryConflict(Kind.BOTH_CHANGED, target, target));
		return structuralCollision(target, current).
			map(path -> List.of(new DeliveryConflict(Kind.STRUCTURAL_COLLISION, target, path))).
			orElse(List.of());
	}

	/**
	 * Returns the change that writes a file beside the candidate: an edit of the regular file that the current state
	 * holds at its path, keeping that file's mode, or else the creation of a new file.
	 *
	 * @param sequence the change's position in the plan
	 * @param target the file's path
	 * @param content the file's content
	 * @param current the current files
	 * @return the change
	 */
	private static DeliveryChange additionOf(int sequence, Path target, FileContent content, FileInventory current)
	{
		// A conflict-free plan holds nothing but a regular file at the target
		if (current.entry(target).orElse(null) instanceof RegularFile before)
			return new Edit(sequence, target, before, content, before.permissions());
		return new Create(sequence, target, content, NEW_FILE_PERMISSIONS);
	}

	/**
	 * Returns the conflicts that block one overlay target.
	 *
	 * @param target the overlay target
	 * @param fork the files recorded at the fork
	 * @param current the current files
	 * @return the conflicts, empty if the target can be delivered
	 */
	private static List<DeliveryConflict> conflictsOf(Path target, FileInventory fork, FileInventory current)
	{
		List<DeliveryConflict> conflicts = new ArrayList<>();
		for (Entry<Path, FileEntry> entry : fork.entries().entrySet())
		{
			if (!(entry.getValue() instanceof SymbolicLink))
				continue;
			Path link = entry.getKey();
			Optional<Path> destination = fork.resolve(link);
			if (destination.isPresent() && target.startsWith(destination.get()) &&
				!current.entry(link).equals(Optional.of(entry.getValue())))
			{
				conflicts.add(new DeliveryConflict(Kind.LINK_DESTINATION_CHANGED, target, link));
			}
		}
		if (!current.resolve(target).equals(Optional.of(target)))
		{
			conflicts.add(new DeliveryConflict(Kind.TARGET_IDENTITY_MISMATCH, target,
				current.firstLinkPrefix(target).orElse(target)));
		}
		if (!conflicts.isEmpty())
			return conflicts;

		Optional<FileEntry> recorded = fork.entry(target);
		Optional<FileEntry> live = current.entry(target);
		if (recorded.isEmpty())
		{
			// The candidate creates the file; anything at, above or below its path collides
			if (live.isPresent())
				return List.of(new DeliveryConflict(Kind.STRUCTURAL_COLLISION, target, target));
			return structuralCollision(target, current).
				map(path -> List.of(new DeliveryConflict(Kind.STRUCTURAL_COLLISION, target, path))).
				orElse(List.of());
		}
		if (live.equals(recorded))
			return List.of();
		// The candidate edits or deletes the file, which the current state no longer holds unchanged
		Optional<Path> collision = structuralCollision(target, current);
		if (collision.isPresent())
			return List.of(new DeliveryConflict(Kind.STRUCTURAL_COLLISION, target, collision.get()));
		return List.of(new DeliveryConflict(Kind.BOTH_CHANGED, target, target));
	}

	/**
	 * Returns the current path that prevents a regular file from existing at a path: a recorded entry above it, an
	 * entry other than a regular file at it, or an entry below it.
	 *
	 * @param path the path of the regular file
	 * @param current the current files
	 * @return the colliding path, or empty if none
	 */
	private static Optional<Path> structuralCollision(Path path, FileInventory current)
	{
		for (Path ancestor = path.getParent(); ancestor != null; ancestor = ancestor.getParent())
		{
			if (ancestor.getNameCount() > 0 && current.entry(ancestor).isPresent())
				return Optional.of(ancestor);
		}
		Optional<FileEntry> live = current.entry(path);
		if (live.isPresent() && !(live.get() instanceof RegularFile))
			return Optional.of(path);
		return current.firstPathBelow(path);
	}

	/**
	 * Returns the ordered changes of a conflict-free plan: every delete, then every write, each group in path order.
	 * Deleting first frees paths that a create may reuse.
	 *
	 * @param overlay the candidate's overlay entries, in path order
	 * @param fork the files recorded at the fork
	 * @return the changes, in sequence order
	 */
	private static List<DeliveryChange> changesOf(Map<Path, OverlayEntry> overlay, FileInventory fork)
	{
		List<DeliveryChange> changes = new ArrayList<>();
		for (Entry<Path, OverlayEntry> entry : overlay.entrySet())
		{
			if (entry.getValue() instanceof OverlayEntry.Delete)
			{
				Path target = entry.getKey();
				changes.add(new Delete(changes.size(), target, recordedFile(fork, target)));
			}
		}
		for (Entry<Path, OverlayEntry> entry : overlay.entrySet())
		{
			if (entry.getValue() instanceof Write write)
			{
				Path target = entry.getKey();
				if (fork.entry(target).isPresent())
				{
					changes.add(new Edit(changes.size(), target, recordedFile(fork, target), write.content(),
						write.permissions()));
				}
				else
					changes.add(new Create(changes.size(), target, write.content(), write.permissions()));
			}
		}
		return changes;
	}

	/**
	 * Returns the regular file that the fork records at an overlay target.
	 *
	 * @param fork the files recorded at the fork
	 * @param target an overlay target that edits or deletes a recorded file
	 * @return the recorded file
	 */
	private static RegularFile recordedFile(FileInventory fork, Path target)
	{
		// Overlay accepts edits and deletes only of recorded regular files
		return (RegularFile) fork.entry(target).orElseThrow();
	}

	/**
	 * Returns the conflicts that stop delivery.
	 *
	 * @return the conflicts, grouped by overlay target in path order; empty if delivery may proceed
	 */
	public List<DeliveryConflict> conflicts()
	{
		return conflicts;
	}

	/**
	 * Returns the changes that deliver the candidate.
	 *
	 * @return the changes in the order they must be applied, or an empty list if there are conflicts
	 */
	public List<DeliveryChange> changes()
	{
		return changes;
	}

	/**
	 * Returns the paths that only the base or external state changed since the fork. Delivery leaves them unchanged.
	 *
	 * @return the paths, in path order
	 */
	public List<Path> baseOnlyChanges()
	{
		return baseOnlyChanges;
	}

	@Override
	public String toString()
	{
		return "DeliveryPlan[conflicts=" + conflicts + ", changes=" + changes + ", baseOnlyChanges=" +
			baseOnlyChanges + "]";
	}
}
