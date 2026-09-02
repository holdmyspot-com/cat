package io.github.cowwoc.cat.common.optimization.promptsystem;

import java.util.List;
import java.util.Set;

/**
 * Candidate prompt-system changes validated against a frozen edit boundary.
 *
 * @param schemaVersion the document schema version
 * @param candidateId the candidate identifier
 * @param targetId the optimization-target identifier
 * @param boundaryId the edit-boundary identifier
 * @param changes the proposed file changes
 */
public record PromptSystemChangeManifest(int schemaVersion, String candidateId, String targetId, String boundaryId,
	List<Change> changes)
{
	/**
	 * Validates one change manifest.
	 *
	 * @param schemaVersion the document schema version
	 * @param candidateId the candidate identifier
	 * @param targetId the optimization-target identifier
	 * @param boundaryId the edit-boundary identifier
	 * @param changes the proposed file changes
	 */
	public PromptSystemChangeManifest
	{
		if (schemaVersion != 1 || blank(candidateId) || blank(targetId) || blank(boundaryId) || changes == null ||
			changes.isEmpty())
			throw new IllegalArgumentException("Invalid prompt-system change manifest");
		changes = List.copyOf(changes);
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
	 * One file change and its traceability evidence.
	 *
	 * @param action the add, modify, delete, or rename action
	 * @param beforePath the previous path, when applicable
	 * @param afterPath the resulting path, when applicable
	 * @param beforeSha256 the prior content fingerprint, when applicable
	 * @param afterSha256 the resulting content fingerprint, when applicable
	 * @param relatedEvidenceIds the evidence identifiers that justify the change
	 */
	public record Change(String action, String beforePath, String afterPath, String beforeSha256, String afterSha256,
		List<String> relatedEvidenceIds)
	{
		/**
		 * Validates one change.
		 *
		 * @param action the add, modify, delete, or rename action
		 * @param beforePath the previous path, when applicable
		 * @param afterPath the resulting path, when applicable
		 * @param beforeSha256 the prior content fingerprint, when applicable
		 * @param afterSha256 the resulting content fingerprint, when applicable
		 * @param relatedEvidenceIds the evidence identifiers that justify the change
		 */
		public Change
		{
			if (!Set.of("add", "modify", "delete", "rename").contains(action))
				throw new IllegalArgumentException("Unsupported prompt-system change action: " + action);
			if ((action.equals("add") && blank(afterPath)) || (!action.equals("add") && blank(beforePath)) ||
				(action.equals("rename") && blank(afterPath)))
				throw new IllegalArgumentException("Change paths do not match action");
			if (relatedEvidenceIds == null)
				relatedEvidenceIds = List.of();
			else
				relatedEvidenceIds = List.copyOf(relatedEvidenceIds);
		}
	}
}
