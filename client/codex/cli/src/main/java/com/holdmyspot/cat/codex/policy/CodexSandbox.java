package com.holdmyspot.cat.codex.policy;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.policy.PermissionEntry.Access;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * A Codex session's sandbox, as Codex resolves it from a permissions profile: file system entries and network access.
 *
 * @param sandboxed         {@code false} if Codex runs the commands without a sandbox
 * @param entries           the file system entries
 * @param hosts             the hosts that the commands may reach
 * @param allowLocalBinding {@code true} if the commands may listen on local network addresses
 */
public record CodexSandbox(boolean sandboxed, List<PermissionEntry> entries, HostAccess hosts,
	boolean allowLocalBinding)
{
	/**
	 * A session without a sandbox.
	 */
	public static final CodexSandbox UNSANDBOXED = new CodexSandbox(false, List.of(), HostAccess.ALL, true);
	/**
	 * The folders inside each writable root that Codex keeps its sandboxed commands from writing, so that they cannot
	 * change the checkout's history or Codex's and other agents' project configuration.
	 */
	private static final List<String> PROTECTED_NAMES = List.of(".git", ".codex", ".agents", ".aws");

	/**
	 * Creates a sandbox.
	 *
	 * @param sandboxed         {@code false} if Codex runs the commands without a sandbox
	 * @param entries           the file system entries
	 * @param hosts             the hosts that the commands may reach
	 * @param allowLocalBinding {@code true} if the commands may listen on local network addresses
	 * @throws NullPointerException if any argument is null
	 */
	public CodexSandbox
	{
		entries = List.copyOf(entries);
		requireThat(hosts, "hosts").isNotNull();
	}

	/**
	 * Returns the policy in CAT's terms. Where Codex's rules cannot be expressed exactly, the policy is narrower.
	 * <p>
	 * The most specific entry decides a path, and of two entries for the same path {@code deny} wins over
	 * {@code write}, and {@code write} over {@code read}. A path that no entry covers can be neither read nor written.
	 *
	 * @return the policy
	 * @throws IOException if a symbolic link in a path cannot be read
	 */
	public ParentPolicy toParentPolicy() throws IOException
	{
		if (!sandboxed)
			return ParentPolicy.UNSANDBOXED;
		Map<Path, Access> accessByPath = new LinkedHashMap<>();
		for (PermissionEntry entry : entries)
			accessByPath.merge(realPath(entry.path()), entry.access(), Access::winner);

		List<Path> writeRoots = new ArrayList<>();
		Set<Path> writeDenials = new LinkedHashSet<>();
		Set<Path> readDenials = new LinkedHashSet<>();
		boolean rootReadable = false;
		for (Entry<Path, Access> entry : accessByPath.entrySet())
		{
			Path path = entry.getKey();
			switch (entry.getValue())
			{
				case WRITE ->
				{
					writeRoots.add(path);
					for (String name : PROTECTED_NAMES)
						writeDenials.add(path.resolve(name));
				}
				case DENY -> readDenials.add(path);
				case READ ->
				{
					// Only the entries inside a write root and outside every denial matter
				}
			}
			if (entry.getValue() != Access.DENY && path.getNameCount() == 0)
				rootReadable = true;
		}
		if (!rootReadable)
		{
			for (Path root : FileSystems.getDefault().getRootDirectories())
				readDenials.add(root);
		}
		for (Path root : writeRoots)
		{
			for (Entry<Path, Access> entry : accessByPath.entrySet())
			{
				Path path = entry.getKey();
				if (entry.getValue() != Access.WRITE && path.startsWith(root) && !path.equals(root))
					writeDenials.add(path);
			}
		}
		List<Path> readReallowances = new ArrayList<>();
		for (Entry<Path, Access> entry : accessByPath.entrySet())
		{
			Path path = entry.getKey();
			if (entry.getValue() != Access.DENY && isStrictlyInsideAny(path, readDenials) &&
				!holdsDenial(path, accessByPath))
			{
				readReallowances.add(path);
			}
		}
		return new ParentPolicy(true, writeRoots, List.copyOf(writeDenials), List.copyOf(readDenials),
			readReallowances, hosts, allowLocalBinding);
	}

	/**
	 * Indicates whether a path lies strictly inside one of some folders.
	 *
	 * @param path    the path
	 * @param folders the folders
	 * @return {@code true} if {@code path} lies below one of {@code folders}
	 */
	private static boolean isStrictlyInsideAny(Path path, Set<Path> folders)
	{
		for (Path folder : folders)
		{
			if (path.startsWith(folder) && !path.equals(folder))
				return true;
		}
		return false;
	}

	/**
	 * Indicates whether a {@code deny} entry lies below a path. A re-allowance of the path would re-open it.
	 *
	 * @param path         the path
	 * @param accessByPath the access of each entry's path
	 * @return {@code true} if a {@code deny} entry lies below {@code path}
	 */
	private static boolean holdsDenial(Path path, Map<Path, Access> accessByPath)
	{
		for (Entry<Path, Access> entry : accessByPath.entrySet())
		{
			if (entry.getValue() == Access.DENY && entry.getKey().startsWith(path) && !entry.getKey().equals(path))
				return true;
		}
		return false;
	}

	/**
	 * Returns the real path of the nearest existing ancestor of a path, followed by the rest of the path's names, so
	 * that two entries that reach the same file through different links compare equal.
	 *
	 * @param path an absolute path
	 * @return the resolved path
	 * @throws IOException if a symbolic link cannot be read
	 */
	private static Path realPath(Path path) throws IOException
	{
		Path normalized = path.normalize();
		Path existing = normalized;
		while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS))
			existing = existing.getParent();
		if (existing == null || !Files.exists(existing))
			return normalized;
		return existing.toRealPath().resolve(existing.relativize(normalized)).normalize();
	}
}
