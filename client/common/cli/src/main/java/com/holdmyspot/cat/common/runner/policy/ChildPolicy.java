package com.holdmyspot.cat.common.runner.policy;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The sandbox policy that a child session's harness enforces on the child's commands: its needs, bounded by its
 * parent's policy. Its paths are resolved as {@link ParentPolicy} describes, and the commands may write, read and
 * reach what {@link ParentPolicy} describes for the same components.
 *
 * @param writeRoots        the folders that the commands may write; the first is the child's run folder
 * @param writeDenials      the paths inside the write roots that the commands may not write
 * @param readDenials       the paths that the commands may not read
 * @param readReallowances  the paths inside read denials that the commands may read nonetheless
 * @param hosts             the network hosts that the commands may reach, as host names or literal IP addresses
 * @param allowLocalBinding {@code true} if the commands may listen on local network addresses
 */
public record ChildPolicy(List<Path> writeRoots, List<Path> writeDenials, List<Path> readDenials,
	List<Path> readReallowances, Set<String> hosts, boolean allowLocalBinding)
{
	/**
	 * Creates a policy.
	 *
	 * @param writeRoots        the folders that the commands may write; the first is the child's run folder
	 * @param writeDenials      the paths inside the write roots that the commands may not write
	 * @param readDenials       the paths that the commands may not read
	 * @param readReallowances  the paths inside read denials that the commands may read nonetheless
	 * @param hosts             the network hosts that the commands may reach, as host names or literal IP addresses
	 * @param allowLocalBinding {@code true} if the commands may listen on local network addresses
	 * @throws NullPointerException     if any of the arguments are null, or a collection contains null
	 * @throws IllegalArgumentException if a path is not absolute, or {@code writeRoots} is empty
	 */
	public ChildPolicy
	{
		writeRoots = ParentPolicy.absolutePaths(writeRoots, "writeRoots");
		requireThat(writeRoots, "writeRoots").isNotEmpty();
		writeDenials = ParentPolicy.absolutePaths(writeDenials, "writeDenials");
		readDenials = ParentPolicy.absolutePaths(readDenials, "readDenials");
		readReallowances = ParentPolicy.absolutePaths(readReallowances, "readReallowances");
		requireThat(hosts, "hosts").isNotNull();
		hosts = Set.copyOf(hosts);
	}

	/**
	 * Returns the policy of a child: its needs, bounded by its parent's policy.
	 *
	 * @param parent the parent's policy
	 * @param needs  the child's needs
	 * @return the child's policy, whose paths are resolved
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if a symbolic link in a path cannot be read, or a path follows more than 40 links
	 */
	public static ChildPolicy intersect(ParentPolicy parent, ChildNeeds needs) throws IOException
	{
		requireThat(parent, "parent").isNotNull();
		requireThat(needs, "needs").isNotNull();
		Path runFolder = RealPaths.resolve(needs.runFolder());
		List<Path> extraWrites = RealPaths.resolveAll(needs.extraWrites());
		List<Path> childReadDenials = RealPaths.resolveAll(needs.readDenials());
		Set<Path> writeRoots = new LinkedHashSet<>();
		writeRoots.add(runFolder);
		if (!parent.sandboxed())
		{
			writeRoots.addAll(extraWrites);
			return new ChildPolicy(List.copyOf(writeRoots), List.of(), childReadDenials, List.of(), needs.hosts(),
				hasLoopback(needs.hosts()));
		}

		List<Path> parentWriteRoots = RealPaths.resolveAll(parent.writeRoots());
		List<Path> parentWriteDenials = RealPaths.resolveAll(parent.writeDenials());
		for (Path write : extraWrites)
		{
			boolean parentMayWrite = RealPaths.isInsideAny(write, parentWriteRoots) &&
				!RealPaths.isInsideAny(write, parentWriteDenials);
			if (parentMayWrite || write.startsWith(runFolder))
				writeRoots.add(write);
		}
		List<Path> writeDenials = new ArrayList<>();
		for (Path denial : parentWriteDenials)
		{
			if (RealPaths.isInsideAny(denial, writeRoots))
				writeDenials.add(denial);
		}

		Set<Path> readDenials = new LinkedHashSet<>(RealPaths.resolveAll(parent.readDenials()));
		readDenials.addAll(childReadDenials);
		// A parent re-allowance would re-open the child's own denials inside it or around it
		List<Path> readReallowances = new ArrayList<>();
		for (Path reallowance : RealPaths.resolveAll(parent.readReallowances()))
		{
			if (ParentPolicy.readsWhole(reallowance, childReadDenials, List.of()))
				readReallowances.add(reallowance);
		}

		Set<String> hosts = new LinkedHashSet<>();
		for (String host : needs.hosts())
		{
			if (parent.hosts().allows(host))
				hosts.add(host);
		}
		return new ChildPolicy(List.copyOf(writeRoots), writeDenials, List.copyOf(readDenials), readReallowances,
			hosts, parent.allowLocalBinding() && hasLoopback(hosts));
	}

	/**
	 * Returns this policy as the policy of a parent, so that a session that the child requests in turn is bounded by
	 * it: {@link #intersect(ParentPolicy, ChildNeeds) intersecting} the requested session's needs with it gives the
	 * requested session nothing that this policy does not give.
	 *
	 * @return a sandboxed parent policy with exactly this policy's accesses, which protects no source of its own
	 */
	public ParentPolicy asParentPolicy()
	{
		HostAccess access;
		if (hosts.isEmpty())
			access = HostAccess.NONE;
		else
			access = HostAccess.listed(hosts);
		return new ParentPolicy(true, writeRoots, writeDenials, readDenials, readReallowances, access,
			allowLocalBinding, List.of());
	}

	/**
	 * Indicates whether one of some hosts is a loopback address, so that a command reaching it reaches a server on the
	 * same machine. No host is looked up: only {@code localhost} and literal loopback addresses count.
	 *
	 * @param hosts host names or literal IP addresses, the latter for IPv6 optionally in brackets
	 * @return {@code true} if one of {@code hosts} is a loopback address
	 */
	private static boolean hasLoopback(Set<String> hosts)
	{
		for (String host : hosts)
		{
			if (host.equals("localhost"))
				return true;
			String literal = host;
			if (literal.startsWith("[") && literal.endsWith("]"))
				literal = literal.substring(1, literal.length() - 1);
			try
			{
				if (InetAddress.ofLiteral(literal).isLoopbackAddress())
					return true;
			}
			catch (IllegalArgumentException _)
			{
				// A host name rather than a literal address
			}
		}
		return false;
	}
}
