package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SpecialFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SymbolicLink;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The recorded files of a project and of the external files it reaches, read without following symbolic links.
 * <p>
 * Each entry is keyed by its path relative to the project directory or, for a file outside the project, by its
 * absolute path. Relative keys let inventories of different copies of the same project, such as the base worktree and
 * the optimization worktree, be compared path by path.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FileInventory
{
	private final Path root;
	private final SortedMap<Path, FileEntry> entries;

	/**
	 * Creates an inventory.
	 *
	 * @param root the absolute project directory, against which relative entry paths and absolute link destinations
	 *             are interpreted
	 * @param entries each recorded entry, keyed by its normalized path relative to {@code root} or, for a file outside
	 *                {@code root}, by its normalized absolute path
	 * @throws NullPointerException if {@code root} or {@code entries} are null, or if {@code entries} contains a null
	 *                              key or value
	 * @throws IllegalArgumentException if {@code root} is relative, or if a key is empty, not normalized, starts with
	 *                                  {@code ..}, or is an absolute path inside {@code root}
	 */
	public FileInventory(Path root, Map<Path, FileEntry> entries)
	{
		requireThat(root, "root").isAbsolute();
		requireThat(entries, "entries").isNotNull();
		root = root.normalize();
		SortedMap<Path, FileEntry> sorted = new TreeMap<>();
		for (Entry<Path, FileEntry> entry : entries.entrySet())
		{
			Path path = entry.getKey();
			requireInventoryPath(path, "path");
			if (path.isAbsolute())
				requireThat(path.startsWith(root), "path.startsWith(root)").isFalse();
			requireThat(entry.getValue(), "entries.get(" + path + ")").isNotNull();
			sorted.put(path, entry.getValue());
		}
		this.root = root;
		this.entries = Collections.unmodifiableSortedMap(sorted);
	}

	/**
	 * Validates a path that identifies an inventory entry or an overlay target.
	 *
	 * @param path a normalized path relative to a project directory, or a normalized absolute path
	 * @param name the name of the value being validated
	 * @throws NullPointerException if {@code path} is null
	 * @throws IllegalArgumentException if {@code path} is empty, not normalized or starts with {@code ..}
	 */
	public static void requireInventoryPath(Path path, String name)
	{
		requireThat(path, name).isEqualTo(path.normalize(), "normalized");
		requireThat(path.toString(), name).isNotEmpty();
		if (!path.isAbsolute())
			requireThat(path.getName(0).toString(), name + ".getName(0)").isNotEqualTo("..");
	}

	/**
	 * Returns the project directory.
	 *
	 * @return the absolute project directory
	 */
	public Path root()
	{
		return root;
	}

	/**
	 * Returns the recorded entries.
	 *
	 * @return an unmodifiable map from each recorded path to its entry, in path order
	 */
	public Map<Path, FileEntry> entries()
	{
		return entries;
	}

	/**
	 * Returns the entry recorded at a path.
	 *
	 * @param path a relative project path or an absolute external path
	 * @return the entry, or empty if nothing is recorded at {@code path}
	 */
	public Optional<FileEntry> entry(Path path)
	{
		return Optional.ofNullable(entries.get(path));
	}

	/**
	 * Indicates whether this inventory records a path or any path below it.
	 *
	 * @param path a relative project path or an absolute external path
	 * @return {@code true} if an entry is recorded at or below {@code path}
	 */
	public boolean holds(Path path)
	{
		return entries.keySet().stream().anyMatch(key -> key.startsWith(path));
	}

	/**
	 * Returns the first recorded path strictly below a path, which shows that the path is a directory.
	 *
	 * @param path a relative project path or an absolute external path
	 * @return the first such path in path order, or empty if none is recorded
	 */
	Optional<Path> firstPathBelow(Path path)
	{
		for (Path candidate : entries.keySet())
		{
			if (candidate.startsWith(path) && !candidate.equals(path))
				return Optional.of(candidate);
		}
		return Optional.empty();
	}

	/**
	 * Resolves the recorded symbolic links on a path.
	 * <p>
	 * Starting from the shortest prefix, each prefix of the path that is a recorded symbolic link is replaced by the
	 * link's destination, interpreted relative to the link's directory, until no prefix is a recorded link. A
	 * destination inside the project is returned relative to the project directory; any other destination is absolute.
	 * Links that are not recorded, such as links inside an external directory that was never scanned, are not
	 * followed.
	 *
	 * @param path a relative project path or an absolute external path
	 * @return the resolved path, which equals {@code path} if no prefix is a recorded link, or empty if the links form a
	 *   cycle
	 * @throws NullPointerException if {@code path} is null
	 * @throws IllegalArgumentException if {@code path} is empty, not normalized or starts with {@code ..}
	 */
	public Optional<Path> resolve(Path path)
	{
		requireInventoryPath(path, "path");
		long remainingHops = entries.values().stream().filter(SymbolicLink.class::isInstance).count();
		Path current = path;
		while (true)
		{
			Optional<Path> next = resolveFirstLink(current);
			if (next.isEmpty())
				return Optional.of(current);
			if (remainingHops == 0)
				return Optional.empty();
			--remainingHops;
			current = next.get();
		}
	}

	/**
	 * Replaces the shortest prefix of a path that is a recorded symbolic link by the link's destination.
	 *
	 * @param path a relative project path or an absolute external path
	 * @return the path after the replacement, or empty if no prefix of {@code path} is a recorded link
	 */
	private Optional<Path> resolveFirstLink(Path path)
	{
		Optional<Path> found = firstLinkPrefix(path);
		if (found.isEmpty())
			return Optional.empty();
		Path prefix = found.get();
		SymbolicLink link = (SymbolicLink) entries.get(prefix);
		Path destination = root.resolve(prefix).getParent().resolve(link.target()).normalize();
		int count = path.getNameCount();
		int linkCount = prefix.getNameCount();
		if (linkCount < count)
			destination = destination.resolve(path.subpath(linkCount, count));
		return Optional.of(keyOf(root, destination));
	}

	/**
	 * Returns the shortest prefix of a path that is a recorded symbolic link.
	 *
	 * @param path a relative project path or an absolute external path
	 * @return the prefix, which is absolute if {@code path} is absolute and may be {@code path} itself, or empty if no
	 *   prefix of {@code path} is a recorded link
	 */
	Optional<Path> firstLinkPrefix(Path path)
	{
		for (int i = 1; i <= path.getNameCount(); ++i)
		{
			Path prefix = path.subpath(0, i);
			if (path.isAbsolute())
				prefix = path.getRoot().resolve(prefix);
			if (entries.get(prefix) instanceof SymbolicLink)
				return Optional.of(prefix);
		}
		return Optional.empty();
	}

	/**
	 * Converts an absolute path to the key under which an inventory records it.
	 *
	 * @param root the inventory's absolute, normalized project directory
	 * @param absolute a normalized absolute path
	 * @return the path relative to {@code root} if it lies inside it; otherwise {@code absolute}
	 */
	static Path keyOf(Path root, Path absolute)
	{
		if (absolute.startsWith(root))
			return root.relativize(absolute);
		return absolute;
	}

	/**
	 * Returns the identity of the recorded entries: a digest of every path with its kind, content hash, mode and link
	 * destination. The project directory does not contribute, so two copies of the same files have the same identity.
	 *
	 * @return the identity
	 */
	public Sha256 identity()
	{
		CanonicalDigest digest = new CanonicalDigest("cat-file-inventory-v1");
		for (Entry<Path, FileEntry> entry : entries.entrySet())
			addEntry(digest, entry.getKey(), entry.getValue());
		return digest.finish();
	}

	/**
	 * Adds one entry to an identity digest.
	 *
	 * @param digest the digest
	 * @param path the entry's path
	 * @param entry the entry
	 */
	private static void addEntry(CanonicalDigest digest, Path path, FileEntry entry)
	{
		digest.text(PortablePaths.text(path));
		switch (entry)
		{
			case RegularFile file -> digest.text("file").text(file.contentHash().hex()).
				text(PosixFilePermissions.toString(file.permissions()));
			case SymbolicLink link -> digest.text("link").text(PortablePaths.text(link.target()));
			case SpecialFile _ -> digest.text("special");
		}
	}

	@Override
	public boolean equals(Object obj)
	{
		return obj instanceof FileInventory other && root.equals(other.root) && entries.equals(other.entries);
	}

	@Override
	public int hashCode()
	{
		return entries.hashCode();
	}

	@Override
	public String toString()
	{
		return "FileInventory[root=" + root + ", entries=" + entries + "]";
	}
}
