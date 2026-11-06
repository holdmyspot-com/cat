package com.holdmyspot.cat.common.runner.policy.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

/**
 * Tests that {@link ChildPolicy#intersect(ParentPolicy, ChildNeeds)} gives a child session its needs when its
 * parent's policy allows them, and refuses the child, naming every write and host that the parent's policy lacks,
 * when it does not. Each test's parent policy and child needs name folders in a temporary directory that stands for
 * the user's file system.
 */
public final class ChildPolicyTest
{
	/**
	 * Creates the test.
	 */
	public ChildPolicyTest()
	{
	}

	/**
	 * Returns a sandboxed parent policy with no network.
	 *
	 * @param writeRoots   the folders that the parent may write
	 * @param writeDenials the paths inside them that the parent may not write
	 * @param readDenials  the paths that the parent may not read
	 * @param reallowances the paths inside read denials that the parent may read
	 * @return the policy
	 */
	private static ParentPolicy sandboxed(List<Path> writeRoots, List<Path> writeDenials, List<Path> readDenials,
		List<Path> reallowances)
	{
		return new ParentPolicy(true, writeRoots, writeDenials, readDenials, reallowances, HostAccess.NONE, false,
			List.of());
	}

	/**
	 * Returns a sandboxed parent policy that writes nothing and denies no reads.
	 *
	 * @param hosts             the hosts that the parent may reach
	 * @param allowLocalBinding {@code true} if the parent may listen on local addresses
	 * @return the policy
	 */
	private static ParentPolicy network(HostAccess hosts, boolean allowLocalBinding)
	{
		return new ParentPolicy(true, List.of(), List.of(), List.of(), List.of(), hosts, allowLocalBinding, List.of());
	}

