package io.github.cowwoc.cat.common.optimization.promptsystem;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Freezes one prompt-system inventory supplied by a harness effective-context capture.
 *
 * <p>This owner deliberately does not search a repository. A harness must first capture every artifact that it loaded
 * for the covered prompt-test invocations. Rejecting an incomplete capture is safer than treating an arbitrary file on
 * disk as an instruction the harness might have loaded.</p>
 */
public final class PromptSystemDiscovery
{
	/** Prevents construction of this static utility owner. */
	private PromptSystemDiscovery()
	{
	}

	/**
	 * Freezes one target and edit boundary from a complete effective-context capture.
	 *
	 * @param capture the explicit capture supplied by the harness boundary
	 * @return the frozen inventory, target, and edit boundary
	 */
	public static Result discover(Capture capture)
	{
		Objects.requireNonNull(capture, "capture must not be null");
		ObservationInventoryDocument inventory = capture.inventory();
		Map<String, ObservationInventoryDocument.Artifact> artifacts = artifacts(inventory);
		Set<String> loaded = loadedArtifacts(inventory, artifacts);
		for (ObservationInventoryDocument.Artifact artifact : inventory.artifacts())
		{
			if (!loaded.contains(artifact.artifactId()))
				throw new IllegalArgumentException("Effective-context capture contains an unloaded artifact: " +
					artifact.artifactId());
			if (artifact.loadKind().equals("observation_only") && artifact.editable())
				throw new IllegalArgumentException("Observation-only artifact must not be editable: " +
					artifact.artifactId());
		}
		ObservationInventoryDocument.Artifact target = artifacts.get(capture.targetArtifactId());
		if (target == null)
			throw new IllegalArgumentException("Effective-context capture does not name the target artifact: " +
				capture.targetArtifactId());
		if (!target.editable() || target.loadKind().equals("observation_only"))
			throw new IllegalArgumentException("Optimization target must be an editable loaded artifact: " +
				capture.targetArtifactId());
		for (ObservationInventoryDocument.Invocation invocation : inventory.invocations())
		{
			if (!invocation.targetPath().equals(target.path()))
				throw new IllegalArgumentException("Covered invocation has a different target path: " +
					invocation.invocationId());
		}
		List<String> editable = inventory.artifacts().stream().filter(ObservationInventoryDocument.Artifact::editable).
			map(ObservationInventoryDocument.Artifact::artifactId).sorted().toList();
		List<String> roots = inventory.artifacts().stream().filter(ObservationInventoryDocument.Artifact::editable).
			map(ObservationInventoryDocument.Artifact::path).map(PromptSystemDiscovery::parent).distinct().sorted().toList();
		List<String> observationOnly = inventory.artifacts().stream().filter(artifact ->
			artifact.loadKind().equals("observation_only")).map(ObservationInventoryDocument.Artifact::artifactId).
			sorted().toList();
		OptimizationTargetDocument targetDocument = new OptimizationTargetDocument(1, capture.targetArtifactId(),
			target.path(), target.sha256(), capture.promptTestIds());
		EditBoundaryDocument boundary = new EditBoundaryDocument(1, capture.boundaryId(), editable, roots,
			observationOnly);
		return new Result(inventory, targetDocument, boundary);
	}

	/**
	 * Validates and indexes the captured artifacts.
	 *
	 * @param inventory the captured inventory
	 * @return artifacts by immutable identifier
	 */
	private static Map<String, ObservationInventoryDocument.Artifact> artifacts(ObservationInventoryDocument inventory)
	{
		Map<String, ObservationInventoryDocument.Artifact> result = new HashMap<>();
		for (ObservationInventoryDocument.Artifact artifact : inventory.artifacts())
		{
			if (result.put(artifact.artifactId(), artifact) != null)
				throw new IllegalArgumentException("Effective-context capture repeats artifact: " + artifact.artifactId());
		}
		return result;
	}

