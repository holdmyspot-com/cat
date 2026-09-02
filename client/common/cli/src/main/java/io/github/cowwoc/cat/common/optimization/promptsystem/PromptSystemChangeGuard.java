package io.github.cowwoc.cat.common.optimization.promptsystem;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates one isolated prompt-system candidate before any harness or model call.
 *
 * <p>The guard reasons only from frozen captures and the candidate manifest. It does not infer edit authority from a
 * filename or scan files outside the supplied effective-context captures.</p>
 */
public final class PromptSystemChangeGuard
{
	/** Prevents construction of this static utility owner. */
	private PromptSystemChangeGuard()
	{
	}

	/**
	 * Validates a candidate manifest against its frozen prompt system and post-change effective-context capture.
	 *
	 * @param inventory the pre-change complete effective-context capture
	 * @param target the frozen optimization target
	 * @param boundary the frozen edit authority
	 * @param manifest the candidate changes
	 * @param relatedEvidence the evidence linking related artifacts to the target
	 * @param postChangeInventory the caller-supplied post-change effective-context capture
	 */
	public static void validate(ObservationInventoryDocument inventory, OptimizationTargetDocument target,
		EditBoundaryDocument boundary, PromptSystemChangeManifest manifest,
		List<RelatedPromptEvidenceDocument> relatedEvidence, ObservationInventoryDocument postChangeInventory)
	{
		Objects.requireNonNull(inventory, "inventory must not be null");
		Objects.requireNonNull(target, "target must not be null");
		Objects.requireNonNull(boundary, "boundary must not be null");
		Objects.requireNonNull(manifest, "manifest must not be null");
		Objects.requireNonNull(relatedEvidence, "relatedEvidence must not be null");
		Objects.requireNonNull(postChangeInventory, "postChangeInventory must not be null");
		if (!manifest.targetId().equals(target.targetId()) || !manifest.boundaryId().equals(boundary.boundaryId()))
			throw new IllegalArgumentException("Candidate manifest does not match the frozen target and edit boundary");
		Map<String, ObservationInventoryDocument.Artifact> before = artifacts(inventory, "pre-change");
		Map<String, ObservationInventoryDocument.Artifact> after = artifacts(postChangeInventory, "post-change");
		Map<String, RelatedPromptEvidenceDocument> evidence = evidence(relatedEvidence, target.targetId());
		ObservationInventoryDocument.Artifact targetArtifact = artifactByPath(before, target.targetPath(),
			"target");
		if (!targetArtifact.sha256().equals(target.targetSha256()))
			throw new IllegalArgumentException("Frozen target fingerprint does not match the pre-change inventory");
		Set<String> changedBeforePaths = new HashSet<>();
		Set<String> changedAfterPaths = new HashSet<>();
		for (PromptSystemChangeManifest.Change change : manifest.changes())
			validateChange(change, target, boundary, before, after, evidence, changedBeforePaths, changedAfterPaths);
		validatePostChangeCapture(inventory, postChangeInventory, target, before, after, manifest.changes());
	}

