package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.Create;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.Delete;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.Edit;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.WriteLink;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryConflict.Kind;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SpecialFile;
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
 * Delivery preflight: a three-way comparison of the fork baseline, the current state, and the candidate. The current
 * state covers the base worktree and the read-only external participants that the fork recorded.
 * <p>
 * A plan changes only files inside the project. External files are read-only, so a candidate that changes one is
 * refused before any plan is made.
 * <p>
 * Delivery stops before changing any file if any overlay target conflicts with the current state:
 * <ul>
 *   <li>the base worktree also changed or deleted a file the candidate changes;</li>
 *   <li>the current structure collides with a file the candidate writes;</li>
 *   <li>a symbolic link through which the fork reached a target was removed or retargeted;</li>
 *   <li>a target path now resolves to a different file.</li>
 * </ul>
 * Changes made outside the candidate to other paths, including read-only external participants, survive delivery
 * and are reported.
 * <p>
 * A plan may also write test files and links, which are planned against the fork baseline like the candidate's own
 * files and applied after them. A test file whose content the fork already records, or a test link that the fork
 * already records with the same target, is left alone. A test link, or a test file where the fork records a link,
 * replaces the path itself rather than what it points to.
 * <p>
 * A plan may also write other files that are not part of the candidate, such as a certification record. Such a file
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
	 * @param baseOnlyChanges the paths that changed outside the candidate since the fork, in path order
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
	 * @throws IllegalArgumentException if {@code candidate} changes an external file
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
	 * @throws IllegalArgumentException if the candidate changes an external file, or if {@code additionalFiles} has an
	 *   absolute path
	 */
	public static DeliveryPlan of(Overlay candidate, FileInventory current, Map<Path, FileContent> additionalFiles)
	{
		return of(candidate, current, TestFiles.NONE, additionalFiles);
	}

	/**
	 * Plans the delivery of a candidate together with test files and other files that are not part of it. The test
	 * files are planned against the fork baseline, as the candidate's files are: a file that the fork records with the
	 * same content is left alone, and any other conflicts as a candidate's file would. A test link that the fork
	 * records with the same target is left alone. Any other test link, and a test file where the fork records a link,
	 * replaces the path: it conflicts if its directory does not resolve to itself, the current state changed what the
	 * fork recorded there, or the current structure collides with it. A directory that the plan creates conflicts with
	 * anything already at or inside it.
	 *
	 * @param candidate the candidate, whose fork is the baseline
	 * @param current a fresh scan of the base worktree and of the same external paths as the fork
	 * @param tests the test files to write after the candidate's changes
	 * @param additionalFiles the content of each file to write after the test files, keyed by its path relative to the
	 *   base worktree
	 * @return the plan
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the candidate changes an external file, or if {@code additionalFiles} has an
	 *   absolute path
	 */
	public static DeliveryPlan of(Overlay candidate, FileInventory current, TestFiles tests,
		Map<Path, FileContent> additionalFiles)
	{
		requireThat(candidate, "candidate").isNotNull();
		requireThat(current, "current").isNotNull();
		requireThat(tests, "tests").isNotNull();
		requireThat(additionalFiles, "additionalFiles").isNotNull();
		requireProjectChanges(candidate);
		for (Path path : additionalFiles.keySet())
			requireThat(path.isAbsolute(), "additionalFiles.isAbsolute(path)").withContext(path, "path").isFalse();
		FileInventory fork = candidate.fork();
		Map<Path, OverlayEntry> overlay = candidate.entries();
		Map<Path, FileContent> additions = new TreeMap<>(additionalFiles);
		Map<Path, FileContent> testFiles = new TreeMap<>();
		for (Entry<Path, FileContent> entry : tests.files().entrySet())
		{
			Optional<FileEntry> recorded = fork.entry(entry.getKey());
			if (!(recorded.orElse(null) instanceof RegularFile file &&
				file.contentHash().equals(Sha256.of(entry.getValue().bytes()))))
			{
				testFiles.put(entry.getKey(), entry.getValue());
			}
		}
		Map<Path, Path> testLinks = new TreeMap<>();
		for (Entry<Path, Path> entry : tests.links().entrySet())
		{
			if (!fork.entry(entry.getKey()).equals(Optional.of(new SymbolicLink(entry.getValue()))))
				testLinks.put(entry.getKey(), entry.getValue());
		}

		List<DeliveryConflict> conflicts = new ArrayList<>();
		for (Path target : overlay.keySet())
			conflicts.addAll(conflictsOf(target, fork, current));
		for (Path target : testFiles.keySet())
		{
			if (overlay.containsKey(target))
				conflicts.add(new DeliveryConflict(Kind.BOTH_CHANGED, target, target));
			else if (fork.entry(target).orElse(null) instanceof SymbolicLink)
				conflicts.addAll(replacementConflictsOf(target, fork, current));
			else
				conflicts.addAll(conflictsOf(target, fork, current));
		}
		for (Path target : testLinks.keySet())
		{
			if (overlay.containsKey(target))
				conflicts.add(new DeliveryConflict(Kind.BOTH_CHANGED, target, target));
			else
				conflicts.addAll(replacementConflictsOf(target, fork, current));
		}
		for (Path directory : new TreeSet<>(tests.createdDirectories()))
		{
			Optional<Path> occupant = occupant(directory, fork).or(() -> occupant(directory, current));
			occupant.ifPresent(path -> conflicts.add(new DeliveryConflict(Kind.STRUCTURAL_COLLISION, directory, path)));
		}
		for (Path target : additions.keySet())
		{
			if (testFiles.containsKey(target) || testLinks.containsKey(target))
				conflicts.add(new DeliveryConflict(Kind.BOTH_CHANGED, target, target));
			else
				conflicts.addAll(additionConflictsOf(target, overlay, current));
		}

		List<DeliveryChange> changes = new ArrayList<>();
		if (conflicts.isEmpty())
		{
			changes = changesOf(overlay, fork);
			for (Entry<Path, FileContent> entry : testFiles.entrySet())
				changes.add(testFileOf(changes.size(), entry.getKey(), entry.getValue(), fork));
			for (Entry<Path, Path> entry : testLinks.entrySet())
				changes.add(new WriteLink(changes.size(), entry.getKey(), fork.entry(entry.getKey()), entry.getValue()));
			for (Entry<Path, FileContent> entry : additions.entrySet())
				changes.add(additionOf(changes.size(), entry.getKey(), entry.getValue(), current));
		}

		SortedSet<Path> paths = new TreeSet<>(fork.entries().keySet());
		paths.addAll(current.entries().keySet());
		List<Path> baseOnlyChanges = new ArrayList<>();
		for (Path path : paths)
		{
			if (!overlay.containsKey(path) && !testFiles.containsKey(path) && !testLinks.containsKey(path) &&
				!additions.containsKey(path) && !Objects.equals(fork.entry(path), current.entry(path)))
			{
				baseOnlyChanges.add(path);
			}
		}
		return new DeliveryPlan(conflicts, changes, baseOnlyChanges);
	}

	/**
	 * Refuses a candidate that changes an external file. External files are read-only, so edit authority never
	 * permits such a change and delivery never writes one.
	 *
	 * @param candidate the candidate
	 * @throws NullPointerException if {@code candidate} is null
	 * @throws IllegalArgumentException if {@code candidate} changes a file outside the project
	 */
	public static void requireProjectChanges(Overlay candidate)
	{
		requireThat(candidate, "candidate").isNotNull();
		for (Path target : candidate.entries().keySet())
		{
			if (target.isAbsolute())
			{
				throw new IllegalArgumentException("The candidate changes " + target + ", which lies outside the " +
					"project. External files are read-only and delivery writes only files inside the project. Select a " +
					"candidate that changes only project files.");
			}
		}
	}

	/**
	 * Returns the first entry of an inventory at or inside a directory that the plan creates.
	 *
	 * @param directory the directory
	 * @param inventory the inventory
	 * @return the entry's path, or empty if the inventory holds nothing there
	 */
	private static Optional<Path> occupant(Path directory, FileInventory inventory)
	{
		if (inventory.entry(directory).isPresent())
			return Optional.of(directory);
		return inventory.firstPathBelow(directory);
	}

	/**
	 * Returns the change that writes a test file: an edit of the regular file that the fork records at its path,
	 * keeping that file's mode, the replacement of the symbolic link that the fork records there, or else the creation
	 * of a new file.
	 *
	 * @param sequence the change's position in the plan
	 * @param target the file's path
	 * @param content the file's content
	 * @param fork the files recorded at the fork
	 * @return the change
	 */
	private static DeliveryChange testFileOf(int sequence, Path target, FileContent content, FileInventory fork)
	{
		// A conflict-free plan holds nothing but the recorded regular file, or nothing, at the target
		return switch (fork.entry(target).orElse(null))
		{
			case RegularFile before -> new Edit(sequence, target, before, content, before.permissions());
			case SymbolicLink before -> new Edit(sequence, target, before, content, NEW_FILE_PERMISSIONS);
			case null, default -> new Create(sequence, target, content, NEW_FILE_PERMISSIONS);
		};
	}

	/**
	 * Returns the conflicts that block a test link, or a test file where the fork records a link. The plan replaces
	 * the path itself rather than what it points to, so only the path's directory must resolve to itself.
	 *
	 * @param target the path
	 * @param fork the files recorded at the fork
	 * @param current the current files
	 * @return the conflicts, empty if the path can be replaced
	 */
	private static List<DeliveryConflict> replacementConflictsOf(Path target, FileInventory fork,
		FileInventory current)
	{
		Path directory = target.getParent();
		if (directory != null)
		{
			for (FileInventory inventory : List.of(fork, current))
			{
				if (!inventory.resolve(directory).equals(Optional.of(directory)))
				{
					return List.of(new DeliveryConflict(Kind.TARGET_IDENTITY_MISMATCH, target,
						inventory.firstLinkPrefix(directory).orElse(directory)));
				}
			}
		}

		Optional<FileEntry> recorded = fork.entry(target);
		Optional<FileEntry> live = current.entry(target);
		if (recorded.isEmpty())
		{
			if (live.isPresent())
				return List.of(new DeliveryConflict(Kind.STRUCTURAL_COLLISION, target, target));
			return structuralCollision(target, current).
				map(path -> List.of(new DeliveryConflict(Kind.STRUCTURAL_COLLISION, target, path))).
				orElse(List.of());
		}
		if (recorded.get() instanceof SpecialFile)
			return List.of(new DeliveryConflict(Kind.STRUCTURAL_COLLISION, target, target));
		if (live.equals(recorded))
			return List.of();
		return List.of(new DeliveryConflict(Kind.BOTH_CHANGED, target, target));
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
				changes.add(new Delete(changes.size(), target, recordedFile(target, fork)));
			}
		}
		for (Entry<Path, OverlayEntry> entry : overlay.entrySet())
		{
			if (entry.getValue() instanceof Write write)
			{
				Path target = entry.getKey();
				if (fork.entry(target).isPresent())
				{
					changes.add(new Edit(changes.size(), target, recordedFile(target, fork), write.content(),
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
	 * @param target an overlay target that edits or deletes a recorded file
	 * @param fork the files recorded at the fork
	 * @return the recorded file
	 */
	private static RegularFile recordedFile(Path target, FileInventory fork)
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
	 * Returns the paths that changed outside the candidate since the fork. Delivery leaves them unchanged.
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
