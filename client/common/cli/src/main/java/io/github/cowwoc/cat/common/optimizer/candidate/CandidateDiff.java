package io.github.cowwoc.cat.common.optimizer.candidate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A validated, structured difference between two prompt snapshots.
 */
public final class CandidateDiff
{
	private final List<Change> changes;

	/**
	 * Creates an immutable structured diff.
	 *
	 * @param changes the ordered changed paths
	 */
	private CandidateDiff(List<Change> changes)
	{
		this.changes = List.copyOf(changes);
	}

	/**
	 * Validates that a candidate changes only regular prompt files beneath confirmed roots and returns its structured
	 * difference from the supplied predecessor.
	 *
	 * @param predecessor the expected prior prompt snapshot
	 * @param candidate the proposed prompt snapshot
	 * @param confirmedRoots confirmed project-relative prompt roots
	 * @return the validated difference
	 * @throws IOException if an entry is outside a root or has an unsupported kind
	 */
	public static CandidateDiff validate(PromptSnapshot predecessor, PromptSnapshot candidate,
		Collection<Path> confirmedRoots) throws IOException
	{
		Objects.requireNonNull(predecessor, "predecessor");
		Objects.requireNonNull(candidate, "candidate");
		List<Path> roots = normalizedRoots(confirmedRoots);
		validateEntries(predecessor.entries(), roots);
		validateEntries(candidate.entries(), roots);
		Set<Path> paths = new TreeSet<>(Comparator.comparing(Path::toString));
		paths.addAll(predecessor.entries().keySet());
		paths.addAll(candidate.entries().keySet());
		List<Change> changes = new ArrayList<>();
		for (Path path: paths)
		{
			PromptSnapshot.Entry before = predecessor.entries().get(path);
			PromptSnapshot.Entry after = candidate.entries().get(path);
			if (!same(before, after))
				changes.add(new Change(path, before, after));
		}
		return new CandidateDiff(changes);
	}

	/**
	 * Validates candidate changes against typed confirmed write authority while permitting unchanged read-only
	 * source entries to remain in both snapshots.
	 *
	 * @param predecessor complete predecessor snapshot
	 * @param candidate complete candidate snapshot
	 * @param authority confirmed editable roots
	 * @return changed editable paths
	 * @throws IOException if a candidate changes a path outside the authority
	 */
	public static CandidateDiff validate(PromptSnapshot predecessor, PromptSnapshot candidate,
		DeliveryWriteAuthority authority) throws IOException
	{
		return validate(predecessor, candidate, authority, null);
	}

	/**
	 * Treats a matching confirmed read-only source as unchanged when an older incumbent omitted that source.
	 *
	 * @param predecessor applied predecessor
	 * @param candidate captured candidate
	 * @param authority confirmed edit authority
	 * @param baseline confirmed source states, or null when the predecessor is already complete
	 * @return changed editable paths
	 * @throws IOException if candidate changes a noneditable source
	 */
	static CandidateDiff validate(PromptSnapshot predecessor, PromptSnapshot candidate,
		DeliveryWriteAuthority authority, ConfirmedSourceBaseline baseline) throws IOException
	{
		Objects.requireNonNull(predecessor, "predecessor");
		Objects.requireNonNull(candidate, "candidate");
		Objects.requireNonNull(authority, "authority");
		Set<Path> paths = new TreeSet<>(Comparator.comparing(Path::toString));
		paths.addAll(predecessor.entries().keySet());
		paths.addAll(candidate.entries().keySet());
		List<Change> changes = new ArrayList<>();
		for (Path path: paths)
		{
			PromptSnapshot.Entry before = predecessor.entries().get(path);
			PromptSnapshot.Entry after = candidate.entries().get(path);
			if (same(before, after))
				continue;
			PromptSnapshot.Entry present = before;
			if (present == null)
				present = after;
			PromptSnapshot.Entry matchingEntry = present;
			if ((before == null || after == null) && !authority.permits(path) && baseline != null &&
				baseline.orderedKnownSourceStates().stream().anyMatch(state -> state.path().equals(path) &&
					state.presence() == KnownSourcePresence.REGULAR &&
					state.contentSha256().orElseThrow().equals(matchingEntry.hash())))
				continue;
			if (!authority.permits(path))
				throw new IOException("Candidate changed a path outside confirmed editable roots: " + path);
			if (before != null && before.kind() != PromptSnapshot.FileKind.REGULAR ||
				after != null && after.kind() != PromptSnapshot.FileKind.REGULAR)
				throw new IOException("Unsupported candidate artifact kind: " + path);
			changes.add(new Change(path, before, after));
		}
		return new CandidateDiff(changes);
	}

