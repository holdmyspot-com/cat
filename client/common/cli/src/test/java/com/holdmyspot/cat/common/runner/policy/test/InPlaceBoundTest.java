package com.holdmyspot.cat.common.runner.policy.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsSource;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.InPlaceBound;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.ParentPolicySource;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

/**
 * Tests that a runner that starts a child in place bounds the child by the policy of the session whose command runs
 * the runner, and refuses the launch, saying how to allow it, when that policy cannot be read or does not allow what
 * the child needs. Each test's policies name folders in a temporary directory that stands for the user's file system.
 */
public final class InPlaceBoundTest
{
	/**
	 * The name of the parent's harness.
	 */
	private static final String HARNESS_NAME = "Test Harness";
	/**
	 * The host of the child's model.
	 */
	private static final String MODEL_HOST = "model.example";

	/**
	 * Creates the test.
	 */
	public InPlaceBoundTest()
	{
	}

	/**
	 * Returns a parent policy source that answers with a policy, or throws what it is given.
	 *
	 * @param policy  the policy, or null if the source throws
	 * @param failure what the source throws, or null if it answers
	 * @return the source
	 */
	private static ParentPolicySource parent(ParentPolicy policy, Exception failure)
	{
		return new ParentPolicySource()
		{
			@Override
			public String harnessName()
			{
				return HARNESS_NAME;
			}

			@Override
			public String permissionEntries(List<Path> writes, Set<String> hosts)
			{
				return "add " + writes + " and " + new TreeSet<>(hosts) + " to the settings of " + HARNESS_NAME;
			}

			@Override
			public ParentPolicy read() throws IOException, PolicyRefusal
			{
				switch (failure)
				{
					case null ->
					{
						return policy;
					}
					case IOException e -> throw e;
					case PolicyRefusal e -> throw e;
					default -> throw new AssertionError(failure);
				}
			}
		};
	}

	/**
	 * Returns a source of a child's needs that needs its run folder, the launch's denied reads, some writes and the
	 * model's host.
	 *
	 * @param writes the writes that the child needs outside its run folder
	 * @return the source
	 */
	private static ChildNeedsSource needing(List<Path> writes)
	{
		return (_, runFolder, deniedReads, _) -> new ChildNeeds(runFolder, writes, deniedReads, Set.of(MODEL_HOST));
	}

	/**
	 * Returns a sandboxed parent policy that may reach the model's host and some other hosts.
	 *
	 * @param writeRoots   the folders that the parent may write
	 * @param writeDenials the paths inside them that the parent may not write
	 * @param otherHosts   the hosts beside the model's that the parent may reach
	 * @return the policy
	 */
	private static ParentPolicy sandboxed(List<Path> writeRoots, List<Path> writeDenials, Set<String> otherHosts)
	{
		Set<String> hosts = new TreeSet<>(otherHosts);
		hosts.add(MODEL_HOST);
		return new ParentPolicy(true, writeRoots, writeDenials, List.of(), List.of(), HostAccess.listed(hosts),
			false);
	}

	/**
	 * Returns the ways in which reading the parent's policy fails.
	 *
	 * @return the failure and the message that refuses the launch
	 */
	@DataProvider
	public Object[][] unusablePolicies()
	{
		PolicyRefusal refusal = new PolicyRefusal("the settings file /p/settings.json cannot be parsed",
			"Fix the file, then start a new session");
		return new Object[][]
			{
				{refusal, refusal.messageFor(HARNESS_NAME)},
				{new IOException("/p/settings.json: permission denied"), "CAT cannot start the session: it cannot " +
					"read this " + HARNESS_NAME + " session's sandbox policy: /p/settings.json: permission denied"}
			};
	}

