package io.github.cowwoc.cat.common.optimizer.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.nio.file.Path;
import java.util.Optional;

import org.testng.annotations.Test;

/**
 * Tests project execution context selection before a native call.
 */
public final class ProjectRunContextTest
{
	/**
	 * Creates a test instance.
	 */
	public ProjectRunContextTest()
	{
	}

	/**
	 * Verifies an active worktree carries both confirmed edit authority and a live checkpoint.
	 */
	@Test
	public void acceptsActiveContextWithBothReferences()
	{
		ProjectRunContext context = new ProjectRunContext(snapshot(), ProjectWorkspaceMode.ACTIVE_WORKTREE,
			Optional.of(reference("editable-roots")), Optional.of(reference("live-checkpoints")));
		requireThat(context.mode(), "mode").isEqualTo(ProjectWorkspaceMode.ACTIVE_WORKTREE);
		requireThat(context.confirmedEditableRootsRef().isPresent(), "hasEditableRoots").isTrue();
		requireThat(context.liveCheckpointRef().isPresent(), "hasLiveCheckpoint").isTrue();
	}

	/**
	 * Verifies an isolated project copy carries neither live-worktree reference.
	 */
	@Test
	public void acceptsIsolatedCopyWithoutLiveReferences()
	{
		ProjectRunContext context = new ProjectRunContext(snapshot(), ProjectWorkspaceMode.ISOLATED_COPY,
			Optional.empty(), Optional.empty());
		requireThat(context.confirmedEditableRootsRef().isEmpty(), "hasNoEditableRoots").isTrue();
		requireThat(context.liveCheckpointRef().isEmpty(), "hasNoLiveCheckpoint").isTrue();
	}

	/**
	 * Verifies an active worktree cannot omit confirmed edit authority.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsActiveContextWithoutEditableRoots()
	{
		new ProjectRunContext(snapshot(), ProjectWorkspaceMode.ACTIVE_WORKTREE, Optional.empty(),
			Optional.of(reference("live-checkpoints")));
	}

	/**
	 * Verifies an active worktree cannot omit its live checkpoint.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsActiveContextWithoutCheckpoint()
	{
		new ProjectRunContext(snapshot(), ProjectWorkspaceMode.ACTIVE_WORKTREE,
			Optional.of(reference("editable-roots")), Optional.empty());
	}

	/**
	 * Verifies isolated copies carry no active-worktree edit authority.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsIsolatedCopyWithEditableRoots()
	{
		new ProjectRunContext(snapshot(), ProjectWorkspaceMode.ISOLATED_COPY,
			Optional.of(reference("editable-roots")), Optional.empty());
	}

	/**
	 * Verifies isolated copies cannot borrow an active call's live checkpoint.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsIsolatedCopyWithCheckpoint()
	{
		new ProjectRunContext(snapshot(), ProjectWorkspaceMode.ISOLATED_COPY, Optional.empty(),
			Optional.of(reference("live-checkpoints")));
	}

	/**
	 * Verifies a project snapshot cannot identify a relative root.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsRelativeProjectRoot()
	{
		new ProjectExecutionSnapshot(Path.of("relative-project"), reference("snapshots"));
	}

	/**
	 * Verifies the caller cannot supply a path retaining redundant parent components.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsUnnormalizedProjectRoot()
	{
		new ProjectExecutionSnapshot(Path.of("project/../project").toAbsolutePath(), reference("snapshots"));
	}

	/**
	 * Creates one absolute project identity without touching process-wide state.
	 *
	 * @return a project snapshot
	 */
	private static ProjectExecutionSnapshot snapshot()
	{
		return new ProjectExecutionSnapshot(Path.of("project").toAbsolutePath().normalize(), reference("snapshots"));
	}

	/**
	 * Creates an exact content address for a test category.
	 *
	 * @param collection the evidence collection
	 * @return an evidence address
	 */
	private static EvidenceRef reference(String collection)
	{
		return new EvidenceRef(collection, "a".repeat(64));
	}
}
