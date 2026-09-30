package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.EditableRoot;
import io.github.cowwoc.cat.common.optimizer.contract.PromptSource;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverage;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationDomain;
import io.github.cowwoc.cat.common.optimizer.runner.RuntimePromptSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Proposes an edit root for the selected captured source while retaining separate native observation evidence.
 * The complete contract still requires user review of this root and every other participating source.
 *
 * @param schemaVersion the proposal schema
 * @param campaignId the campaign that owns the inspection
 * @param sourceCaptureRef the selected path, resolved file, and retained source bytes
 * @param nativeCompletionRef the native result containing observed paths and source coverage limits
 * @param selectedPath the logical path requested for inspection
 * @param resolvedSourcePath the regular file supplying the retained readable bytes
 * @param selectedLinkTarget the link destination text, or empty for a regular selected path
 * @param selectedDirectoryLinks directory links traversed to reach the selected regular file
 * @param retainedSourceSha256 the hash of the H1-captured readable bytes, without a host-consumption claim
 * @param promptPathObservations native path events, which do not prove consumed bytes
 * @param promptSourceCoverage the native host's prompt-path observation coverage
 * @param editableRoot the resolved in-project regular file proposed for content editing
 */
public record SelectedSourceEditProposal(int schemaVersion, String campaignId, EvidenceRef sourceCaptureRef,
	EvidenceRef nativeCompletionRef, Path selectedPath, Path resolvedSourcePath, String selectedLinkTarget,
	List<PromptSource.DirectoryLink> selectedDirectoryLinks, String retainedSourceSha256,
	List<RuntimePromptSource> promptPathObservations,
	ObservationCoverage promptSourceCoverage, EditableRoot editableRoot)
{
	/** The supported proposal schema. */
	public static final int SCHEMA_VERSION = 3;

	/**
	 * Validates the proposal's evidence collections and edit-root shape.
	 *
	 * @param schemaVersion the proposal schema
	 * @param campaignId the campaign that owns the inspection
	 * @param sourceCaptureRef the selected path, resolved file, and retained source bytes
	 * @param nativeCompletionRef the native result containing observed paths and source coverage limits
	 * @param selectedPath the logical path requested for inspection
	 * @param resolvedSourcePath the regular file supplying the retained readable bytes
	 * @param selectedLinkTarget the link destination text, or empty for a regular selected path
	 * @param selectedDirectoryLinks directory links traversed to reach the selected regular file
	 * @param retainedSourceSha256 the hash of the H1-captured readable bytes
	 * @param promptPathObservations native path events without consumed-byte proof
	 * @param promptSourceCoverage native prompt-path observation coverage
	 * @param editableRoot the resolved in-project regular file proposed for content editing
	 */
	public SelectedSourceEditProposal
	{
		Objects.requireNonNull(sourceCaptureRef, "sourceCaptureRef");
		Objects.requireNonNull(nativeCompletionRef, "nativeCompletionRef");
		Objects.requireNonNull(selectedPath, "selectedPath");
		Objects.requireNonNull(resolvedSourcePath, "resolvedSourcePath");
		Objects.requireNonNull(selectedLinkTarget, "selectedLinkTarget");
		selectedDirectoryLinks = List.copyOf(selectedDirectoryLinks);
		promptPathObservations = List.copyOf(promptPathObservations);
		Objects.requireNonNull(promptSourceCoverage, "promptSourceCoverage");
		Objects.requireNonNull(editableRoot, "editableRoot");
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			!sourceCaptureRef.collection().equals("contract-source-captures") ||
			!nativeCompletionRef.collection().equals("native-run-completions") ||
			!relative(selectedPath) || !relative(resolvedSourcePath) ||
			(selectedLinkTarget.isEmpty() && selectedDirectoryLinks.isEmpty()) !=
				selectedPath.equals(resolvedSourcePath) ||
			retainedSourceSha256 == null || !retainedSourceSha256.matches("[0-9a-f]{64}") ||
			promptSourceCoverage.domain() != ObservationDomain.PROMPT_SOURCE ||
			editableRoot.kind() != EditableRoot.Kind.FILE || !editableRoot.path().equals(resolvedSourcePath))
			throw new IllegalArgumentException("Selected source edit proposal is invalid");
	}

	/**
	 * Returns the proposed source identity carried from the retained initial capture.
	 *
	 * @return logical path, resolved file, links, and retained byte hash
	 */
	public PromptSource source()
	{
		return new PromptSource(selectedPath, resolvedSourcePath, selectedLinkTarget, selectedDirectoryLinks,
			retainedSourceSha256);
	}

	/**
	 * Checks a canonical project-relative path.
	 *
	 * @param path selected or resolved source path
	 * @return whether the path is canonical and contained
	 */
	private static boolean relative(Path path)
	{
		return !path.isAbsolute() && !path.toString().isEmpty() && path.equals(path.normalize()) &&
			!path.startsWith("..");
	}
}
