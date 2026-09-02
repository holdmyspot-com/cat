package io.github.cowwoc.cat.common.optimization.promptsystem;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Evidence linking a related prompt artifact to a frozen target and requirements.
 *
 * @param schemaVersion the document schema version
 * @param evidenceId the immutable evidence identifier
 * @param artifactId the related artifact identifier
 * @param targetId the optimization-target identifier
 * @param relation the artifact's relation to the target
 * @param sourceArtifactIds the artifacts supporting the relation
 * @param requirementIds the requirements affected by the relation
 * @param evidenceSha256 the evidence content fingerprint
 */
public record RelatedPromptEvidenceDocument(int schemaVersion, String evidenceId, String artifactId, String targetId,
	String relation, List<String> sourceArtifactIds, List<String> requirementIds, String evidenceSha256)
{
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

	/**
	 * Validates one evidence document.
	 *
	 * @param schemaVersion the document schema version
	 * @param evidenceId the immutable evidence identifier
	 * @param artifactId the related artifact identifier
	 * @param targetId the optimization-target identifier
	 * @param relation the artifact's relation to the target
	 * @param sourceArtifactIds the artifacts supporting the relation
	 * @param requirementIds the requirements affected by the relation
	 * @param evidenceSha256 the evidence content fingerprint
	 */
	public RelatedPromptEvidenceDocument
	{
		if (schemaVersion != 1 || blank(evidenceId) || blank(artifactId) || blank(targetId) ||
			!Set.of("loads_with", "affects_requirement", "interacts_with").contains(relation) ||
			evidenceSha256 == null || !SHA256.matcher(evidenceSha256).matches())
			throw new IllegalArgumentException("Invalid related prompt evidence");
		sourceArtifactIds = values(sourceArtifactIds);
		requirementIds = values(requirementIds);
	}

	/**
	 * Determines whether text is absent or blank.
	 *
	 * @param value the text
	 * @return whether the text is absent or blank
	 */
	private static boolean blank(String value)
	{
		return value == null || value.isBlank();
	}

	/**
	 * Validates and copies one evidence identifier list.
	 *
	 * @param values the identifiers
	 * @return an immutable identifier list
	 */
	private static List<String> values(List<String> values)
	{
		if (values == null || values.stream().anyMatch(RelatedPromptEvidenceDocument::blank))
			throw new IllegalArgumentException("Evidence identifiers must be nonblank");
		return List.copyOf(values);
	}
}