	/**
	 * Verifies that a child that needs a write outside every folder that its parent may write is refused, and the
	 * refusal names that write.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesWriteOutsideParentWrites() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-policy-")).toRealPath();
			Path project = Files.createDirectory(root.resolve("project"));
			Path cache = Files.createDirectory(root.resolve("cache"));
			Path run = Files.createDirectory(root.resolve("run"));
			ParentPolicy parent = sandboxed(List.of(project), List.of(), List.of(), List.of());

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> ChildPolicy.intersect(parent, new ChildNeeds(run, List.of(cache), List.of(), Set.of())));

			requireThat(refusal.getMessage(), "message").contains(cache.toString());
		}
	}

	/**
	 * Verifies that a child that needs several writes and hosts that its parent's policy lacks is refused once, and
	 * that the refusal names every one of them and none that the parent allows.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void namesEveryMissingWriteAndHost() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-policy-")).toRealPath();
			Path project = Files.createDirectory(root.resolve("project"));
			Path allowed = Files.createDirectory(project.resolve("allowed"));
			Path firstCache = Files.createDirectory(root.resolve("first-cache"));
			Path secondCache = Files.createDirectory(root.resolve("second-cache"));
			Path run = Files.createDirectory(root.resolve("run"));
			ParentPolicy parent = new ParentPolicy(true, List.of(project), List.of(), List.of(), List.of(),
				HostAccess.listed(Set.of("allowed.example")), false, List.of());

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> ChildPolicy.intersect(parent, new ChildNeeds(run, List.of(firstCache, allowed, secondCache),
					List.of(), Set.of("allowed.example", "first.example", "second.example"))));

			requireThat(refusal.getMessage(), "message").contains(firstCache.toString()).
				contains(secondCache.toString()).contains("first.example").contains("second.example").
				doesNotContain(allowed.toString()).doesNotContain("allowed.example");
		}
	}

	/**
	 * Verifies that a write that a child needs inside a folder its parent may write is given to the child.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void keepsWriteInsideParentWrites() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-policy-")).toRealPath();
			Path project = Files.createDirectory(root.resolve("project"));
			Path build = Files.createDirectory(project.resolve("build"));
			Path run = Files.createDirectory(root.resolve("run"));
			ParentPolicy parent = sandboxed(List.of(project), List.of(), List.of(), List.of());

			ChildPolicy child = ChildPolicy.intersect(parent, new ChildNeeds(run, List.of(build), List.of(),
				Set.of()));

			requireThat(child.writeRoots(), "writeRoots").isEqualTo(List.of(run, build));
		}
	}

	/**
	 * Verifies that a child that needs a write inside a path that its parent may not write is refused, and the
	 * refusal names the write and the path that its parent may not write.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesWriteInsideParentWriteDenial() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-policy-")).toRealPath();
			Path project = Files.createDirectory(root.resolve("project"));
			Path git = Files.createDirectory(project.resolve(".git"));
			Path hooks = git.resolve("hooks");
			Path run = Files.createDirectory(root.resolve("run"));
			ParentPolicy parent = sandboxed(List.of(project), List.of(git), List.of(), List.of());

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> ChildPolicy.intersect(parent, new ChildNeeds(run, List.of(hooks), List.of(), Set.of())));

			requireThat(refusal.getMessage(), "message").contains(hooks.toString()).contains(git.toString());
		}
	}

	/**
	 * Verifies that a write inside the child's own run folder is given to the child even though its parent may not
	 * write the run folder.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void keepsWriteInsideRunFolder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-policy-")).toRealPath();
			Path run = Files.createDirectory(root.resolve("run"));
			Path requests = Files.createDirectory(run.resolve("requests"));
			ParentPolicy parent = sandboxed(List.of(), List.of(), List.of(), List.of());

			ChildPolicy child = ChildPolicy.intersect(parent, new ChildNeeds(run, List.of(requests), List.of(),
				Set.of()));

			requireThat(child.writeRoots(), "writeRoots").isEqualTo(List.of(run, requests));
		}
	}

	/**
	 * Verifies that a child's run folder is writable even though it lies outside every folder that its parent may
	 * write.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void keepsRunFolderOutsideParentWrites() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-policy-")).toRealPath();
			Path sessions = root.resolve("sessions");
			Path run = Files.createDirectories(sessions.resolve("requests/id/run"));
			ParentPolicy parent = sandboxed(List.of(root), List.of(sessions), List.of(), List.of());

			ChildPolicy child = ChildPolicy.intersect(parent, new ChildNeeds(run, List.of(), List.of(), Set.of()));

			requireThat(child.writeRoots(), "writeRoots").isEqualTo(List.of(run));
			requireThat(child.writeDenials(), "writeDenials").isEmpty();
		}
	}

	/**
	 * Verifies that a child may not read any path that its parent may not read, in addition to the paths that the
	 * child itself must not read.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void keepsEveryParentReadDenial() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-policy-")).toRealPath();
			Path secrets = Files.createDirectory(root.resolve("secrets"));
			Path keys = root.resolve("keys");
			Path login = root.resolve("login.json");
			Path run = Files.createDirectory(root.resolve("run"));
			ParentPolicy parent = sandboxed(List.of(), List.of(), List.of(secrets, keys), List.of());

			ChildPolicy child = ChildPolicy.intersect(parent, new ChildNeeds(run, List.of(), List.of(login),
				Set.of()));

			requireThat(Set.copyOf(child.readDenials()), "readDenials").isEqualTo(Set.of(secrets, keys, login));
		}
	}

	/**
	 * Verifies that a path inside a parent's read denial that the parent may read again stays readable for the child
	 * unless the child must not read a path inside it or around it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void dropsReallowanceThatReopensChildDenial() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-policy-")).toRealPath();
			Path config = Files.createDirectory(home.resolve("config"));
			Path plugins = Files.createDirectory(home.resolve("plugins"));
			Path docs = Files.createDirectory(home.resolve("docs"));
			Path pluginData = Files.createDirectory(plugins.resolve("data"));
			Path login = config.resolve("login.json");
			Path run = Files.createDirectory(home.resolve("run"));
			ParentPolicy parent = sandboxed(List.of(), List.of(), List.of(home), List.of(config, pluginData, docs));

			ChildPolicy child = ChildPolicy.intersect(parent, new ChildNeeds(run, List.of(), List.of(login, plugins),
				Set.of()));

			requireThat(child.readReallowances(), "readReallowances").isEqualTo(List.of(docs));
		}
	}

	/**
	 * Returns parent host access with the hosts that a child that needs {@code api.example.com},
	 * {@code mirror.other.org} and {@code model.test} lacks under it.
	 *
	 * @return each parent access and the child's hosts that it does not allow
	 */
	@DataProvider
	public Object[][] hostAccess()
	{
		return new Object[][]
			{
				{HostAccess.ALL, Set.of()},
				{HostAccess.listed(Set.of("API.example.com", "*.other.org", "unused.test", "*.model.test")),
					Set.of("model.test")},
				{HostAccess.listed(Set.of("*.model.test", "example.com")),
					Set.of("api.example.com", "mirror.other.org", "model.test")},
				{HostAccess.NONE, Set.of("api.example.com", "mirror.other.org", "model.test")}
			};
	}

