package io.github.cowwoc.cat.common.optimizer.candidate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exact manifest-entry path inventory for every confirmed editable root.
 *
 * @param schemaVersion durable schema version
 * @param roots ordered root inventories
 */
public record RootInventory(int schemaVersion, List<RootInventory.Root> roots)
{
	/** The only supported root inventory schema. */
	public static final int SCHEMA_VERSION = 1;

	/** Validates the inventory's immutable shape. */
	public RootInventory
	{
		if (schemaVersion != SCHEMA_VERSION)
			throw new IllegalArgumentException("Unsupported root inventory schema: " + schemaVersion);
		roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
		if (roots.isEmpty())
			throw new IllegalArgumentException("Root inventory requires a confirmed editable root");
	}

	/**
	 * Derives the complete staged manifest paths beneath each confirmed root.
	 *
	 * @param authority confirmed edit authority
	 * @param snapshot complete frozen candidate
	 * @return exact root inventory
	 */
	public static RootInventory capture(DeliveryWriteAuthority authority, PromptSnapshot snapshot)
	{
		Objects.requireNonNull(authority, "authority");
		Objects.requireNonNull(snapshot, "snapshot");
		List<Root> roots = new ArrayList<>();
		for (EditableRoot editable: authority.roots())
		{
			List<Path> paths = snapshot.entries().keySet().stream().filter(path -> within(path, editable)).
				sorted((left, right) -> left.toString().compareTo(right.toString())).toList();
			roots.add(new Root(editable, paths));
		}
		return new RootInventory(SCHEMA_VERSION, roots);
	}

	/**
	 * Checks membership including exact FILE-root matches.
	 *
	 * @param path candidate path
	 * @param root confirmed root
	 * @return whether the path is an entry beneath this root
	 */
	private static boolean within(Path path, EditableRoot root)
	{
		if (root.kind() == EditableRoot.Kind.FILE)
			return path.equals(root.path());
		return path.startsWith(root.path()) && !path.equals(root.path());
	}

	/**
	 * The complete sorted candidate manifest paths beneath one root.
	 *
	 * @param editableRoot confirmed root and kind
	 * @param manifestEntries exact staged paths
	 */
	public record Root(EditableRoot editableRoot, List<Path> manifestEntries)
	{
		/** Validates one root inventory entry. */
		public Root
		{
			Objects.requireNonNull(editableRoot, "editableRoot");
			manifestEntries = List.copyOf(Objects.requireNonNull(manifestEntries, "manifestEntries"));
			Path previous = null;
			for (Path path: manifestEntries)
			{
				Objects.requireNonNull(path, "manifestPath");
				if (!within(path, editableRoot) || previous != null &&
					previous.toString().compareTo(path.toString()) >= 0)
					throw new IllegalArgumentException("Root inventory paths must be unique, ordered, and under root");
				previous = path;
			}
		}
	}
}
