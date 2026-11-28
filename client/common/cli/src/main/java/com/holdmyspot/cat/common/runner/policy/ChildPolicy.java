package com.holdmyspot.cat.common.runner.policy;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The sandbox policy that a child session's harness enforces on the child's commands: its needs, which its parent's
 * policy allows. Its paths are resolved as {@link ParentPolicy} describes, and the commands may write, read and
 * reach what {@link ParentPolicy} describes for the same components.
 * <p>
 * The child gets the same type of sandbox as its parent: none when the parent has none, in which case the other
 * components say what the child's harness is configured to allow but no sandbox enforces them, and otherwise its
 * harness's sandbox, in the implementation that {@code sandboxSelection} names where the harness has more than one on
 * the platform.
 *
 * @param sandboxed         {@code true} if the child's commands run in its harness's sandbox, {@code false} if they
 *                          run without one, as its parent's do
 * @param writeRoots        the folders that the commands may write; the first is the child's session folder
 * @param writeDenials      the paths inside the write roots that the commands may not write
 * @param readDenials       the paths that the commands may not read
 * @param readReallowances  the paths inside read denials that the commands may read nonetheless
 * @param hosts             the network hosts that the commands may reach, as host names or literal IP addresses
 * @param allowLocalBinding {@code true} if the commands may listen on local network addresses
 * @param sandboxSelection  the parent's settings that select which of its harness's sandbox implementations runs the
 *                          commands, by dotted setting name, which the child's harness applies as they are; empty
 *                          if the parent's harness has a single sandbox on the platform, or selects none, and always
 *                          empty when the child has no sandbox
 */