	/**
	 * Verifies that a child reaches every host it needs when its parent may reach them, and is otherwise refused with
	 * a refusal that names each host that its parent may not reach and no other.
	 *
	 * @param parentHosts the hosts that the parent may reach
	 * @param missing     the hosts that the child needs and the parent may not reach
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "hostAccess")
	public void requiresParentToReachChildHosts(HostAccess parentHosts, Set<String> missing) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path run = cleanup.add(Files.createTempDirectory("cat-policy-"));
			Set<String> needed = Set.of("api.example.com", "mirror.other.org", "model.test");
			ChildNeeds needs = new ChildNeeds(run, List.of(), List.of(), needed);

			if (missing.isEmpty())
			{
				ChildPolicy child = ChildPolicy.intersect(network(parentHosts, false), needs);
				requireThat(child.hosts(), "hosts").isEqualTo(needed);
				return;
			}
			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> ChildPolicy.intersect(network(parentHosts, false), needs));

			String message = refusal.getMessage();
			for (String host : needed)
			{
				if (missing.contains(host))
					requireThat(message, "message").contains(host);
				else
					requireThat(message, "message").doesNotContain(host);
			}
		}
	}

	/**
	 * Returns whether a parent may listen on local addresses and the hosts that a child needs, with whether the child
	 * may then listen on local addresses.
	 *
	 * @return each case
	 * @throws UnknownHostException if an address cannot be formed
	 */
	@DataProvider
	public Object[][] localBinding() throws UnknownHostException
	{
		String privateAddress = address(10, 1, 2, 3);
		return new Object[][]
			{
				{true, Set.of("localhost"), true},
				{true, Set.of(InetAddress.getLoopbackAddress().getHostAddress()), true},
				{true, Set.of("[::1]"), true},
				{true, Set.of(privateAddress), true},
				{true, Set.of(address(192, 168, 1, 2)), true},
				{true, Set.of(address(169, 254, 1, 2)), true},
				{true, Set.of(address(0, 0, 0, 0)), true},
				{true, Set.of("[fd12:3456::1]"), true},
				{true, Set.of("[fe80::1]"), true},
				{true, Set.of("api.example.com", "[2001:db8::1]"), false},
				{false, Set.of("localhost"), false},
				{false, Set.of(privateAddress), false}
			};
	}

	/**
	 * Returns the literal form of an IPv4 address.
	 *
	 * @param octets the address's four octets
	 * @return the address in dotted-decimal form
	 * @throws UnknownHostException if the address cannot be formed
	 */
	private static String address(int... octets) throws UnknownHostException
	{
		byte[] bytes = new byte[octets.length];
		for (int i = 0; i < octets.length; ++i)
			bytes[i] = (byte) octets[i];
		return InetAddress.getByAddress(bytes).getHostAddress();
	}

