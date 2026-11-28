package com.holdmyspot.cat.common.runner.launcher.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.launcher.ChildBound;
import com.holdmyspot.cat.common.runner.launcher.ChildNeedsSource;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
	 * Returns a source of the parent's policy whose settings give a policy.
	 *
	 * @param policy the policy
	 * @return the source
	 */
	private static FakeParentPolicySource parent(ParentPolicy policy)
	{
		return new FakeParentPolicySource(HARNESS_NAME, policy);
	}

	/**
	 * Returns a source of the parent's policy whose readings fail.
	 *
	 * @param failure the {@link PolicyRefusal} or {@link IOException} that each reading throws
	 * @return the source
	 */
	private static FakeParentPolicySource failing(Exception failure)
	{
		FakeParentPolicySource parent = parent(ParentPolicy.UNSANDBOXED);
		switch (failure)
		{
			case PolicyRefusal refusal -> parent.refuse(refusal);
			case IOException e -> parent.failReading(e);
			default -> throw new AssertionError(failure);
		}
		return parent;
	}

	/**
	 * Returns a source of a child's needs that needs its session folder, the launch's denied reads, some writes and the
	 * model's host.
	 *
	 * @param writes the writes that the child needs outside its session folder
	 * @return the source
	 */
	private static ChildNeedsSource needing(List<Path> writes)
	{
		return (_, sessionFolder, deniedReads, _) -> new ChildNeeds(sessionFolder, writes, deniedReads, Set.of(MODEL_HOST));
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
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path sessionFolder = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			ChildBound bound = new ChildBound(failing(failure));

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> bound.bound("codex", sessionFolder, List.of(), ExtraPermissions.NONE, Optional.empty(), (_, _, _, _) ->
			{
				throw new AssertionError("The child's needs were asked for");
			}));

			requireThat(refusal.getMessage(), "message").isEqualTo(expected);
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
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path sessionFolder = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			PolicyRefusal login = new PolicyRefusal("its sandbox denies its commands reading /h/auth.json",
				"Let the session's sandbox read /h/auth.json, then start a new session");
			ChildBound bound = new ChildBound(parent(ParentPolicy.UNSANDBOXED));

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> bound.bound("codex", sessionFolder, List.of(), ExtraPermissions.NONE, Optional.empty(), (_, _, _, _) ->
			{
				throw login;
			}));

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
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			Path sessionFolder = Files.createDirectory(root.resolve("session"));
			Path cache = root.resolve("cache");
			ChildNeedsSource needs = (_, folder, deniedReads, _) -> new ChildNeeds(folder, List.of(cache),
				deniedReads, Set.of(MODEL_HOST, "zeta.example", "other.example"));
			ChildBound bound = new ChildBound(parent(sandboxed(List.of(sessionFolder), List.of(), Set.of())));

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> bound.bound("codex", sessionFolder, List.of(), ExtraPermissions.NONE, Optional.empty(),
					needs));

			requireThat(refusal.getMessage(), "message").isEqualTo("CAT cannot start the session: this " +
				HARNESS_NAME + " session's sandbox does not let its commands write " + cache + " and reach " +
				"other.example, zeta.example, which the requested session needs. To allow this, add \"" + cache +
				"\" to the writes and \"other.example\", \"zeta.example\" to the hosts in the settings of " +
				HARNESS_NAME + ", then start a new " + HARNESS_NAME + " session.");
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
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path work = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			Path sessionFolder = Files.createDirectory(work.resolve("session"));
			Path denial = Files.createDirectory(work.resolve("protected"));
			Path cache = denial.resolve("cache");
			ChildBound bound = new ChildBound(parent(sandboxed(List.of(work), List.of(denial), Set.of())));

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> bound.bound("codex", sessionFolder, List.of(), ExtraPermissions.NONE, Optional.empty(),
					needing(List.of(cache))));

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
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			Path sessionFolder = Files.createDirectory(root.resolve("session"));
			Path cache = Files.createDirectory(root.resolve("registry cache"));
			ChildBound bound = new ChildBound(parent(sandboxed(List.of(sessionFolder, cache), List.of(),
				Set.of("registry.example", "unused.example"))));

			ChildPolicy child = bound.bound("codex", sessionFolder, List.of(),
				new ExtraPermissions(List.of(cache), Set.of("registry.example")), Optional.empty(),
					needing(List.of()));

			requireThat(child.writeRoots(), "writeRoots").isEqualTo(List.of(sessionFolder, cache));
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
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			Path sessionFolder = Files.createDirectory(root.resolve("session"));
			Path denied = root.resolve("denied");
			ChildBound bound = new ChildBound(parent(sandboxed(List.of(root), List.of(),
				Set.of("registry.example"))));

			ChildPolicy child = bound.bound("codex", sessionFolder, List.of(denied), ExtraPermissions.NONE, Optional.empty(),
					needing(List.of()));

			requireThat(child.writeRoots(), "writeRoots").isEqualTo(List.of(sessionFolder));
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
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			Path sessionFolder = Files.createDirectory(root.resolve("session"));
			Path cache = root.resolve("registry cache");
			ChildBound bound = new ChildBound(parent(sandboxed(List.of(sessionFolder), List.of(), Set.of())));

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> bound.bound("codex", sessionFolder, List.of(),
					new ExtraPermissions(List.of(cache), Set.of("registry.example")), Optional.empty(),
					needing(List.of())));

			requireThat(refusal.getMessage(), "message").isEqualTo("CAT cannot start the session: this " +
				HARNESS_NAME + " session's sandbox does not let its commands write " + cache + " and reach " +
				"registry.example, which the requested session needs. To allow this, add \"" + cache + "\" to the " +
				"writes and \"registry.example\" to the hosts in the settings of " + HARNESS_NAME + ", then start a " +
				"new " + HARNESS_NAME + " session.");
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
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path state = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			ChildBound bound = new ChildBound(failing(failure));

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
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			Path sessionFolder = Files.createDirectory(root.resolve("session"));
			Path state = Files.createDirectory(root.resolve("state"));
			Path snapshotCache = Files.createDirectory(root.resolve("snapshot cache"));
			Path launchCache = Files.createDirectory(root.resolve("launch cache"));
			FakeParentPolicySource parent = parent(sandboxed(List.of(sessionFolder, snapshotCache), List.of(),
				Set.of("snapshot.example")));
			ChildBound bound = new ChildBound(parent).snapshot(state);
			parent.allow(sandboxed(List.of(sessionFolder, launchCache), List.of(), Set.of("launch.example")));
			ExtraPermissions extra = new ExtraPermissions(List.of(snapshotCache, launchCache),
				Set.of("snapshot.example", "launch.example"));

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> bound.bound("codex", sessionFolder, List.of(), extra, Optional.empty(),
					needing(List.of())));

			requireThat(refusal.getMessage(), "message").isEqualTo("CAT cannot start the session: this " +
				HARNESS_NAME + " session's sandbox does not let its commands write " + snapshotCache + ", " +
				launchCache + " and reach launch.example, snapshot.example, which the requested session needs. To " +
				"allow this, add \"" + snapshotCache + "\", \"" + launchCache + "\" to the writes and " +
				"\"launch.example\", \"snapshot.example\" to the hosts in the settings of " + HARNESS_NAME +
				", then start a new " + HARNESS_NAME + " session.");
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
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			Path sessionFolder = Files.createDirectory(root.resolve("session"));
			Path state = Files.createDirectory(root.resolve("state"));
			ParentPolicy writesState = sandboxed(List.of(root), List.of(), Set.of());
			ParentPolicy atSnapshot;
			if (reading.equals("snapshot"))
				atSnapshot = writesState;
			else
				atSnapshot = sandboxed(List.of(sessionFolder), List.of(), Set.of());
			FakeParentPolicySource parent = parent(atSnapshot);
			ChildBound bound = new ChildBound(parent);

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class, () ->
			{
				ChildBound snapshot = bound.snapshot(state);
				parent.allow(writesState);
				snapshot.bound("codex", sessionFolder, List.of(), ExtraPermissions.NONE, Optional.empty(),
					needing(List.of()));
			});

			requireThat(refusal.getMessage(), "message").isEqualTo("CAT cannot start the session: it cannot read " +
				"this " + HARNESS_NAME + " session's sandbox policy safely: the session's sandbox lets its commands " +
				"write " + state + ", so they could change what CAT reads there. Remove " + state + " from the paths " +
				"that the session's sandbox lets its commands write, then start a new session.");
		}
	}

	/**
	 * Verifies that the child of a parent without a sandbox gets none, and is not refused a write or host that no
	 * parent policy allows, since its commands reach no more than the parent's already do.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void givesUnsandboxedParentsChildNoSandbox() throws IOException
	{
		// Requirement: R-I43
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			Path sessionFolder = Files.createDirectory(root.resolve("session"));
			Path outside = Files.createDirectory(root.resolve("outside"));
			FakeParentPolicySource parent = parent(ParentPolicy.UNSANDBOXED);
			parent.select(Map.of("windows.sandbox", "elevated"));
			ChildBound bound = new ChildBound(parent);

			ChildPolicy child = bound.bound("codex", sessionFolder, List.of(),
				new ExtraPermissions(List.of(outside), Set.of("other.example")), Optional.empty(), needing(List.of()));

			requireThat(child.sandboxed(), "sandboxed").isFalse();
			requireThat(child.sandboxSelection(), "sandboxSelection").isEmpty();
			requireThat(child.asParentPolicy().sandboxed(), "asParentPolicy().sandboxed()").isFalse();
		}
	}

	/**
	 * Verifies that the child of a sandboxed parent is sandboxed, bounded by the parent's policy as before, and takes
	 * the parent's selection of its harness's sandbox.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void givesSandboxedParentsChildParentsSandbox() throws IOException
	{
		// Requirement: R-I42
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-bound-")).toRealPath();
			Path sessionFolder = Files.createDirectory(root.resolve("session"));
			FakeParentPolicySource parent = parent(sandboxed(List.of(root), List.of(), Set.of()));
			Map<String, String> selection = Map.of("windows.sandbox", "mxc", "windows.allow_mxc", "true");
			parent.select(selection);
			ChildBound bound = new ChildBound(parent);

			ChildPolicy child = bound.bound("codex", sessionFolder, List.of(), ExtraPermissions.NONE, Optional.empty(),
				needing(List.of()));

			requireThat(child.sandboxed(), "sandboxed").isTrue();
			requireThat(child.writeRoots(), "writeRoots").isEqualTo(List.of(sessionFolder));
			requireThat(child.sandboxSelection(), "sandboxSelection").isEqualTo(selection);
		}
	}

	/**
	 * Verifies that the bound says why prompt optimization cannot run from a parent: none for a sandboxed parent, the
	 * harness's message for a parent without a sandbox, and the harness's report for a parent whose sandbox failed to
	 * start.
	 *
	 * @throws SessionRefusedException if the parent's policy cannot be read
	 */
	@Test
	public void saysWhetherParentHasSandbox() throws SessionRefusedException
	{
		// Requirement: R-I46
		FakeParentPolicySource sandboxedParent = parent(sandboxed(List.of(Path.of("/project").toAbsolutePath()),
			List.of(), Set.of()));
		requireThat(new ChildBound(sandboxedParent).sandboxRefusal(Optional.empty()), "sandboxed").isEmpty();

		FakeParentPolicySource unsandboxedParent = parent(ParentPolicy.UNSANDBOXED);
		requireThat(new ChildBound(unsandboxedParent).sandboxRefusal(Optional.empty()), "unsandboxed").
			isEqualTo(Optional.of(unsandboxedParent.sandboxAbsence()));

		sandboxedParent.failSandbox("Sandbox is enabled but failed to initialize: no socket.");
		requireThat(new ChildBound(sandboxedParent).sandboxRefusal(Optional.empty()).orElseThrow(), "failed").
			contains("Sandbox is enabled but failed to initialize: no socket.");
	}
}
