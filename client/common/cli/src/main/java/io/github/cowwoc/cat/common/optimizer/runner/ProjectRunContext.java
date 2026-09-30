package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;
import java.util.Optional;

/**
 * Selects a project execution mode and its mode-specific evidence.
 *
 * @param snapshot the selected project's starting snapshot
 * @param mode the live worktree or isolated copy mode
 * @param confirmedEditableRootsRef the confirmed roots for live generation
 * @param liveCheckpointRef the live checkpoint for safe generation recovery
 */
public record ProjectRunContext(ProjectExecutionSnapshot snapshot, ProjectWorkspaceMode mode,
	Optional<EvidenceRef> confirmedEditableRootsRef, Optional<EvidenceRef> liveCheckpointRef)
	implements ExecutionContext
{
	/**
	 * Validates the evidence carried by the selected project mode.
	 *
	 * @param snapshot the selected project's starting snapshot
	 * @param mode the live worktree or isolated copy mode
	 * @param confirmedEditableRootsRef the confirmed roots for live generation
	 * @param liveCheckpointRef the live checkpoint for safe generation recovery
	 * @throws NullPointerException if any parameter is null
	 * @throws IllegalArgumentException if the selected mode has missing or forbidden live-worktree evidence
	 */
	public ProjectRunContext
	{
		Objects.requireNonNull(snapshot, "snapshot");
		Objects.requireNonNull(mode, "mode");
		Objects.requireNonNull(confirmedEditableRootsRef, "confirmedEditableRootsRef");
		Objects.requireNonNull(liveCheckpointRef, "liveCheckpointRef");
		switch (mode)
		{
			case ACTIVE_WORKTREE ->
			{
				if (confirmedEditableRootsRef.isEmpty() || liveCheckpointRef.isEmpty())
				{
					throw new IllegalArgumentException("ACTIVE_WORKTREE requires confirmed editable roots and a live " +
						"checkpoint");
				}
			}
			case ISOLATED_COPY ->
			{
				if (confirmedEditableRootsRef.isPresent() || liveCheckpointRef.isPresent())
					throw new IllegalArgumentException("ISOLATED_COPY must not carry live-worktree evidence");
			}
		}
	}
}