	/**
	 * Validates one declared file transition.
	 *
	 * @param change candidate file transition
	 * @param target frozen target
	 * @param boundary frozen edit boundary
	 * @param before artifacts before the candidate
	 * @param after artifacts after the candidate
	 * @param evidence related-artifact evidence by identifier
	 * @param changedBeforePaths changed source paths
	 * @param changedAfterPaths changed resulting paths
	 */
	private static void validateChange(PromptSystemChangeManifest.Change change, OptimizationTargetDocument target,
		EditBoundaryDocument boundary, Map<String, ObservationInventoryDocument.Artifact> before,
		Map<String, ObservationInventoryDocument.Artifact> after, Map<String, RelatedPromptEvidenceDocument> evidence,
		Set<String> changedBeforePaths, Set<String> changedAfterPaths)
	{
		String action = change.action();
		String beforePath = change.beforePath();
		String afterPath = change.afterPath();
		if (beforePath != null && !changedBeforePaths.add(beforePath))
			throw new IllegalArgumentException("Candidate changes a source path more than once: " + beforePath);
		if (afterPath != null && !changedAfterPaths.add(afterPath))
			throw new IllegalArgumentException("Candidate creates a resulting path more than once: " + afterPath);
		if (action.equals("add"))
		{
			ObservationInventoryDocument.Artifact added = artifactByPath(after, afterPath, "added artifact");
			requireEditablePath(afterPath, boundary);
			requireFingerprint(change.afterSha256(), added.sha256(), "added artifact");
			requireEvidence(change, evidence, target, added.artifactId(), true);
			return;
		}
		ObservationInventoryDocument.Artifact original = artifactByPath(before, beforePath, "changed artifact");
		if (boundary.observationOnlyArtifactIds().contains(original.artifactId()))
			throw new IllegalArgumentException("Candidate changes an observation-only artifact: " + beforePath);
		if (!boundary.editableArtifactIds().contains(original.artifactId()) || !original.editable())
			throw new IllegalArgumentException("Candidate changes an artifact outside the edit boundary: " + beforePath);
		requireEditablePath(beforePath, boundary);
		requireFingerprint(change.beforeSha256(), original.sha256(), "changed artifact");
		boolean targetChange = beforePath.equals(target.targetPath());
		if (targetChange && (action.equals("delete") || action.equals("rename") || !beforePath.equals(afterPath)))
			throw new IllegalArgumentException("Candidate deletes, renames, or replaces the optimization target");
		if (action.equals("delete"))
		{
			if (artifactByPathOrNull(after, beforePath) != null)
				throw new IllegalArgumentException("Deleted artifact remains in the post-change capture: " + beforePath);
			requireEvidence(change, evidence, target, original.artifactId(), false);
			return;
		}
		ObservationInventoryDocument.Artifact changed = artifactByPath(after, afterPath, "resulting artifact");
		requireEditablePath(afterPath, boundary);
		requireFingerprint(change.afterSha256(), changed.sha256(), "resulting artifact");
		if (!targetChange)
			requireEvidence(change, evidence, target, original.artifactId(), false);
	}

	/**
	 * Validates that the post-change capture retains the target and every declared added artifact is actually loaded.
	 *
	 * @param beforeInventory the pre-change capture
	 * @param afterInventory the post-change capture
	 * @param target frozen target
	 * @param before artifacts before the candidate
	 * @param after artifacts after the candidate
	 * @param changes candidate changes
	 */
	private static void validatePostChangeCapture(ObservationInventoryDocument beforeInventory,
		ObservationInventoryDocument afterInventory, OptimizationTargetDocument target,
		Map<String, ObservationInventoryDocument.Artifact> before,
		Map<String, ObservationInventoryDocument.Artifact> after, List<PromptSystemChangeManifest.Change> changes)
	{
		validateUndeclaredCaptureChanges(before, after, changes);
		validateCoveredInvocations(beforeInventory, afterInventory, target);

		ObservationInventoryDocument.Artifact targetArtifact = artifactByPath(after, target.targetPath(),
			"post-change target");
		if (!targetArtifact.editable() || targetArtifact.loadKind().equals("observation_only"))
			throw new IllegalArgumentException("Post-change capture no longer loads an editable optimization target");
		Set<String> loaded = new HashSet<>();
		for (ObservationInventoryDocument.Invocation invocation : afterInventory.invocations())
		{
			if (!invocation.targetPath().equals(target.targetPath()))
				throw new IllegalArgumentException("Post-change capture changed a covered target path: " +
					invocation.invocationId());
			loaded.addAll(invocation.loadedArtifactIds());
		}
		for (String loadedId : loaded)
			if (!after.containsKey(loadedId))
				throw new IllegalArgumentException("Post-change capture loads an unclassified artifact: " + loadedId);
		for (PromptSystemChangeManifest.Change change : changes)
			if (change.action().equals("add"))
			{
				ObservationInventoryDocument.Artifact added = artifactByPath(after, change.afterPath(),
					"added artifact");
					for (ObservationInventoryDocument.Invocation invocation : afterInventory.invocations())
					if (!invocation.loadedArtifactIds().contains(added.artifactId()))
						throw new IllegalArgumentException("Added artifact is not loaded in every covered invocation: " +
							added.artifactId());
			}
	}

