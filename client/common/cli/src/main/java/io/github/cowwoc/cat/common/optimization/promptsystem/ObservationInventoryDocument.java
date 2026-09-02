package io.github.cowwoc.cat.common.optimization.promptsystem;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict immutable inventory of artifacts observed in effective harness contexts.
 *
 * @param schemaVersion the document schema version
 * @param inventoryId the immutable inventory identifier
 * @param projectRootSha256 the project-root fingerprint
 * @param invocations the effective harness invocations
 * @param artifacts the observed prompt-system artifacts
 */
public record ObservationInventoryDocument(int schemaVersion, String inventoryId, String projectRootSha256,
	List<Invocation> invocations, List<Artifact> artifacts)
{
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

	/**
	 * Validates and snapshots an inventory.
	 *
	 * @param schemaVersion the document schema version
	 * @param inventoryId the immutable inventory identifier
	 * @param projectRootSha256 the project-root fingerprint
	 * @param invocations the effective harness invocations
	 * @param artifacts the observed prompt-system artifacts
	 */
	public ObservationInventoryDocument
	{
		if (schemaVersion != 1)
			throw new IllegalArgumentException("Unsupported observation inventory schema version: " + schemaVersion);
		inventoryId = text(inventoryId, "inventoryId");
		sha(projectRootSha256, "projectRootSha256");
		invocations = copy(invocations, "invocations");
		artifacts = copy(artifacts, "artifacts");
	}

	/**
	 * Validates bounded nonblank text.
	 *
	 * @param value the text
	 * @param name the parameter name
	 * @return the validated text
	 */
	private static String text(String value, String name)
	{
		if (value == null)
			throw new NullPointerException(name + " must not be null");
		if (value.isBlank() || value.length() > 128)
			throw new IllegalArgumentException(name + " must be nonblank and at most 128 characters");
		return value;
	}

	/**
	 * Validates one lowercase SHA-256 fingerprint.
	 *
	 * @param value the fingerprint
	 * @param name the parameter name
	 * @return the validated fingerprint
	 */
	private static String sha(String value, String name)
	{
		if (value == null)
			throw new NullPointerException(name + " must not be null");
		if (!SHA256.matcher(value).matches())
			throw new IllegalArgumentException(name + " must be a lowercase SHA-256 fingerprint");
		return value;
	}

	/**
	 * Validates and copies one bounded list.
	 *
	 * @param <T> the value type
	 * @param values the values
	 * @param name the parameter name
	 * @return an immutable value list
	 */
	private static <T> List<T> copy(List<T> values, String name)
	{
		if (values == null)
			throw new NullPointerException(name + " must not be null");
		if (values.size() > 4096 || values.stream().anyMatch(Objects::isNull))
			throw new IllegalArgumentException(name + " must contain at most 4096 entries");
		return List.copyOf(values);
	}

	/**
	 * One harness effective-context invocation.
	 *
	 * @param invocationId the invocation identifier
	 * @param harness the closed harness identifier
	 * @param targetPath the invoked target's project-relative path
	 * @param loadedArtifactIds the artifacts loaded for the invocation
	 */
	public record Invocation(String invocationId, String harness, String targetPath, List<String> loadedArtifactIds)
	{
		/**
		 * Validates one invocation.
		 *
		 * @param invocationId the invocation identifier
		 * @param harness the closed harness identifier
		 * @param targetPath the invoked target's project-relative path
		 * @param loadedArtifactIds the artifacts loaded for the invocation
		 */
		public Invocation
		{
			invocationId = text(invocationId, "invocationId");
			if (!Set.of("codex", "claude").contains(harness))
				throw new IllegalArgumentException("Unsupported harness: " + harness);
			targetPath = path(targetPath, "targetPath");
			loadedArtifactIds = copy(loadedArtifactIds, "loadedArtifactIds");
		}
	}

	/**
	 * One observed prompt-system artifact.
	 *
	 * @param artifactId the artifact identifier
	 * @param path the artifact's project-relative path
	 * @param sha256 the artifact content fingerprint
	 * @param loadKind the always, conditional, or observation-only load kind
	 * @param editable whether CAT may edit the artifact
	 * @param dependencyArtifactIds the loaded dependencies
	 * @param conditionEvidenceIds the evidence supporting conditional loading
	 */
	public record Artifact(String artifactId, String path, String sha256, String loadKind, boolean editable,
		List<String> dependencyArtifactIds, List<String> conditionEvidenceIds)
	{
		/**
		 * Validates one artifact.
		 *
		 * @param artifactId the artifact identifier
		 * @param path the artifact's project-relative path
		 * @param sha256 the artifact content fingerprint
		 * @param loadKind the always, conditional, or observation-only load kind
		 * @param editable whether CAT may edit the artifact
		 * @param dependencyArtifactIds the loaded dependencies
		 * @param conditionEvidenceIds the evidence supporting conditional loading
		 */
		public Artifact
		{
			artifactId = text(artifactId, "artifactId");
			path = ObservationInventoryDocument.path(path, "path");
			sha256 = sha(sha256, "sha256");
			if (!Set.of("always", "conditional", "observation_only").contains(loadKind))
				throw new IllegalArgumentException("Unsupported load kind: " + loadKind);
			dependencyArtifactIds = copy(dependencyArtifactIds, "dependencyArtifactIds");
			conditionEvidenceIds = copy(conditionEvidenceIds, "conditionEvidenceIds");
		}
	}

	/**
	 * Validates one project-relative path.
	 *
	 * @param value the path
	 * @param name the parameter name
	 * @return the validated path
	 */
	private static String path(String value, String name)
	{
		text(value, name);
		if (value.startsWith("/") || value.equals("..") || value.startsWith("../") || value.contains("/../"))
			throw new IllegalArgumentException(name + " must be project-relative without ..");
		return value;
	}
}
