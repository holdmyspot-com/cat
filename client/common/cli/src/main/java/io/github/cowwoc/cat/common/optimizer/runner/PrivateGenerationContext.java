package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Binds candidate authoring to a private Git worktree prepared from the selected project.
 *
 * @param selectedProject selected project that authoring must leave unchanged
 * @param privateProject private worktree where native authoring runs
 * @param generationRequestRef indexed request that names this attempt
 * @param readinessRef verified private worktree before authoring
 * @param confirmedRootsRef confirmed editable paths for the selected source
 */
public record PrivateGenerationContext(Path selectedProject, Path privateProject,
	EvidenceRef generationRequestRef, EvidenceRef readinessRef, EvidenceRef confirmedRootsRef)
	implements ExecutionContext
{
	/**
	 * Validates the separate worktree locations and the evidence carried to native authoring.
	 *
	 * @param selectedProject selected project that authoring must leave unchanged
	 * @param privateProject private worktree where native authoring runs
	 * @param generationRequestRef indexed request that names this attempt
	 * @param readinessRef verified private worktree before authoring
	 * @param confirmedRootsRef confirmed editable paths for the selected source
	 */
	public PrivateGenerationContext
	{
		Objects.requireNonNull(selectedProject, "selectedProject");
		Objects.requireNonNull(privateProject, "privateProject");
		Objects.requireNonNull(generationRequestRef, "generationRequestRef");
		Objects.requireNonNull(readinessRef, "readinessRef");
		Objects.requireNonNull(confirmedRootsRef, "confirmedRootsRef");
		if (!selectedProject.isAbsolute() || !selectedProject.equals(selectedProject.normalize()))
			throw new IllegalArgumentException("selectedProject must be absolute and normalized");
		if (!privateProject.isAbsolute() || !privateProject.equals(privateProject.normalize()))
			throw new IllegalArgumentException("privateProject must be absolute and normalized");
		if (privateProject.equals(selectedProject))
			throw new IllegalArgumentException("privateProject must differ from selectedProject");
		if (!generationRequestRef.collection().equals("candidate-generation-requests"))
			throw new IllegalArgumentException("generationRequestRef must name candidate-generation-requests");
		if (!readinessRef.collection().equals("generation-attempt-readiness"))
			throw new IllegalArgumentException("readinessRef must name generation-attempt-readiness");
		if (!confirmedRootsRef.collection().equals("confirmed-editable-roots"))
			throw new IllegalArgumentException("confirmedRootsRef must name confirmed-editable-roots");
	}
}