	/**
	 * Rejects a post-change capture that adds, removes, or changes an artifact the candidate did not declare.
	 *
	 * @param before artifacts before the candidate
	 * @param after artifacts after the candidate
	 * @param changes declared candidate changes
	 */
	private static void validateUndeclaredCaptureChanges(Map<String, ObservationInventoryDocument.Artifact> before,
		Map<String, ObservationInventoryDocument.Artifact> after, List<PromptSystemChangeManifest.Change> changes)
	{
		Map<String, ObservationInventoryDocument.Artifact> beforeByPath = artifactsByPath(before, "pre-change");
		Map<String, ObservationInventoryDocument.Artifact> afterByPath = artifactsByPath(after, "post-change");
		Set<String> declaredBeforePaths = new HashSet<>();
		Set<String> declaredAfterPaths = new HashSet<>();
		for (PromptSystemChangeManifest.Change change : changes)
		{
			if (change.beforePath() != null)
				declaredBeforePaths.add(change.beforePath());
			if (change.afterPath() != null)
				declaredAfterPaths.add(change.afterPath());
		}

		for (Map.Entry<String, ObservationInventoryDocument.Artifact> entry : beforeByPath.entrySet())
		{
			String path = entry.getKey();
			if (declaredBeforePaths.contains(path))
				continue;
			ObservationInventoryDocument.Artifact postChangeArtifact = afterByPath.get(path);
			if (postChangeArtifact == null)
				throw new IllegalArgumentException("Post-change capture removes an undeclared artifact: " + path);
			if (!entry.getValue().equals(postChangeArtifact))
				throw new IllegalArgumentException("Post-change capture changes an undeclared artifact: " + path);
		}

		for (String path : afterByPath.keySet())
			if (!beforeByPath.containsKey(path) && !declaredAfterPaths.contains(path))
				throw new IllegalArgumentException("Post-change capture adds an undeclared artifact: " + path);
	}

	/**
	 * Requires the post-change capture to retain exactly the invocations covered by the frozen pre-change capture.
	 *
	 * @param beforeInventory the frozen pre-change capture
	 * @param afterInventory the caller-supplied post-change capture
	 * @param target the frozen optimization target
	 */
	private static void validateCoveredInvocations(ObservationInventoryDocument beforeInventory,
		ObservationInventoryDocument afterInventory, OptimizationTargetDocument target)
	{
		Map<String, ObservationInventoryDocument.Invocation> before = invocations(beforeInventory, "pre-change");
		Map<String, ObservationInventoryDocument.Invocation> after = invocations(afterInventory, "post-change");
		for (Map.Entry<String, ObservationInventoryDocument.Invocation> entry : before.entrySet())
		{
			ObservationInventoryDocument.Invocation afterInvocation = after.get(entry.getKey());
			if (afterInvocation == null)
			{
				throw new IllegalArgumentException("Post-change capture does not retain covered invocation: " +
					entry.getKey());
			}
			if (!afterInvocation.harness().equals(entry.getValue().harness()) ||
				!afterInvocation.targetPath().equals(target.targetPath()))
			{
				throw new IllegalArgumentException("Post-change capture changes a covered invocation: " + entry.getKey());
			}
		}
		for (String invocationId : after.keySet())
			if (!before.containsKey(invocationId))
				throw new IllegalArgumentException("Post-change capture adds an uncovered invocation: " + invocationId);
	}

	/**
	 * Requires a candidate change to have evidence that names the target and related artifact.
	 *
	 * @param change candidate change
	 * @param evidence related evidence by identifier
	 * @param target frozen target
	 * @param artifactId changed or added artifact identifier
	 * @param addition whether this is an addition
	 */
	private static void requireEvidence(PromptSystemChangeManifest.Change change,
		Map<String, RelatedPromptEvidenceDocument> evidence, OptimizationTargetDocument target, String artifactId,
		boolean addition)
	{
		if (change.relatedEvidenceIds().isEmpty())
			throw new IllegalArgumentException("Candidate change has no related-artifact evidence");
		for (String evidenceId : change.relatedEvidenceIds())
		{
			RelatedPromptEvidenceDocument item = evidence.get(evidenceId);
			if (item == null || !item.targetId().equals(target.targetId()))
				continue;
			if (item.artifactId().equals(artifactId) || addition && item.sourceArtifactIds().contains(artifactId))
				return;
		}
		throw new IllegalArgumentException("Candidate change has no evidence for related artifact: " + artifactId);
	}

	/**
	 * Requires a project-relative path to remain below a frozen editable root.
	 *
	 * @param path candidate path
	 * @param boundary frozen edit boundary
	 */
	private static void requireEditablePath(String path, EditBoundaryDocument boundary)
	{
		if (path == null || path.startsWith("/") || path.equals("..") || path.startsWith("../") ||
			path.contains("/../"))
			throw new IllegalArgumentException("Candidate path is not a safe project-relative path: " + path);
		for (String root : boundary.editableRoots())
			if (root.equals(".") || path.equals(root) || path.startsWith(root + "/"))
				return;
		throw new IllegalArgumentException("Candidate path is outside the edit boundary: " + path);
	}