	/**
	 * Validates loaded and dependency artifact identifiers.
	 *
	 * @param inventory the captured inventory
	 * @param artifacts artifacts by identifier
	 * @return every loaded artifact identifier
	 */
	private static Set<String> loadedArtifacts(ObservationInventoryDocument inventory,
		Map<String, ObservationInventoryDocument.Artifact> artifacts)
	{
		Set<String> result = new HashSet<>();
		Set<String> invocations = new HashSet<>();
		for (ObservationInventoryDocument.Invocation invocation : inventory.invocations())
		{
			if (!invocations.add(invocation.invocationId()))
				throw new IllegalArgumentException("Effective-context capture repeats invocation: " +
					invocation.invocationId());
			for (String artifactId : invocation.loadedArtifactIds())
			{
				if (!artifacts.containsKey(artifactId))
					throw new IllegalArgumentException("Covered invocation loads an unclassified artifact: " + artifactId);
				result.add(artifactId);
			}
		}
		for (ObservationInventoryDocument.Artifact artifact : artifacts.values())
			for (String dependency : artifact.dependencyArtifactIds())
			{
				if (!artifacts.containsKey(dependency))
					throw new IllegalArgumentException("Captured artifact has an unresolved dependency: " + dependency);
				result.add(dependency);
			}
		return result;
	}

	/**
	 * Returns the project-relative parent of an artifact path.
	 *
	 * @param path the validated project-relative artifact path
	 * @return the containing root, or {@code .} for a root-level artifact
	 */
	private static String parent(String path)
	{
		int slash = path.lastIndexOf('/');
		if (slash < 0)
			return ".";
		return path.substring(0, slash);
	}

	/**
	 * Explicit effective-context capture and caller-selected target identity.
	 *
	 * @param inventory the complete versioned harness capture
	 * @param targetArtifactId the one artifact whose promptTests are optimized
	 * @param boundaryId the immutable edit-boundary identifier
	 * @param promptTestIds the target prompt-test identifiers
	 */
	public record Capture(ObservationInventoryDocument inventory, String targetArtifactId, String boundaryId,
		List<String> promptTestIds)
	{
		/**
		 * Validates the capture envelope.
		 *
		 * @param inventory complete effective-context inventory
		 * @param targetArtifactId one target artifact identifier
		 * @param boundaryId immutable edit-boundary identifier
		 * @param promptTestIds target prompt-test identifiers
		 */
		public Capture
		{
			Objects.requireNonNull(inventory, "inventory must not be null");
			targetArtifactId = text(targetArtifactId, "targetArtifactId");
			boundaryId = text(boundaryId, "boundaryId");
			if (promptTestIds == null || promptTestIds.isEmpty() ||
				promptTestIds.stream().anyMatch(PromptSystemDiscovery::blank))
				throw new IllegalArgumentException("promptTestIds must contain at least one nonblank identifier");
			promptTestIds = promptTestIds.stream().sorted(Comparator.naturalOrder()).toList();
		}
	}

	/**
	 * Frozen prompt-system discovery result.
	 *
	 * @param inventory the complete effective-context inventory
	 * @param target the immutable optimization target
	 * @param boundary the immutable edit authority
	 */
	public record Result(ObservationInventoryDocument inventory, OptimizationTargetDocument target,
		EditBoundaryDocument boundary)
	{
		/**
		 * Validates one discovery result.
		 *
		 * @param inventory complete effective-context inventory
		 * @param target immutable optimization target
		 * @param boundary immutable edit authority
		 */
		public Result
		{
			Objects.requireNonNull(inventory, "inventory must not be null");
			Objects.requireNonNull(target, "target must not be null");
			Objects.requireNonNull(boundary, "boundary must not be null");
		}
	}

	/**
	 * Indicates whether text is missing or blank.
	 *
	 * @param value candidate text
	 * @return whether the text is absent or blank
	 */
	private static boolean blank(String value)
	{
		return value == null || value.isBlank();
	}

	/**
	 * Validates bounded nonblank text.
	 *
	 * @param value candidate text
	 * @param name parameter name
	 * @return the validated text
	 */
	private static String text(String value, String name)
	{
		if (blank(value))
			throw new IllegalArgumentException(name + " must be nonblank");
		return value;
	}
}