	/**
	 * Verifies that a child may listen on local addresses only when its parent may and the child needs a host that
	 * Codex's network proxy blocks without that permission: {@code localhost}, or a literal loopback, private,
	 * link-local or unspecified address.
	 *
	 * @param parentAllows {@code true} if the parent may listen on local addresses
	 * @param hosts        the hosts that the child needs
	 * @param expected     {@code true} if the child may listen on local addresses
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "localBinding")
	public void bindsLocallyOnlyForParentAndLocalHost(boolean parentAllows, Set<String> hosts, boolean expected)
		throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path run = cleanup.add(Files.createTempDirectory("cat-policy-"));

			ChildPolicy child = ChildPolicy.intersect(network(HostAccess.ALL, parentAllows), new ChildNeeds(run,
				List.of(), List.of(), hosts));

			requireThat(child.allowLocalBinding(), "allowLocalBinding").isEqualTo(expected);
		}
	}

	/**
	 * Verifies that the child of a parent whose commands are unrestricted gets exactly its needs.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void givesUnsandboxedParentsChildItsNeeds() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-policy-")).toRealPath();
			Path run = Files.createDirectory(root.resolve("run"));
			Path cache = root.resolve("cache");
			Path login = root.resolve("login.json");

			ChildPolicy child = ChildPolicy.intersect(ParentPolicy.UNSANDBOXED, new ChildNeeds(run, List.of(cache),
				List.of(login), Set.of("api.example.com", "localhost")));

			requireThat(child, "child").isEqualTo(new ChildPolicy(List.of(run, cache), List.of(), List.of(login),
				List.of(), Set.of("api.example.com", "localhost"), true));
		}
	}

	/**
	 * Verifies that the child of a parent whose commands are unrestricted may listen on local addresses when it needs a
	 * private address, which Codex's network proxy blocks without that permission.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void bindsLocallyForUnsandboxedPrivateHost() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path run = cleanup.add(Files.createTempDirectory("cat-policy-"));

			ChildPolicy child = ChildPolicy.intersect(ParentPolicy.UNSANDBOXED, new ChildNeeds(run, List.of(),
				List.of(), Set.of(address(192, 168, 1, 2))));

			requireThat(child.allowLocalBinding(), "allowLocalBinding").isTrue();
		}
	}

	/**
	 * Verifies that a parent's write root named through a symbolic link, and child writes named with {@code ..}, are
	 * compared by the folders that they reach.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void comparesPathsByRealPath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-policy-")).toRealPath();
			Path project = Files.createDirectory(root.resolve("project"));
			Path link = Files.createSymbolicLink(root.resolve("link"), project);
			Path outside = Files.createDirectory(root.resolve("outside"));
			Path run = Files.createDirectory(root.resolve("run"));
			Path cache = project.resolve("cache");
			Path escapingCache = project.resolve("../outside/cache");
			Path returningCache = outside.resolve("../project/build");
			ParentPolicy parent = sandboxed(List.of(link), List.of(), List.of(), List.of());

			ChildPolicy child = ChildPolicy.intersect(parent, new ChildNeeds(run, List.of(cache, returningCache),
				List.of(), Set.of()));
			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> ChildPolicy.intersect(parent, new ChildNeeds(run, List.of(escapingCache), List.of(),
					Set.of())));

			requireThat(child.writeRoots(), "writeRoots").isEqualTo(List.of(run, cache, project.resolve("build")));
			requireThat(refusal.getMessage(), "message").contains(outside.resolve("cache").toString());
		}
	}

	/**
	 * Verifies that a child write named through a symbolic link whose target does not exist yet compares by that
	 * target, so that a link inside a parent's write root cannot give the child a write outside it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void comparesDanglingLinkByTarget() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-policy-")).toRealPath();
			Path project = Files.createDirectory(root.resolve("project"));
			Path link = Files.createSymbolicLink(project.resolve("link"), root.resolve("outside/missing"));
			Path run = Files.createDirectory(root.resolve("run"));
			ParentPolicy parent = sandboxed(List.of(project), List.of(), List.of(), List.of());

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> ChildPolicy.intersect(parent, new ChildNeeds(run, List.of(link.resolve("cache")), List.of(),
					Set.of())));

			requireThat(refusal.getMessage(), "message").contains(root.resolve("outside/missing/cache").toString());
		}
	}

	/**
	 * Verifies that a session that a bounded session requests gets the writes inside the bound's write roots, the
	 * bound's read denials and the hosts of the bound that it needs.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void boundGivesRequestedSessionWhatItAllows() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-policy-")).toRealPath();
			Path run = Files.createDirectory(root.resolve("run"));
			Path work = Files.createDirectory(run.resolve("work"));
			Path requested = Files.createDirectory(run.resolve("requested"));
			Path secret = Files.createDirectory(root.resolve("secret"));
			ChildPolicy bound = new ChildPolicy(List.of(run), List.of(), List.of(secret), List.of(),
				Set.of("model.example"), false);

			ChildPolicy child = ChildPolicy.intersect(bound.asParentPolicy(), new ChildNeeds(requested,
				List.of(work), List.of(), Set.of("model.example")));

			requireThat(child.writeRoots(), "writeRoots").isEqualTo(List.of(requested, work));
			requireThat(child.readDenials(), "readDenials").isEqualTo(List.of(secret));
			requireThat(child.hosts(), "hosts").isEqualTo(Set.of("model.example"));
		}
	}

	/**
	 * Verifies that a session that a bounded session requests is refused when it needs a write or a host beyond the
	 * bound, and the refusal names them.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void boundRefusesRequestedSessionNeedingMore() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-policy-")).toRealPath();
			Path run = Files.createDirectory(root.resolve("run"));
			Path requested = Files.createDirectory(run.resolve("requested"));
			Path cache = Files.createDirectory(root.resolve("cache"));
			ChildPolicy bound = new ChildPolicy(List.of(run), List.of(), List.of(), List.of(), Set.of("model.example"),
				false);

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> ChildPolicy.intersect(bound.asParentPolicy(), new ChildNeeds(requested, List.of(cache),
					List.of(), Set.of("model.example", "other.example"))));

			requireThat(refusal.getMessage(), "message").contains(cache.toString()).contains("other.example").
				doesNotContain("model.example");
		}
	}

	/**
	 * Verifies that a session that a bounded session without hosts requests is refused when it needs a host.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void boundWithoutHostsRefusesHost() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-policy-")).toRealPath();
			Path run = Files.createDirectory(root.resolve("run"));
			ChildPolicy bound = new ChildPolicy(List.of(run), List.of(), List.of(), List.of(), Set.of(), false);

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> ChildPolicy.intersect(bound.asParentPolicy(), new ChildNeeds(run, List.of(), List.of(),
					Set.of("model.example"))));

			requireThat(refusal.getMessage(), "message").contains("model.example");
		}
	}
}
