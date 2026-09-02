package io.github.cowwoc.cat.common.optimization.promptsystem;

import java.util.List;

/**
 * Frozen edit authority for one prompt system.
 *
 * @param schemaVersion the document schema version
 * @param boundaryId the immutable edit-boundary identifier
 * @param editableArtifactIds the artifacts CAT may edit
 * @param editableRoots the project-relative roots CAT may edit
 * @param observationOnlyArtifactIds the artifacts CAT may inspect but not edit
 */
public record EditBoundaryDocument(int schemaVersion, String boundaryId, List<String> editableArtifactIds,
	List<String> editableRoots, List<String> observationOnlyArtifactIds)
{
	/**
	 * Validates one edit boundary.
	 *
	 * @param schemaVersion the document schema version
	 * @param boundaryId the immutable edit-boundary identifier
	 * @param editableArtifactIds the artifacts CAT may edit
	 * @param editableRoots the project-relative roots CAT may edit
	 * @param observationOnlyArtifactIds the artifacts CAT may inspect but not edit
	 */
	public EditBoundaryDocument
	{
		if (schemaVersion != 1 || boundaryId == null || boundaryId.isBlank())
			throw new IllegalArgumentException("Invalid edit boundary");
		editableArtifactIds = values(editableArtifactIds, "editableArtifactIds");
		editableRoots = values(editableRoots, "editableRoots");
		observationOnlyArtifactIds = values(observationOnlyArtifactIds, "observationOnlyArtifactIds");
	}

	/**
	 * Validates and copies one identifier list.
	 *
	 * @param values the identifiers
	 * @param name the parameter name
	 * @return an immutable identifier list
	 */
	private static List<String> values(List<String> values, String name)
	{
		if (values == null || values.stream().anyMatch(value -> value == null || value.isBlank()))
			throw new IllegalArgumentException(name + " must contain nonblank values");
		return List.copyOf(values);
	}
}
