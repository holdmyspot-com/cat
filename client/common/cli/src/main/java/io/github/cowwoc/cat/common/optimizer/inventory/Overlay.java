package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.OverlayEntry.Delete;
import io.github.cowwoc.cat.common.optimizer.inventory.OverlayEntry.Write;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A candidate: the fork's recorded files plus an overlay of ordinary-file content edits, creates and deletes. A
 * rename is a delete of the old path and a create of the new one.
 * <p>
 * Each overlay entry is keyed by the path it changes after symbolic links are resolved, so an edit made at a link path
 * is recorded at the link's target. The overlay holds only differences from the fork: writing a file's recorded
 * content and mode back, or deleting a file the fork did not record, removes the entry. Two overlays that describe the
 * same files on the same fork therefore have the same {@link #identity() identity}.
 * <p>
 * The overlay does not decide whether a change is authorized; {@link Authority} does.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class Overlay
{
	private final FileInventory fork;
	private final SortedMap<Path, OverlayEntry> entries;

	/**
	 * Creates an overlay.
	 *
	 * @param fork the files recorded at the fork
	 * @param entries the overlay entries, which this overlay takes ownership of
	 */
	private Overlay(FileInventory fork, SortedMap<Path, OverlayEntry> entries)
	{
		this.fork = fork;
		this.entries = Collections.unmodifiableSortedMap(entries);
	}

	/**
	 * Returns the overlay with no edits, which describes the incumbent.
	 *
	 * @param fork the files recorded at the fork
	 * @return the empty overlay
	 * @throws NullPointerException if {@code fork} is null
	 */
	public static Overlay of(FileInventory fork)
	{
		requireThat(fork, "fork").isNotNull();
		return new Overlay(fork, new TreeMap<>());
	}

	/**
	 * Returns the files recorded at the fork.
	 *
	 * @return the fork's inventory
	 */
	public FileInventory fork()
	{
		return fork;
	}

	/**
	 * Returns this candidate's edits on a fork that holds every file of this overlay's fork, and possibly more. A
	 * confirmed revision that records more files moves the campaign's candidates onto such a fork.
	 *
	 * @param larger the larger fork's files
	 * @return the overlay on {@code larger}
	 * @throws NullPointerException if {@code larger} is null
	 * @throws IllegalArgumentException if {@code larger} lacks a file of this overlay's fork or records it differently,
	 *   or an edit is not valid on {@code larger} (see {@link #write(Path, FileContent, Set)})
	 */
	public Overlay onto(FileInventory larger)
	{
		requireThat(larger, "larger").isNotNull();
		for (Entry<Path, FileEntry> entry : fork.entries().entrySet())
		{
			if (!larger.entry(entry.getKey()).equals(Optional.of(entry.getValue())))
			{
				throw new IllegalArgumentException("The larger fork does not hold " + entry.getKey() + " as this " +
					"overlay's fork records it.");
			}
		}
		Overlay moved = of(larger);
		for (Entry<Path, OverlayEntry> entry : entries.entrySet())
		{
			moved = switch (entry.getValue())
			{
				case Write write -> moved.write(entry.getKey(), write.content(), write.permissions());
				case Delete _ -> moved.delete(entry.getKey());
			};
		}
		return moved;
	}

	/**
	 * Returns a candidate in which an ordinary file has the given content and mode.
	 *
	 * @param target the file's resolved path: relative to the project directory, or absolute for an external target
	 * @param content the file's bytes
	 * @param permissions the file's mode
	 * @return the resulting overlay; this overlay is unchanged
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code target} is not a valid inventory path, if the fork reaches
	 *                                  {@code target} through a symbolic link, or if the fork records something other
	 *                                  than a regular file at {@code target}
	 */
	public Overlay write(Path target, FileContent content, Set<PosixFilePermission> permissions)
	{
		Write write = new Write(content, permissions);
		Optional<RegularFile> recorded = requireOrdinaryTarget(target);
		if (recorded.isPresent() && recorded.get().equals(new RegularFile(content.hash(), write.permissions())))
			return without(target);
		return with(target, write);
	}

	/**
	 * Returns a candidate in which an ordinary file does not exist.
	 *
	 * @param target the file's resolved path: relative to the project directory, or absolute for an external target
	 * @return the resulting overlay; this overlay is unchanged
	 * @throws NullPointerException if {@code target} is null
	 * @throws IllegalArgumentException if {@code target} is not a valid inventory path, if the fork reaches
	 *                                  {@code target} through a symbolic link, or if the fork records something other
	 *                                  than a regular file at {@code target}
	 */
	public Overlay delete(Path target)
	{
		Optional<RegularFile> recorded = requireOrdinaryTarget(target);
		if (recorded.isEmpty())
			return without(target);
		return with(target, new Delete());
	}

	/**
	 * Validates the target of an overlay entry.
	 *
	 * @param target the file's resolved path
	 * @return the regular file the fork records at {@code target}, or empty if it records nothing there
	 * @throws NullPointerException if {@code target} is null
	 * @throws IllegalArgumentException if {@code target} is not a valid inventory path, if the fork reaches
	 *                                  {@code target} through a symbolic link, or if the fork records something other
	 *                                  than a regular file at {@code target}
	 */
	private Optional<RegularFile> requireOrdinaryTarget(Path target)
	{
		Optional<Path> resolved = fork.resolve(target);
		if (!resolved.equals(Optional.of(target)))
		{
			throw new IllegalArgumentException("The fork reaches " + target + " through a symbolic link, so an edit " +
				"there applies to its resolved target " + resolved.map(Path::toString).orElse("(a link cycle)") + ".");
		}
		Optional<FileEntry> recorded = fork.entry(target);
		if (recorded.isEmpty())
			return Optional.empty();
		if (recorded.get() instanceof RegularFile file)
			return Optional.of(file);
		throw new IllegalArgumentException("The fork records " + target + " as " + recorded.get() + ", but an overlay " +
			"changes only regular files.");
	}

	/**
	 * Returns this overlay with one entry set.
	 *
	 * @param target the entry's path
	 * @param entry the entry
	 * @return the resulting overlay
	 */
	private Overlay with(Path target, OverlayEntry entry)
	{
		SortedMap<Path, OverlayEntry> updated = new TreeMap<>(entries);
		updated.put(target, entry);
		return new Overlay(fork, updated);
	}

	/**
	 * Returns this overlay without an entry.
	 *
	 * @param target the entry's path
	 * @return the resulting overlay
	 */
	private Overlay without(Path target)
	{
		SortedMap<Path, OverlayEntry> updated = new TreeMap<>(entries);
		updated.remove(target);
		return new Overlay(fork, updated);
	}

	/**
	 * Returns the overlay's entries.
	 *
	 * @return an unmodifiable map from each changed path to its state in the candidate, in path order
	 */
	public Map<Path, OverlayEntry> entries()
	{
		return entries;
	}

	/**
	 * Returns the candidate's identity: a digest of the fork's {@link FileInventory#identity() identity} and of every
	 * overlay entry's path, kind, content hash and mode.
	 *
	 * @return the identity
	 */
	public Sha256 identity()
	{
		CanonicalDigest digest = new CanonicalDigest("cat-candidate-v1").text(fork.identity().hex());
		for (Entry<Path, OverlayEntry> entry : entries.entrySet())
		{
			digest.text(PortablePaths.text(entry.getKey()));
			switch (entry.getValue())
			{
				case Write write -> digest.text("write").text(write.content().hash().hex()).
					text(PosixFilePermissions.toString(write.permissions()));
				case Delete _ -> digest.text("delete");
			}
		}
		return digest.finish();
	}

	/**
	 * Returns the candidate's files: the fork's inventory with every overlay entry applied.
	 *
	 * @return the inventory
	 */
	public FileInventory snapshot()
	{
		Map<Path, FileEntry> files = new HashMap<>(fork.entries());
		for (Entry<Path, OverlayEntry> entry : entries.entrySet())
		{
			switch (entry.getValue())
			{
				case Write write -> files.put(entry.getKey(),
					new RegularFile(write.content().hash(), write.permissions()));
				case Delete _ -> files.remove(entry.getKey());
			}
		}
		return new FileInventory(fork.root(), files);
	}

	@Override
	public boolean equals(Object obj)
	{
		return obj instanceof Overlay other && fork.equals(other.fork) && entries.equals(other.entries);
	}

	@Override
	public int hashCode()
	{
		return entries.hashCode();
	}

	@Override
	public String toString()
	{
		return "Overlay[entries=" + entries + "]";
	}
}