	/**
	 * Verifies that a launch is refused with the reason and its fix, and asks nothing of the child's harness, when the
	 * parent's policy cannot be read.
	 *
	 * @param failure  what reading the policy throws
	 * @param expected the message that refuses the launch
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "unusablePolicies")
	public void refusesUnusablePolicy(Exception failure, String expected) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path run = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			InPlaceBound bound = new InPlaceBound(parent(null, failure), (_, _, _, _) ->
			{
				throw new AssertionError("The child's needs were asked for");
			});

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> bound.bound("codex", run, List.of(), ExtraPermissions.NONE));

			requireThat(refusal.getMessage(), "message").isEqualTo(expected);
		}
	}

	/**
	 * Verifies that a refusal of the parent's own, such as a command that the other harness runs, reaches the user
	 * unchanged.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void keepsParentsOwnRefusal() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path run = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			SessionRefusedException harness = new SessionRefusedException("Codex runs this command");
			InPlaceBound bound = new InPlaceBound(parent(null, harness), needing(List.of()));

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> bound.bound("codex", run, List.of(), ExtraPermissions.NONE));

			requireThat(refusal, "refusal").isReferenceEqualTo(harness, "harness");
		}
	}

	/**
	 * Verifies that a launch is refused when the child's harness refuses to run within the parent's policy, such as a
	 * child whose isolated home would copy a login that the parent may not read.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesChildThatCannotRunWithinParent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path run = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			PolicyRefusal login = new PolicyRefusal("its sandbox denies its commands reading /h/auth.json",
				"Let the session's sandbox read /h/auth.json, then start a new session");
			InPlaceBound bound = new InPlaceBound(parent(ParentPolicy.UNSANDBOXED, null), (_, _, _, _) ->
			{
				throw login;
			});

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> bound.bound("codex", run, List.of(), ExtraPermissions.NONE));

			requireThat(refusal.getMessage(), "message").isEqualTo(login.messageFor(HARNESS_NAME));
		}
	}

	/**
	 * Verifies that a launch is refused when the child needs a write outside the parent's write roots and hosts that
	 * the parent may not reach; the refusal names each, quotes the settings of the parent's harness that allow them
	 * and says to start a new session.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesChildNeedingMoreThanParentAllows() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			Path run = Files.createDirectory(root.resolve("run"));
			Path cache = root.resolve("cache");
			ChildNeedsSource needs = (_, runFolder, deniedReads, _) -> new ChildNeeds(runFolder, List.of(cache),
				deniedReads, Set.of(MODEL_HOST, "zeta.example", "other.example"));
			InPlaceBound bound = new InPlaceBound(parent(sandboxed(List.of(run), List.of(), Set.of()), null),
				needs);

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> bound.bound("codex", run, List.of(), ExtraPermissions.NONE));

			requireThat(refusal.getMessage(), "message").isEqualTo("CAT cannot start the session: this " +
				HARNESS_NAME + " session's sandbox does not let its commands write " + cache + " and reach " +
				"other.example, zeta.example, which the requested session needs. To allow this, add [" + cache +
				"] and [other.example, zeta.example] to the settings of " + HARNESS_NAME + ", then start a new " +
				HARNESS_NAME + " session.");
		}
	}

	/**
	 * Verifies that a launch is refused when the child needs a write inside a path that the parent's sandbox denies
	 * writing; the refusal names the denial to remove, because adding the write to the settings would not allow it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesChildNeedingWriteThatParentDenies() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path work = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			Path run = Files.createDirectory(work.resolve("run"));
			Path denial = Files.createDirectory(work.resolve("protected"));
			Path cache = denial.resolve("cache");
			InPlaceBound bound = new InPlaceBound(parent(sandboxed(List.of(work), List.of(denial), Set.of()), null),
				needing(List.of(cache)));

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> bound.bound("codex", run, List.of(), ExtraPermissions.NONE));

			requireThat(refusal.getMessage(), "message").isEqualTo("CAT cannot start the session: this " +
				HARNESS_NAME + " session's sandbox does not let its commands write " + cache + " (denied by " + denial +
				"), which the requested session needs. To allow this, remove the denial of " + denial + " from its " +
				"settings, then start a new " + HARNESS_NAME + " session.");
		}
	}

	/**
	 * Verifies that the extra permissions of a launch join the child's needs, so that a parent that allows them gives
	 * them to the child.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void givesChildAllowedExtraPermissions() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			Path run = Files.createDirectory(root.resolve("run"));
			Path cache = Files.createDirectory(root.resolve("registry cache"));
			InPlaceBound bound = new InPlaceBound(parent(sandboxed(List.of(run, cache), List.of(),
				Set.of("registry.example", "unused.example")), null), needing(List.of()));

			ChildPolicy child = bound.bound("codex", run, List.of(),
				new ExtraPermissions(List.of(cache), Set.of("registry.example")));

			requireThat(child.writeRoots(), "writeRoots").isEqualTo(List.of(run, cache));
			requireThat(child.hosts(), "hosts").isEqualTo(Set.of(MODEL_HOST, "registry.example"));
		}
	}

	/**
	 * Verifies that a launch that asks for no extra permissions gives the child the permissions that it needs and no
	 * more, even when the parent allows more.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void givesChildNoExtraPermissionsUnlessAsked() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			Path run = Files.createDirectory(root.resolve("run"));
			Path denied = root.resolve("denied");
			InPlaceBound bound = new InPlaceBound(parent(sandboxed(List.of(root), List.of(),
				Set.of("registry.example")), null), needing(List.of()));

			ChildPolicy child = bound.bound("codex", run, List.of(denied), ExtraPermissions.NONE);

			requireThat(child.writeRoots(), "writeRoots").isEqualTo(List.of(run));
			requireThat(child.hosts(), "hosts").isEqualTo(Set.of(MODEL_HOST));
			requireThat(child.readDenials(), "readDenials").contains(denied);
		}
	}

	/**
	 * Verifies that a launch is refused when it asks for a write and a host that the parent's sandbox does not allow;
	 * the refusal names both and the settings to add them to.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesDisallowedExtraPermissions() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			Path run = Files.createDirectory(root.resolve("run"));
			Path cache = root.resolve("registry cache");
			InPlaceBound bound = new InPlaceBound(parent(sandboxed(List.of(run), List.of(), Set.of()), null),
				needing(List.of()));

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> bound.bound("codex", run, List.of(),
					new ExtraPermissions(List.of(cache), Set.of("registry.example"))));

			requireThat(refusal.getMessage(), "message").isEqualTo("CAT cannot start the session: this " +
				HARNESS_NAME + " session's sandbox does not let its commands write " + cache + " and reach " +
				"registry.example, which the requested session needs. To allow this, add [" + cache + "] and " +
				"[registry.example] to the settings of " + HARNESS_NAME + ", then start a new " + HARNESS_NAME +
				" session.");
		}
	}
}