	/**
	 * Requires a manifest fingerprint to match the captured artifact fingerprint.
	 *
	 * @param declared manifest fingerprint
	 * @param actual captured fingerprint
	 * @param description artifact description
	 */
	private static void requireFingerprint(String declared, String actual, String description)
	{
		if (declared == null || !declared.equals(actual))
			throw new IllegalArgumentException("Candidate " + description + " fingerprint does not match its capture");
	}

	/**
	 * Indexes artifacts by identifier and rejects duplicate identifiers.
	 *
	 * @param inventory captured inventory
	 * @param description capture description
	 * @return artifacts by identifier
	 */
	private static Map<String, ObservationInventoryDocument.Artifact> artifacts(ObservationInventoryDocument inventory,
		String description)
	{
		Map<String, ObservationInventoryDocument.Artifact> result = new HashMap<>();
		for (ObservationInventoryDocument.Artifact artifact : inventory.artifacts())
			if (result.put(artifact.artifactId(), artifact) != null)
				throw new IllegalArgumentException(description + " capture repeats artifact: " + artifact.artifactId());
		return result;
	}

	/**
	 * Indexes covered invocations by identifier and rejects duplicate identifiers.
	 *
	 * @param inventory captured inventory
	 * @param description capture description
	 * @return covered invocations by identifier
	 */
	private static Map<String, ObservationInventoryDocument.Invocation> invocations(
		ObservationInventoryDocument inventory, String description)
	{
		Map<String, ObservationInventoryDocument.Invocation> result = new HashMap<>();
		for (ObservationInventoryDocument.Invocation invocation : inventory.invocations())
			if (result.put(invocation.invocationId(), invocation) != null)
				throw new IllegalArgumentException(description + " capture repeats invocation: " +
					invocation.invocationId());
		return result;
	}

	/**
	 * Indexes artifacts by path and rejects a capture whose path identity is ambiguous.
	 *
	 * @param artifacts artifacts by identifier
	 * @param description capture description
	 * @return artifacts by path
	 */
	private static Map<String, ObservationInventoryDocument.Artifact> artifactsByPath(
		Map<String, ObservationInventoryDocument.Artifact> artifacts, String description)
	{
		Map<String, ObservationInventoryDocument.Artifact> result = new HashMap<>();
		for (ObservationInventoryDocument.Artifact artifact : artifacts.values())
			if (result.put(artifact.path(), artifact) != null)
				throw new IllegalArgumentException(description + " capture repeats artifact path: " + artifact.path());
		return result;
	}

	/**
	 * Indexes evidence and rejects identifiers that would make a manifest ambiguous.
	 *
	 * @param values related-artifact evidence
	 * @param targetId frozen target identifier
	 * @return evidence by identifier
	 */
	private static Map<String, RelatedPromptEvidenceDocument> evidence(List<RelatedPromptEvidenceDocument> values,
		String targetId)
	{
		Map<String, RelatedPromptEvidenceDocument> result = new HashMap<>();
		for (RelatedPromptEvidenceDocument item : values)
		{
			if (item == null)
				throw new NullPointerException("relatedEvidence must not contain null");
			if (!item.targetId().equals(targetId))
				throw new IllegalArgumentException("Related evidence names another optimization target: " + item.evidenceId());
			if (result.put(item.evidenceId(), item) != null)
				throw new IllegalArgumentException("Related evidence repeats identifier: " + item.evidenceId());
		}
		return result;
	}

	/**
	 * Finds one artifact by its project-relative path.
	 *
	 * @param artifacts artifacts by identifier
	 * @param path required project-relative path
	 * @param description requested artifact description
	 * @return the one matching artifact
	 */
	private static ObservationInventoryDocument.Artifact artifactByPath(
		Map<String, ObservationInventoryDocument.Artifact> artifacts, String path, String description)
	{
		ObservationInventoryDocument.Artifact result = artifactByPathOrNull(artifacts, path);
		if (result == null)
			throw new IllegalArgumentException("Capture has no " + description + " at path: " + path);
		return result;
	}

	/**
	 * Finds one artifact by path while rejecting ambiguous duplicate paths.
	 *
	 * @param artifacts artifacts by identifier
	 * @param path candidate project-relative path
	 * @return the matching artifact, or {@code null}
	 */
	private static ObservationInventoryDocument.Artifact artifactByPathOrNull(
		Map<String, ObservationInventoryDocument.Artifact> artifacts, String path)
	{
		ObservationInventoryDocument.Artifact result = null;
		for (ObservationInventoryDocument.Artifact artifact : artifacts.values())
			if (artifact.path().equals(path))
			{
				if (result != null)
					throw new IllegalArgumentException("Capture repeats artifact path: " + path);
				result = artifact;
			}
		return result;
	}
}
