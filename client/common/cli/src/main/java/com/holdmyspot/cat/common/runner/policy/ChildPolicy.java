package com.holdmyspot.cat.common.runner.policy;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The sandbox policy that a child session's harness enforces on the child's commands: its needs, which its parent's
 * policy allows. Its paths are resolved as {@link ParentPolicy} describes, and the commands may write, read and
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
	 * Returns the policy of a child: its needs, which its parent's policy must allow. The child's run folder is always
	 * allowed. Read denials only narrow the child's reads, so the parent's are added to the child's.
	 *
	 * @param parent the parent's policy
	 * @param needs  the child's needs
	 * @return the child's policy, whose paths are resolved
	 * @throws NullPointerException        if any of the arguments are null
	 * @throws MissingPermissionsException if the parent is sandboxed and does not allow a write outside the child's run
	 *                                     folder, or a host, that the child needs
	 * @throws IOException                 if a symbolic link in a path cannot be read, or a path follows more than 40
	 *                                     links
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
				anyNeedsLocalBinding(needs.hosts()));
		}

		List<Path> parentWriteRoots = RealPaths.resolveAll(parent.writeRoots());
		List<Path> parentWriteDenials = RealPaths.resolveAll(parent.writeDenials());
		List<Path> missingWrites = new ArrayList<>();
		Map<Path, Path> deniedWrites = new LinkedHashMap<>();
		for (Path write : extraWrites)
		{
			if (write.startsWith(runFolder))
				writeRoots.add(write);
			else if (!RealPaths.isInsideAny(write, parentWriteRoots))
				missingWrites.add(write);
			else
			{
				Path denial = deniedBy(write, parentWriteDenials);
				if (denial == null)
					writeRoots.add(write);
				else
					deniedWrites.put(write, denial);
			}
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

		Set<String> missingHosts = new TreeSet<>();
		for (String host : needs.hosts())
		{
			if (!parent.hosts().allows(host))
				missingHosts.add(host);
		}
		if (!missingWrites.isEmpty() || !deniedWrites.isEmpty() || !missingHosts.isEmpty())
			throw new MissingPermissionsException(missingWrites, deniedWrites, missingHosts);
		Set<String> hosts = needs.hosts();
		return new ChildPolicy(List.copyOf(writeRoots), writeDenials, List.copyOf(readDenials), readReallowances,
			hosts, parent.allowLocalBinding() && anyNeedsLocalBinding(hosts));
	}

	/**
	 * Returns the first of some resolved paths that contains a resolved path.
	 *
	 * @param path    a resolved path
	 * @param folders resolved paths
	 * @return the first of {@code folders} that is {@code path} or lies above it, or {@code null} if there is none
	 */
	private static Path deniedBy(Path path, List<Path> folders)
	{
		for (Path folder : folders)
		{
			if (path.startsWith(folder))
				return folder;
		}
		return null;
	}

	/**
	 * Returns this policy as the policy of a parent, so that a session that the child requests in turn gets nothing
	 * that this policy does not give: {@link #intersect(ParentPolicy, ChildNeeds) intersecting} the requested
	 * session's needs with it refuses a need that this policy does not allow.
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
			allowLocalBinding);
	}

	/**
	 * Indicates whether a command reaches a host only if it may bind local addresses: {@code localhost}, or a literal
	 * loopback, private (site-local or IPv6 unique local, {@code fc00::/7}), link-local or unspecified address. Codex's
	 * managed network proxy blocks every such host unless the commands may bind local addresses, even when its domain
	 * list allows the host. No host is looked up, so a host name that resolves to such an address does not count.
	 *
	 * @param host a host name or literal IP address, the latter for IPv6 optionally in brackets
	 * @return {@code true} if reaching {@code host} needs local binding
	 * @throws NullPointerException if {@code host} is null
	 */
	public static boolean needsLocalBinding(String host)
	{
		requireThat(host, "host").isNotNull();
		if (host.equalsIgnoreCase("localhost"))
			return true;
		String literal = host;
		if (literal.startsWith("[") && literal.endsWith("]"))
			literal = literal.substring(1, literal.length() - 1);
		InetAddress address;
		try
		{
			address = InetAddress.ofLiteral(literal);
		}
		catch (IllegalArgumentException _)
		{
			// A host name rather than a literal address
			return false;
		}
		if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() ||
			address.isAnyLocalAddress())
		{
			return true;
		}
		// InetAddress counts only the deprecated fec0::/10 as site-local, but fc00::/7 holds IPv6's private addresses
		return address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC;
	}

	/**
	 * Indicates whether one of some hosts {@link #needsLocalBinding(String) needs local binding}.
	 *
	 * @param hosts host names or literal IP addresses
	 * @return {@code true} if reaching one of {@code hosts} needs local binding
	 */
	private static boolean anyNeedsLocalBinding(Set<String> hosts)
	{
		for (String host : hosts)
		{
			if (needsLocalBinding(host))
				return true;
		}
		return false;
	}
}