public record ChildPolicy(boolean sandboxed, List<Path> writeRoots, List<Path> writeDenials, List<Path> readDenials,
	List<Path> readReallowances, Set<String> hosts, boolean allowLocalBinding, Map<String, String> sandboxSelection)
{
	/**
	 * Creates a policy.
	 *
	 * @param sandboxed         {@code true} if the child's commands run in its harness's sandbox
	 * @param writeRoots        the folders that the commands may write; the first is the child's session folder
	 * @param writeDenials      the paths inside the write roots that the commands may not write
	 * @param readDenials       the paths that the commands may not read
	 * @param readReallowances  the paths inside read denials that the commands may read nonetheless
	 * @param hosts             the network hosts that the commands may reach, as host names or literal IP addresses
	 * @param allowLocalBinding {@code true} if the commands may listen on local network addresses
	 * @param sandboxSelection  the parent's settings that select its harness's sandbox implementation
	 * @throws NullPointerException     if any of the arguments are null, or a collection contains null
	 * @throws IllegalArgumentException if a path is not absolute, {@code writeRoots} is empty, or a policy without a
	 *                                  sandbox has a selection
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
		requireThat(sandboxSelection, "sandboxSelection").isNotNull();
		sandboxSelection = Map.copyOf(sandboxSelection);
		if (!sandboxed)
			requireThat(sandboxSelection, "sandboxSelection").isEmpty();
	}

	/**
	 * Creates a sandboxed policy that selects no implementation.
	 *
	 * @param writeRoots        the folders that the commands may write; the first is the child's session folder
	 * @param writeDenials      the paths inside the write roots that the commands may not write
	 * @param readDenials       the paths that the commands may not read
	 * @param readReallowances  the paths inside read denials that the commands may read nonetheless
	 * @param hosts             the network hosts that the commands may reach, as host names or literal IP addresses
	 * @param allowLocalBinding {@code true} if the commands may listen on local network addresses
	 * @throws NullPointerException     if any of the arguments are null, or a collection contains null
	 * @throws IllegalArgumentException if a path is not absolute, or {@code writeRoots} is empty
	 */
	public ChildPolicy(List<Path> writeRoots, List<Path> writeDenials, List<Path> readDenials,
		List<Path> readReallowances, Set<String> hosts, boolean allowLocalBinding)
	{
		this(true, writeRoots, writeDenials, readDenials, readReallowances, hosts, allowLocalBinding, Map.of());
	}

	/**
	 * Returns this policy for a child whose parent has no sandbox, so that the child's commands run without one too.
	 *
	 * @return a policy with the same components, no sandbox and no selection
	 */
	public ChildPolicy unsandboxed()
	{
		if (!sandboxed)
			return this;
		return new ChildPolicy(false, writeRoots, writeDenials, readDenials, readReallowances, hosts,
			allowLocalBinding, Map.of());
	}

	/**
	 * Returns this policy with the parent's settings that select its harness's sandbox implementation.
	 *
	 * @param selection the settings, by dotted setting name
	 * @return a policy that differs from this one only in its selection
	 * @throws NullPointerException  if {@code selection} is null
	 * @throws IllegalStateException if this policy has no sandbox and {@code selection} is not empty
	 */
	public ChildPolicy withSandboxSelection(Map<String, String> selection)
	{
		requireThat(selection, "selection").isNotNull();
		if (!sandboxed && !selection.isEmpty())
			throw new IllegalStateException("A policy without a sandbox selects no sandbox implementation");
		return new ChildPolicy(sandboxed, writeRoots, writeDenials, readDenials, readReallowances, hosts,
			allowLocalBinding, selection);
	}

	/**
	 * Returns the policy of a child: its needs, which its parent's policy must allow. The child's session folder is
	 * always allowed. Read denials only narrow the child's reads, so the parent's are added to the child's.
	 *
	 * @param parent the parent's policy
	 * @param needs  the child's needs
	 * @return the child's policy, whose paths are resolved
	 * @throws NullPointerException        if any of the arguments are null
	 * @throws MissingPermissionsException if the parent is sandboxed and does not allow a write outside the child's
	 *                                     session folder, or a host, that the child needs
	 * @throws IOException                 if a symbolic link in a path cannot be read, or a path follows more than 40
	 *                                     links
	 */
	public static ChildPolicy intersect(ParentPolicy parent, ChildNeeds needs) throws IOException
	{
		requireThat(parent, "parent").isNotNull();
		requireThat(needs, "needs").isNotNull();
		Path sessionFolder = RealPaths.resolve(needs.sessionFolder());
		List<Path> extraWrites = RealPaths.resolveAll(needs.extraWrites());
		List<Path> childReadDenials = RealPaths.resolveAll(needs.readDenials());
		Set<Path> writeRoots = new LinkedHashSet<>();
		writeRoots.add(sessionFolder);
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
			if (write.startsWith(sessionFolder))
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
	 * Returns this policy with each read denial that holds one of the paths replaced by the denial of everything else
	 * in it, so that the commands may read those paths while they stay denied the rest of what the denial held. The
	 * policy's write roots inside such a denial stay out of the replacement, as the commands may write them, and a
	 * file or folder that is created later beside the paths is not denied.
	 * <p>
	 * A session that another session requests needs this: its requester's policy denies the folder that holds all of
	 * the requester's requested sessions, and the session's own responses folder lies inside it.
	 *
	 * @param readable the paths that the commands must be able to read
	 * @return a policy that differs from this one only in its read denials
	 * @throws NullPointerException if {@code readable} is null
	 * @throws IOException          if a symbolic link in a path cannot be read, a path follows more than 40 links, or
	 *                              a folder of a denial cannot be listed
	 */
	public ChildPolicy leavingReadable(List<Path> readable) throws IOException
	{
		requireThat(readable, "readable").isNotNull();
		List<Path> resolved = RealPaths.resolveAll(readable);
		Set<Path> carved = new LinkedHashSet<>();
		for (Path denial : readDenials)
		{
			List<Path> inside = new ArrayList<>();
			for (Path path : resolved)
			{
				if (path.startsWith(denial) && !path.equals(denial))
					inside.add(path);
			}
			if (inside.isEmpty())
			{
				carved.add(denial);
				continue;
			}
			for (Path root : writeRoots)
			{
				if (root.startsWith(denial))
					inside.add(root);
			}
			carveAround(denial, inside, carved);
		}
		return new ChildPolicy(sandboxed, writeRoots, writeDenials, List.copyOf(carved), readReallowances, hosts,
			allowLocalBinding, sandboxSelection);
	}

	/**
	 * Adds each entry of a folder that is not one of the paths and does not hold one, descending into each entry that
	 * holds one.
	 *
	 * @param folder the folder that holds a path
	 * @param paths  the paths that stay readable
	 * @param denied the denials to which the entries are added
	 * @throws IOException if the folder cannot be listed
	 */
	private static void carveAround(Path folder, List<Path> paths, Set<Path> denied) throws IOException
	{
		List<Path> entries;
		try (Stream<Path> listing = Files.list(folder))
		{
			entries = listing.sorted().toList();
		}
		for (Path entry : entries)
		{
			if (paths.contains(entry))
				continue;
			boolean holdsPath = false;
			for (Path path : paths)
			{
				if (path.startsWith(entry))
				{
					holdsPath = true;
					break;
				}
			}
			if (holdsPath && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
				carveAround(entry, paths, denied);
			else
				denied.add(entry);
		}
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
	 * Returns this policy with some paths that its session's harness already keeps from the session's commands added to
	 * its write denials, so that the policy reports the paths that the commands cannot write. A path that lies outside
	 * the write roots is already unwritable and is left out.
	 *
	 * @param paths the absolute paths that the commands may not write
	 * @return a policy that differs from this one only in its write denials, or this policy if no path lies inside a
	 *         write root
	 * @throws NullPointerException     if {@code paths} is null or contains null
	 * @throws IllegalArgumentException if a path is not absolute
	 * @throws IOException              if a symbolic link in a path cannot be read, or a path follows more than 40
	 *                                  links
	 */
	public ChildPolicy denyingWrites(List<Path> paths) throws IOException
	{
		List<Path> resolved = RealPaths.resolveAll(ParentPolicy.absolutePaths(paths, "paths"));
		Set<Path> denials = new LinkedHashSet<>(writeDenials);
		for (Path path : resolved)
		{
			if (RealPaths.isInsideAny(path, writeRoots))
				denials.add(path);
		}
		if (denials.size() == writeDenials.size())
			return this;
		return new ChildPolicy(sandboxed, writeRoots, List.copyOf(denials), readDenials, readReallowances, hosts,
			allowLocalBinding, sandboxSelection);
	}

	/**
	 * Returns this policy as the policy of a parent, so that a session that the child requests in turn gets nothing
	 * that this policy does not give: {@link #intersect(ParentPolicy, ChildNeeds) intersecting} the requested
	 * session's needs with it refuses a need that this policy does not allow.
	 *
	 * @return a sandboxed parent policy with exactly this policy's accesses, which protects no source of its own, or
	 *         {@link ParentPolicy#UNSANDBOXED} if this policy has no sandbox
	 */
	public ParentPolicy asParentPolicy()
	{
		if (!sandboxed)
			return ParentPolicy.UNSANDBOXED;
		HostAccess access;
		if (hosts.isEmpty())
			access = HostAccess.NONE;
		else
			access = HostAccess.listed(hosts);
		return new ParentPolicy(true, writeRoots, writeDenials, readDenials, readReallowances, access,
			allowLocalBinding, List.of());
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
