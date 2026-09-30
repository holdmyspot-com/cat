package io.github.cowwoc.cat.common.optimizer.candidate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable, ordered write authority derived from confirmed editable roots. */
public final class DeliveryWriteAuthority
{
	private final List<EditableRoot> roots;

	/**
	 * Creates an immutable root set.
	 *
	 * @param roots confirmed editable roots
	 */
	public DeliveryWriteAuthority(List<EditableRoot> roots)
	{
		Objects.requireNonNull(roots, "roots");
		if (roots.isEmpty())
			throw new IllegalArgumentException("At least one editable root is required");
		List<EditableRoot> ordered = new ArrayList<>(roots);
		ordered.sort(Comparator.comparing(root -> root.path().toString()));
		Set<Path> paths = new HashSet<>();
		for (EditableRoot root: ordered)
		{
			if (!paths.add(root.path()))
				throw new IllegalArgumentException("Duplicate editable root: " + root.path());
		}
		this.roots = List.copyOf(ordered);
	}

	/**
	 * Returns the normalized ordered root set.
	 *
	 * @return confirmed roots
	 */
	public List<EditableRoot> roots()
	{
		return roots;
	}

	/**
	 * Checks whether a project-relative regular file is writable.
	 *
	 * @param path project-relative pathname
	 * @return whether the confirmed authority grants this pathname
	 */
	public boolean permits(Path path)
	{
		Objects.requireNonNull(path, "path");
		if (path.isAbsolute() || !path.equals(path.normalize()) || path.toString().isEmpty())
			return false;
		for (EditableRoot root: roots)
		{
			if (root.kind() == EditableRoot.Kind.FILE && path.equals(root.path()) ||
				root.kind() == EditableRoot.Kind.DIRECTORY && path.startsWith(root.path()) &&
				!path.equals(root.path()))
				return true;
		}
		return false;
	}
}
