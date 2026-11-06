package com.holdmyspot.cat.common.runner.launcher.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.launcher.ChildBound;
import com.holdmyspot.cat.common.runner.launcher.ChildNeedsSource;
import com.holdmyspot.cat.common.runner.launcher.ParentPolicySource;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

/**
 * Tests that a child session's bound gives the child what it needs and no more than its parent's policy allows, and
 * refuses the launch, saying how to allow it, when that policy cannot be read safely or does not allow what the child
 * needs. Each test's policies name folders in a temporary directory that stands for the user's file system.
 */
public final class ChildBoundTest
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
	public ChildBoundTest()
	{
	}

	/**
	 * Returns a source of the parent's policy whose readings answer in turn with a policy or throw a failure; once only
	 * the last answer remains, every later reading repeats it. The source stands for the harness settings and records
	 * that a real source reads, and each answer for the policy that they hold at that reading.
	 *
	 * @param answers the {@link ParentPolicy} that each reading returns, or the {@link IOException},
	 *                {@link PolicyRefusal} or {@link SessionRefusedException} that it throws
	 * @return the source
	 */
	private static ParentPolicySource parent(Object... answers)
	{
		Deque<Object> remaining = new ArrayDeque<>(List.of(answers));
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
				Object answer;
				synchronized (remaining)
				{
					if (remaining.size() > 1)
						answer = remaining.removeFirst();
					else
						answer = remaining.getFirst();
				}
				switch (answer)
				{
					case ParentPolicy policy ->
					{
						return policy;
					}
					case IOException e -> throw e;
					case PolicyRefusal e -> throw e;
					default -> throw new AssertionError(answer);
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
			false, List.of());
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
			ChildBound bound = new ChildBound(parent(failure), (_, _, _, _) ->
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
			ChildBound bound = new ChildBound(parent(harness), needing(List.of()));

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
			ChildBound bound = new ChildBound(parent(ParentPolicy.UNSANDBOXED), (_, _, _, _) ->
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
			ChildBound bound = new ChildBound(parent(sandboxed(List.of(run), List.of(), Set.of())),
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
			ChildBound bound = new ChildBound(parent(sandboxed(List.of(work), List.of(denial), Set.of())),
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
			ChildBound bound = new ChildBound(parent(sandboxed(List.of(run, cache), List.of(),
				Set.of("registry.example", "unused.example"))), needing(List.of()));

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
			ChildBound bound = new ChildBound(parent(sandboxed(List.of(root), List.of(),
				Set.of("registry.example"))), needing(List.of()));

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
			ChildBound bound = new ChildBound(parent(sandboxed(List.of(run), List.of(), Set.of())),
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

	/**
	 * Verifies that a snapshot is refused with the reason and its fix when the parent's policy cannot be read for it,
	 * as a launch is.
	 *
	 * @param failure  what reading the policy throws
	 * @param expected the message that refuses the snapshot
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "unusablePolicies")
	public void refusesSnapshotOfUnusablePolicy(Exception failure, String expected) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path state = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			ChildBound bound = new ChildBound(parent(failure), needing(List.of()));

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class, () -> bound.snapshot(state));

			requireThat(refusal.getMessage(), "message").isEqualTo(expected);
		}
	}

	/**
	 * Verifies that a bound taken after a snapshot gives the child only what both the snapshot's reading and the
	 * launch's reading of the parent's policy allow: a write or a host that only one of them allows is refused, so a
	 * change of the parent's settings after the snapshot can narrow a child but never widen it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void boundsChildBySnapshotAndLaunchReadings() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			Path run = Files.createDirectory(root.resolve("run"));
			Path state = Files.createDirectory(root.resolve("state"));
			Path snapshotCache = Files.createDirectory(root.resolve("snapshot cache"));
			Path launchCache = Files.createDirectory(root.resolve("launch cache"));
			ChildBound bound = new ChildBound(parent(
				sandboxed(List.of(run, snapshotCache), List.of(), Set.of("snapshot.example")),
				sandboxed(List.of(run, launchCache), List.of(), Set.of("launch.example"))), needing(List.of())).
				snapshot(state);
			ExtraPermissions extra = new ExtraPermissions(List.of(snapshotCache, launchCache),
				Set.of("snapshot.example", "launch.example"));

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> bound.bound("codex", run, List.of(), extra));

			requireThat(refusal.getMessage(), "message").isEqualTo("CAT cannot start the session: this " +
				HARNESS_NAME + " session's sandbox does not let its commands write " + snapshotCache + ", " +
				launchCache + " and reach launch.example, snapshot.example, which the requested session needs. To " +
				"allow this, add [" + snapshotCache + ", " + launchCache + "] and [launch.example, snapshot.example] " +
				"to the settings of " + HARNESS_NAME + ", then start a new " + HARNESS_NAME + " session.");
		}
	}

	/**
	 * Returns the readings of the parent's policy that a snapshot's bound checks for a trusted folder.
	 *
	 * @return the reading that lets the parent's commands write the folder: the snapshot's, or the launch's after a
	 *         snapshot that does not
	 */
	@DataProvider
	public Object[][] trustedFolderReadings()
	{
		return new Object[][]{{"snapshot"}, {"launch"}};
	}

	/**
	 * Verifies that a reading of the parent's policy that lets the parent's commands write the trusted folder is
	 * refused, naming the folder, whether the snapshot or a later launch reads it. A launch's reading is refused even
	 * when the snapshot's reading kept the folder from the commands, because the commands could now change what CAT
	 * reads there.
	 *
	 * @param reading the reading that lets the parent's commands write the folder
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "trustedFolderReadings")
	public void refusesReadingThatLetsParentWriteTrusted(String reading) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			Path run = Files.createDirectory(root.resolve("run"));
			Path state = Files.createDirectory(root.resolve("state"));
			ParentPolicy writesState = sandboxed(List.of(root), List.of(), Set.of());
			ParentPolicySource parent;
			if (reading.equals("snapshot"))
				parent = parent(writesState);
			else
				parent = parent(sandboxed(List.of(run), List.of(), Set.of()), writesState);
			ChildBound bound = new ChildBound(parent, needing(List.of()));

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> bound.snapshot(state).bound("codex", run, List.of(), ExtraPermissions.NONE));

			requireThat(refusal.getMessage(), "message").isEqualTo("CAT cannot start the session: it cannot read " +
				"this " + HARNESS_NAME + " session's sandbox policy safely: the session's sandbox lets its commands " +
				"write " + state + ", so they could change what CAT reads there. Remove " + state + " from the paths " +
				"that the session's sandbox lets its commands write, then start a new session.");
		}
	}
}
