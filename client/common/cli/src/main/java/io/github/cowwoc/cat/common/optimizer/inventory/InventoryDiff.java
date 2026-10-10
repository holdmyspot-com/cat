package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SpecialFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import io.github.cowwoc.cat.common.optimizer.inventory.PathChange.Added;
import io.github.cowwoc.cat.common.optimizer.inventory.PathChange.Modified;
import io.github.cowwoc.cat.common.optimizer.inventory.PathChange.Removed;
import io.github.cowwoc.cat.common.optimizer.inventory.TargetChange.TargetDelete;
import io.github.cowwoc.cat.common.optimizer.inventory.TargetChange.TargetWrite;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The differences between two inventories of the same project, compared path by path.
 * <p>
 * An empty difference between the inventory CAT expects, such as a candidate's {@link Overlay#snapshot() snapshot},
 * and a fresh scan shows that nothing changed outside CAT's recorded operations. A difference between a prompt author's
 * copy before and after an attempt is the attempt's delta, which {@link #review(FileInventory, Authority)} validates.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class InventoryDiff
{
	private final List<PathChange> changes;

	/**
	 * Creates a difference.
	 *
	 * @param changes the changed paths, in path order
	 */
	private InventoryDiff(List<PathChange> changes)
	{
		this.changes = List.copyOf(changes);
	}

	/**
	 * Compares two inventories.
	 *
	 * @param before the earlier or expected inventory
	 * @param after the later or observed inventory
	 * @return the differences
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static InventoryDiff between(FileInventory before, FileInventory after)
	{
		requireThat(before, "before").isNotNull();
		requireThat(after, "after").isNotNull();
		SortedSet<Path> paths = new TreeSet<>(before.entries().keySet());
		paths.addAll(after.entries().keySet());

		List<PathChange> changes = new ArrayList<>();
		for (Path path : paths)
		{
			FileEntry earlier = before.entries().get(path);
			FileEntry later = after.entries().get(path);
			if (earlier == null)
				changes.add(new Added(path, later));
			else if (later == null)
				changes.add(new Removed(path, earlier));
			else if (!earlier.equals(later))
				changes.add(new Modified(path, earlier, later));
		}
		return new InventoryDiff(changes);
	}

	/**
	 * Returns the changed paths.
	 *
	 * @return the changes, in path order
	 */
	public List<PathChange> changes()
	{
		return changes;
	}

	/**
	 * Indicates whether the two inventories record the same entries.
	 *
	 * @return {@code true} if no path changed
	 */
	public boolean isEmpty()
	{
		return changes.isEmpty();
	}

	/**
	 * Validates a prompt author attempt, treating this difference as the attempt's delta: the prompt author's copy
	 * scanned before and after the attempt.
	 * <p>
	 * The prompt author's copy replaces every symbolic link with a copy of its target, so each changed path is resolved
	 * through the fork's links and the change applies to the resolved target. Each change is classified:
	 * <ul>
	 *   <li>changed bytes of a regular file: a content edit of the target;</li>
	 *   <li>an added regular file: a create; a removed regular file: a delete;</li>
	 *   <li>a changed mode: a mode change;</li>
	 *   <li>an added, removed or retargeted symbolic link, or the removal of a path the fork records as a link: a link
	 *   change;</li>
	 *   <li>an added or removed special file: forbidden as {@link Limit#NOT_ORDINARY_FILE}.</li>
	 * </ul>
	 * {@code authority} decides which of these are forbidden. Changes that leave one target in different states
	 * through different paths are forbidden as {@link Limit#AMBIGUOUS_EDIT}.
	 *
	 * @param fork the files recorded at the fork, whose symbolic links resolve each changed path
	 * @param authority the edit authority
	 * @return the forbidden operations and the permitted target changes
	 * @throws NullPointerException if any of the arguments are null
	 */
	public AttemptReview review(FileInventory fork, Authority authority)
	{
		requireThat(fork, "fork").isNotNull();
		requireThat(authority, "authority").isNotNull();
		Review review = new Review(fork, authority);
		for (PathChange change : changes)
			review.classify(change);
		return review.finish();
	}

	/**
	 * The state of one attempt's review while its changes are classified.
	 */
	private static final class Review
	{
		private final FileInventory fork;
		private final Authority authority;
		private final List<ForbiddenOperation> forbidden = new ArrayList<>();
		private final SortedMap<Path, List<Outcome>> outcomesByTarget = new TreeMap<>();

		/**
		 * Creates a review.
		 *
		 * @param fork the files recorded at the fork
		 * @param authority the edit authority
		 */
		Review(FileInventory fork, Authority authority)
		{
			this.fork = fork;
			this.authority = authority;
		}

		/**
		 * Classifies one changed path.
		 *
		 * @param change the change
		 */
		void classify(PathChange change)
		{
			Path path = change.path();
			Optional<Path> resolved = fork.resolve(path);
			switch (change)
			{
				case Added added -> classifyAdded(path, resolved, added.after());
				case Removed removed -> classifyRemoved(path, resolved, removed.before());
				case Modified modified -> classifyModified(path, resolved, modified.before(), modified.after());
			}
		}

		/**
		 * Classifies a path that the attempt added.
		 *
		 * @param path the path in the prompt author's copy
		 * @param resolved the path's resolved target, or empty if the fork's links form a cycle
		 * @param after the added entry
		 */
		private void classifyAdded(Path path, Optional<Path> resolved, FileEntry after)
		{
			switch (after)
			{
				case RegularFile file -> check(path, resolved, Operation.CREATE, file);
				case SymbolicLink _ -> forbid(path, resolved, Operation.CHANGE_LINK, Limit.LINK_FIXED);
				case SpecialFile _ -> forbid(path, resolved, Operation.CREATE, Limit.NOT_ORDINARY_FILE);
			}
		}

		/**
		 * Classifies a path that the attempt removed.
		 *
		 * @param path the path in the prompt author's copy
		 * @param resolved the path's resolved target, or empty if the fork's links form a cycle
		 * @param before the removed entry
		 */
		private void classifyRemoved(Path path, Optional<Path> resolved, FileEntry before)
		{
			// The prompt author's copy holds a regular file where the fork holds a link, so removing that file removes
			// the link
			if (fork.entry(path).orElse(before) instanceof SymbolicLink)
			{
				forbid(path, resolved, Operation.CHANGE_LINK, Limit.LINK_FIXED);
				return;
			}
			switch (before)
			{
				case RegularFile _ -> check(path, resolved, Operation.DELETE, null);
				case SymbolicLink _ -> forbid(path, resolved, Operation.CHANGE_LINK, Limit.LINK_FIXED);
				case SpecialFile _ -> forbid(path, resolved, Operation.DELETE, Limit.NOT_ORDINARY_FILE);
			}
		}

		/**
		 * Classifies a path whose entry the attempt changed.
		 *
		 * @param path the path in the prompt author's copy
		 * @param resolved the path's resolved target, or empty if the fork's links form a cycle
		 * @param before the earlier entry
		 * @param after the later entry
		 */
		private void classifyModified(Path path, Optional<Path> resolved, FileEntry before, FileEntry after)
		{
			if (before instanceof RegularFile earlier && after instanceof RegularFile later)
			{
				if (!earlier.permissions().equals(later.permissions()))
					check(path, resolved, Operation.CHANGE_MODE, later);
				if (!earlier.contentHash().equals(later.contentHash()))
					check(path, resolved, Operation.EDIT_CONTENT, later);
				return;
			}
			if (before instanceof SymbolicLink || after instanceof SymbolicLink)
			{
				forbid(path, resolved, Operation.CHANGE_LINK, Limit.LINK_FIXED);
				return;
			}
			// A regular file replaced by a special file, or the reverse: one entry removed and another added
			classifyRemoved(path, resolved, before);
			classifyAdded(path, resolved, after);
		}

		/**
		 * Checks an operation against the authority, recording it as forbidden or as a permitted outcome for its target.
		 *
		 * @param path the path in the prompt author's copy
		 * @param resolved the path's resolved target, or empty if the fork's links form a cycle
		 * @param operation the operation
		 * @param after the target's resulting regular file, or {@code null} if the operation deletes it
		 */
		private void check(Path path, Optional<Path> resolved, Operation operation, RegularFile after)
		{
			if (resolved.isEmpty())
			{
				forbid(path, resolved, operation, Limit.LINK_CYCLE);
				return;
			}
			Path target = resolved.get();
			Optional<Limit> limit = authority.limitOf(operation, target);
			if (limit.isPresent())
			{
				forbid(path, resolved, operation, limit.get());
				return;
			}
			outcomesByTarget.computeIfAbsent(target, _ -> new ArrayList<>()).add(new Outcome(path, operation, after));
		}

		/**
		 * Records a forbidden operation.
		 *
		 * @param path the path in the prompt author's copy
		 * @param resolved the path's resolved target, or empty if the fork's links form a cycle
		 * @param operation the operation
		 * @param limit the reason it is forbidden
		 */
		private void forbid(Path path, Optional<Path> resolved, Operation operation, Limit limit)
		{
			forbidden.add(new ForbiddenOperation(path, resolved.orElse(path), operation, limit));
		}

		/**
		 * Completes the review, forbidding every target that the attempt left in different states through different
		 * paths.
		 *
		 * @return the review
		 */
		AttemptReview finish()
		{
			List<TargetChange> targetChanges = new ArrayList<>();
			for (Entry<Path, List<Outcome>> entry : outcomesByTarget.entrySet())
			{
				Path target = entry.getKey();
				List<Outcome> outcomes = entry.getValue();
				Outcome first = outcomes.getFirst();
				if (outcomes.stream().allMatch(outcome -> Objects.equals(outcome.after(), first.after())))
				{
					if (first.after() == null)
						targetChanges.add(new TargetDelete(target));
					else
						targetChanges.add(new TargetWrite(target, first.source(), first.after()));
					continue;
				}
				for (Outcome outcome : outcomes)
				{
					forbidden.add(new ForbiddenOperation(outcome.source(), target, outcome.operation(),
						Limit.AMBIGUOUS_EDIT));
				}
			}
			forbidden.sort(Comparator.comparing(ForbiddenOperation::path).
				thenComparing(ForbiddenOperation::operation));
			return new AttemptReview(forbidden, targetChanges);
		}
	}

	/**
	 * A permitted operation's effect on its resolved target.
	 *
	 * @param source the path in the prompt author's copy at which the operation was performed
	 * @param operation the operation
	 * @param after the target's resulting regular file, or {@code null} if the operation deletes it
	 */
	private record Outcome(Path source, Operation operation, RegularFile after)
	{
	}

	@Override
	public String toString()
	{
		return "InventoryDiff" + changes;
	}
}