	/**
	 * Validates a returned candidate project against an isolated baseline project, rejecting every changed path that is
	 * not under a confirmed prompt root.
	 *
	 * @param baselineProject the isolated project before candidate generation
	 * @param candidateProject the isolated project returned by candidate generation
	 * @param confirmedRoots confirmed project-relative prompt roots
	 * @return the validated prompt-only candidate diff
	 * @throws IOException if either tree has unsupported artifacts or a non-prompt write
	 */
	public static CandidateDiff validate(Path baselineProject, Path candidateProject, Collection<Path> confirmedRoots)
		throws IOException
	{
		List<Path> roots = normalizedRoots(confirmedRoots);
		PromptSnapshot baselineTree = PromptSnapshot.capture(baselineProject, List.of(baselineProject));
		PromptSnapshot candidateTree = PromptSnapshot.capture(candidateProject, List.of(candidateProject));
		Set<Path> paths = new TreeSet<>(Comparator.comparing(Path::toString));
		paths.addAll(baselineTree.entries().keySet());
		paths.addAll(candidateTree.entries().keySet());
		for (Path path: paths)
		{
			if (!same(baselineTree.entries().get(path), candidateTree.entries().get(path)) &&
				roots.stream().noneMatch(path::startsWith))
				throw new IOException("Candidate writes a non-prompt path: " + path);
		}
		PromptSnapshot baselinePrompts = PromptSnapshot.capture(baselineProject, roots);
		PromptSnapshot candidatePrompts = PromptSnapshot.capture(candidateProject, roots);
		return validate(baselinePrompts, candidatePrompts, roots);
	}

	/**
	 * Returns the changed prompt paths in deterministic order.
	 *
	 * @return structured prompt changes
	 */
	public List<Change> changes()
	{
		return changes;
	}

	/**
	 * Normalizes and validates confirmed roots.
	 *
	 * @param confirmedRoots roots supplied by contract confirmation
	 * @return normalized project-relative roots
	 */
	private static List<Path> normalizedRoots(Collection<Path> confirmedRoots) throws IOException
	{
		Objects.requireNonNull(confirmedRoots, "confirmedRoots");
		if (confirmedRoots.isEmpty())
			throw new IOException("At least one confirmed prompt root is required");
		List<Path> roots = new ArrayList<>();
		for (Path root: confirmedRoots)
		{
			if (root == null || root.isAbsolute())
				throw new IOException("Prompt root must be project-relative: " + root);
			Path normalized = root.normalize();
			if (normalized.getNameCount() == 0 || normalized.startsWith(".."))
				throw new IOException("Prompt root escapes the project: " + root);
			roots.add(normalized);
		}
		return List.copyOf(roots);
	}

	/**
	 * Rejects entries outside roots or with unsupported kinds.
	 *
	 * @param entries manifest entries to validate
	 * @param roots normalized permitted prompt roots
	 */
	private static void validateEntries(Map<Path, PromptSnapshot.Entry> entries, List<Path> roots) throws IOException
	{
		for (Map.Entry<Path, PromptSnapshot.Entry> entry: entries.entrySet())
		{
			Path path = entry.getKey();
			if (path.isAbsolute() || path.normalize().startsWith("..") || roots.stream().noneMatch(path::startsWith))
				throw new IOException("Candidate writes a non-prompt path: " + path);
			if (entry.getValue().kind() != PromptSnapshot.FileKind.REGULAR)
				throw new IOException("Unsupported candidate artifact kind: " + entry.getValue().kind());
		}
	}

	/**
	 * Compares two optional entries by immutable content identity.
	 *
	 * @param left the predecessor entry
	 * @param right the candidate entry
	 * @return whether both entries are absent or have identical kind and hash
	 */
	private static boolean same(PromptSnapshot.Entry left, PromptSnapshot.Entry right)
	{
		return left == right || left != null && right != null && left.kind() == right.kind() &&
			left.hash().equals(right.hash());
	}

	/**
	 * One changed prompt path. A {@code null} before or after entry represents creation or deletion respectively.
	 *
	 * @param path the project-relative prompt path
	 * @param before expected predecessor content, or {@code null}
	 * @param after candidate content, or {@code null}
	 */
	public record Change(Path path, PromptSnapshot.Entry before, PromptSnapshot.Entry after)
	{
	}
}
