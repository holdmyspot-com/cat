package com.holdmyspot.cat.common.optimizer.inventory.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.RegularFile;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * Builds in-memory {@link FileInventory} values for tests.
 */
final class Inventories
{
	/**
	 * The project root that in-memory inventories describe; no test reads or writes it.
	 */
	static final Path ROOT = Path.of("/project").toAbsolutePath();
	/**
	 * The permissions of an ordinary non-executable file.
	 */
	static final Set<PosixFilePermission> READ_WRITE = PosixFilePermissions.fromString("rw-r--r--");
	/**
	 * The permissions of an executable file.
	 */
	static final Set<PosixFilePermission> EXECUTABLE = PosixFilePermissions.fromString("rwxr-xr-x");

	/**
	 * Prevents instantiation.
	 */
	private Inventories()
	{
	}

	/**
	 * Returns file content holding UTF-8 text.
	 *
	 * @param text the file's text
	 * @return the content
	 */
	static FileContent content(String text)
	{
		return new FileContent(text.getBytes(UTF_8));
	}

	/**
	 * Returns a non-executable regular-file entry holding UTF-8 text.
	 *
	 * @param text the file's text
	 * @return the entry
	 */
	static RegularFile file(String text)
	{
		return file(text, READ_WRITE);
	}

	/**
	 * Returns a regular-file entry holding UTF-8 text.
	 *
	 * @param text the file's text
	 * @param permissions the file's permissions
	 * @return the entry
	 */
	static RegularFile file(String text, Set<PosixFilePermission> permissions)
	{
		return new RegularFile(content(text).hash(), permissions);
	}

	/**
	 * Returns a symbolic-link entry.
	 *
	 * @param target the link's stored destination text
	 * @return the entry
	 */
	static SymbolicLink link(String target)
	{
		return new SymbolicLink(Path.of(target));
	}

	/**
	 * Returns an inventory of {@link #ROOT}.
	 *
	 * @param entries each entry keyed by its project-relative path, or by its absolute path when it lies outside the
	 *                project
	 * @return the inventory
	 */
	static FileInventory inventory(Map<String, ? extends FileEntry> entries)
	{
		Map<Path, FileEntry> converted = new HashMap<>();
		for (Entry<String, ? extends FileEntry> entry : entries.entrySet())
			converted.put(Path.of(entry.getKey()), entry.getValue());
		return new FileInventory(ROOT, converted);
	}
}
