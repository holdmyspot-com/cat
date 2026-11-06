package com.holdmyspot.cat.common.runner.policy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The sandbox policy of a parent session's commands, independent of the harness that enforces it. It bounds every
 * child session that CAT starts for the parent: a child never gets an access that this policy does not give.
 * <p>
 * A command may write a path that lies inside a write root unless the path also lies inside a write denial. It may
 * read a path that lies inside a read re-allowance, and every other path that lies inside no read denial; a
 * re-allowance thus re-opens every read denial inside it or around it. Paths compare by the files that they reach,
 * after symbolic links and {@code ..} names resolve.
 *
 * @param sandboxed         {@code true} if the harness limits the commands; {@code false} if the commands are
 *                          unrestricted, in which case every other component is ignored
 * @param writeRoots        the absolute folders that the commands may write
 * @param writeDenials      the absolute paths inside the write roots that the commands may not write
 * @param readDenials       the absolute paths that the commands may not read
 * @param readReallowances  the absolute paths inside read denials that the commands may read nonetheless
 * @param hosts             the network hosts that the commands may reach
 * @param allowLocalBinding {@code true} if the commands may listen on local network addresses
 */
public record ParentPolicy(boolean sandboxed, List<Path> writeRoots, List<Path> writeDenials, List<Path> readDenials,
	List<Path> readReallowances, HostAccess hosts, boolean allowLocalBinding)
{
	/**
	 * The policy of a parent whose commands are unrestricted.
	 */
	public static final ParentPolicy UNSANDBOXED = new ParentPolicy(false, List.of(), List.of(), List.of(), List.of(),
		HostAccess.ALL, true);

	/**
	 * Creates a policy.
	 *
	 * @param sandboxed         {@code true} if the harness limits the commands; {@code false} if the commands are
	 *                          unrestricted, in which case every other component is ignored
	 * @param writeRoots        the absolute folders that the commands may write
	 * @param writeDenials      the absolute paths inside the write roots that the commands may not write
	 * @param readDenials       the absolute paths that the commands may not read
	 * @param readReallowances  the absolute paths inside read denials that the commands may read nonetheless
	 * @param hosts             the network hosts that the commands may reach
	 * @param allowLocalBinding {@code true} if the commands may listen on local network addresses
	 * @throws NullPointerException     if any of the arguments are null, or a list contains null
	 * @throws IllegalArgumentException if a path is not absolute
	 */
	public ParentPolicy
	{
		writeRoots = absolutePaths(writeRoots, "writeRoots");
		writeDenials = absolutePaths(writeDenials, "writeDenials");
		readDenials = absolutePaths(readDenials, "readDenials");
		readReallowances = absolutePaths(readReallowances, "readReallowances");
		requireThat(hosts, "hosts").isNotNull();
	}

	/**
	 * Checks that a list holds absolute paths, and copies it.
	 *
	 * @param paths the list
	 * @param name  the name of the list
	 * @return an unmodifiable copy of {@code paths}
	 * @throws NullPointerException     if {@code paths} is null or contains null
	 * @throws IllegalArgumentException if a path is not absolute
	 */
	static List<Path> absolutePaths(List<Path> paths, String name)
	{
		requireThat(paths, name).isNotNull();
		for (int index = 0; index < paths.size(); ++index)
		{
			Path path = paths.get(index);
			requireThat(path, name + "[" + index + "]").isNotNull();
			if (!path.isAbsolute())
				throw new IllegalArgumentException(name + " must hold absolute paths: " + path);
		}
		return List.copyOf(paths);
	}

	/**
	 * Returns the policy that gives only the accesses that both this policy and another one give. A reader uses it to
	 * combine the policy without a source whose effect on the parent is uncertain with the policy that includes the
	 * source, so that the source contributes only the entries that narrow the policy.
	 *
	 * @param other the other policy of the same parent
	 * @return the combined policy, whose paths are resolved
	 * @throws NullPointerException if {@code other} is null
	 * @throws IOException          if a symbolic link in a path cannot be read, or a path follows more than 40 links
	 */
	public ParentPolicy intersect(ParentPolicy other) throws IOException
	{
		requireThat(other, "other").isNotNull();
		if (!sandboxed)
			return other.resolved();
		if (!other.sandboxed)
			return resolved();
		ParentPolicy first = resolved();
		ParentPolicy second = other.resolved();

		// Two write roots give a common write only where one lies inside the other
		Set<Path> commonWriteRoots = new LinkedHashSet<>();
		for (Path firstRoot : first.writeRoots)
		{
			for (Path secondRoot : second.writeRoots)
			{
				if (firstRoot.startsWith(secondRoot))
					commonWriteRoots.add(firstRoot);
				else if (secondRoot.startsWith(firstRoot))
					commonWriteRoots.add(secondRoot);
			}
		}
		Set<Path> commonReallowances = new LinkedHashSet<>();
		for (Path reallowance : first.readReallowances)
		{
			if (readsWhole(reallowance, second.readDenials, second.readReallowances))
				commonReallowances.add(reallowance);
		}
		for (Path reallowance : second.readReallowances)
		{
			if (readsWhole(reallowance, first.readDenials, first.readReallowances))
				commonReallowances.add(reallowance);
		}
		return new ParentPolicy(true, List.copyOf(commonWriteRoots), union(first.writeDenials, second.writeDenials),
			union(first.readDenials, second.readDenials), List.copyOf(commonReallowances),
			commonHosts(first.hosts, second.hosts), first.allowLocalBinding && second.allowLocalBinding);
	}

	/**
	 * Returns this policy with its paths resolved.
	 *
	 * @return the policy
	 * @throws IOException if a symbolic link in a path cannot be read, or a path follows more than 40 links
	 */
	private ParentPolicy resolved() throws IOException
	{
		return new ParentPolicy(sandboxed, RealPaths.resolveAll(writeRoots), RealPaths.resolveAll(writeDenials),
			RealPaths.resolveAll(readDenials), RealPaths.resolveAll(readReallowances), hosts, allowLocalBinding);
	}

	/**
	 * Returns the paths of two lists.
	 *
	 * @param first  the first list
	 * @param second the second list
	 * @return each path once, those of {@code first} first
	 */
	private static List<Path> union(List<Path> first, List<Path> second)
	{
		Set<Path> union = new LinkedHashSet<>(first);
		union.addAll(second);
		return List.copyOf(union);
	}

	/**
	 * Returns the hosts that two accesses both let the commands reach.
	 *
	 * @param first  the first access
	 * @param second the second access
	 * @return the common access
	 */
	private static HostAccess commonHosts(HostAccess first, HostAccess second)
	{
		return switch (first)
		{
			case HostAccess.NoHosts _ -> HostAccess.NONE;
			case HostAccess.AllHosts _ -> second;
			case HostAccess.ListedHosts firstListed -> switch (second)
			{
				case HostAccess.NoHosts _ -> HostAccess.NONE;
				case HostAccess.AllHosts _ -> first;
				case HostAccess.ListedHosts secondListed ->
				{
					// A pattern stays when the other access lets the commands reach every host that it matches
					Set<String> common = new LinkedHashSet<>();
					for (String pattern : firstListed.patterns())
					{
						if (second.covers(pattern))
							common.add(pattern);
					}
					for (String pattern : secondListed.patterns())
					{
						if (first.covers(pattern))
							common.add(pattern);
					}
					yield HostAccess.listed(common);
				}
			};
		};
	}

	/**
	 * Indicates whether commands may read every path inside a folder.
	 *
	 * @param folder           a resolved folder
	 * @param readDenials      the resolved paths that the commands may not read
	 * @param readReallowances the resolved paths inside read denials that the commands may read nonetheless
	 * @return {@code true} if {@code folder} lies inside a re-allowance, or no read denial lies inside it or around
	 *   it
	 */
	static boolean readsWhole(Path folder, List<Path> readDenials, List<Path> readReallowances)
	{
		if (RealPaths.isInsideAny(folder, readReallowances))
			return true;
		for (Path denial : readDenials)
		{
			if (denial.startsWith(folder) || folder.startsWith(denial))
				return false;
		}
		return true;
	}

	/**
	 * Indicates whether the parent's commands may read a file: it lies outside every read denial, or inside a
	 * re-allowance. Every file is readable when the parent is not sandboxed.
	 *
	 * @param file the absolute path of the file
	 * @return {@code true} if the parent's commands may read {@code file}
	 * @throws NullPointerException     if {@code file} is null
	 * @throws IllegalArgumentException if {@code file} is not absolute
	 * @throws IOException              if a symbolic link in a path cannot be read, or a path follows more than 40 links
	 */
	public boolean mayRead(Path file) throws IOException
	{
		requireThat(file, "file").isNotNull();
		if (!file.isAbsolute())
			throw new IllegalArgumentException("file must be absolute: " + file);
		return !sandboxed || readsWhole(RealPaths.resolve(file), RealPaths.resolveAll(readDenials),
			RealPaths.resolveAll(readReallowances));
	}
}
