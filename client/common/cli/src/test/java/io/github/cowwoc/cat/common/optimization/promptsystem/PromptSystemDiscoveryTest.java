package io.github.cowwoc.cat.common.optimization.promptsystem;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import java.util.List;
import org.testng.annotations.Test;

/** Verifies effective-context-only discovery and deterministic candidate change validation. */
public final class PromptSystemDiscoveryTest
{
	private static final String TARGET_SHA = "a".repeat(64);
	private static final String RELATED_SHA = "b".repeat(64);
	private static final String UPDATED_TARGET_SHA = "c".repeat(64);
	private static final String ADDED_SHA = "d".repeat(64);

	/** Creates the test owner. */
	public PromptSystemDiscoveryTest()
	{
	}

	/** Verifies discovery derives one frozen target and editable roots from only loaded artifacts. */
	@Test
	public void derivesFrozenTargetAndBoundary()
	{
		PromptSystemDiscovery.Result result = PromptSystemDiscovery.discover(new PromptSystemDiscovery.Capture(inventory(),
			"target", "boundary", List.of("test-b", "test-a")));

		requireThat(result.target().targetPath(), "targetPath").isEqualTo("AGENTS.md");
		requireThat(result.target().promptTestIds(), "promptTestIds").isEqualTo(List.of("test-a", "test-b"));
		requireThat(result.boundary().editableArtifactIds(), "editableArtifacts").isEqualTo(List.of("related", "target"));
		requireThat(result.boundary().editableRoots(), "editableRoots").isEqualTo(List.of(".", "rules"));
		requireThat(result.boundary().observationOnlyArtifactIds(), "observationOnly").isEqualTo(List.of("system"));
	}

	/** Verifies discovery rejects a capture that claims an artifact was observed but never loaded. */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Effective-context capture contains an unloaded artifact: unloaded.*")
	public void rejectsUnloadedArtifact()
	{
		ObservationInventoryDocument inventory = inventory();
		ObservationInventoryDocument.Artifact unloaded = new ObservationInventoryDocument.Artifact("unloaded",
			"unused.md", ADDED_SHA, "always", true, List.of(), List.of());
		ObservationInventoryDocument changed = new ObservationInventoryDocument(1, inventory.inventoryId(),
			inventory.projectRootSha256(), inventory.invocations(), List.of(inventory.artifacts().getFirst(),
			inventory.artifacts().get(1), inventory.artifacts().get(2), unloaded));

		PromptSystemDiscovery.discover(new PromptSystemDiscovery.Capture(changed, "target", "boundary", List.of("test")));
	}

	/** Verifies a target edit and evidence-backed related-file edit pass the guard. */
	@Test
	public void acceptsTargetAndRelatedFileEdits()
	{
		PromptSystemDiscovery.Result discovered = discovered();
		PromptSystemChangeManifest manifest = new PromptSystemChangeManifest(1, "candidate", "target", "boundary",
			List.of(new PromptSystemChangeManifest.Change("modify", "AGENTS.md", "AGENTS.md", TARGET_SHA,
				UPDATED_TARGET_SHA, List.of()), new PromptSystemChangeManifest.Change("modify", "rules/review.md",
				"rules/review.md", RELATED_SHA, RELATED_SHA, List.of("related-evidence"))));

		PromptSystemChangeGuard.validate(discovered.inventory(), discovered.target(), discovered.boundary(), manifest,
			List.of(relatedEvidence("related", List.of("target"))), postChangeInventory(false, false, true));
	}

	/** Verifies a post-change capture cannot hide an undeclared target write. */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Post-change capture changes an undeclared artifact: AGENTS.md.*")
	public void rejectsUndeclaredTargetChange()
	{
		PromptSystemDiscovery.Result discovered = discovered();
		PromptSystemChangeManifest manifest = new PromptSystemChangeManifest(1, "candidate", "target", "boundary",
			List.of(new PromptSystemChangeManifest.Change("modify", "rules/review.md", "rules/review.md", RELATED_SHA,
				RELATED_SHA, List.of("related-evidence"))));

		PromptSystemChangeGuard.validate(discovered.inventory(), discovered.target(), discovered.boundary(), manifest,
			List.of(relatedEvidence("related", List.of("target"))), postChangeInventory(false, false, true));
	}

	/** Verifies a post-change capture cannot introduce an undeclared loaded artifact. */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Post-change capture adds an undeclared artifact: rules/examples.md.*")
	public void rejectsUndeclaredAddedArtifact()
	{
		PromptSystemDiscovery.Result discovered = discovered();
		PromptSystemChangeManifest manifest = new PromptSystemChangeManifest(1, "candidate", "target", "boundary",
			List.of(new PromptSystemChangeManifest.Change("modify", "rules/review.md", "rules/review.md", RELATED_SHA,
				RELATED_SHA, List.of("related-evidence"))));

		PromptSystemChangeGuard.validate(discovered.inventory(), discovered.target(), discovered.boundary(), manifest,
			List.of(relatedEvidence("related", List.of("target"))), postChangeInventory(true, true, false));
	}

	/** Verifies a post-change capture cannot omit a covered invocation. */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Post-change capture does not retain covered invocation: invocation-b.*")
	public void rejectsDroppedCoveredInvocation()
	{
		PromptSystemDiscovery.Result discovered = discovered();
		PromptSystemChangeManifest manifest = new PromptSystemChangeManifest(1, "candidate", "target", "boundary",
			List.of(new PromptSystemChangeManifest.Change("modify", "rules/review.md", "rules/review.md", RELATED_SHA,
				RELATED_SHA, List.of("related-evidence"))));
		ObservationInventoryDocument completeCapture = postChangeInventory(false, false, false);
		ObservationInventoryDocument incompleteCapture = new ObservationInventoryDocument(1, "post-inventory",
			"2".repeat(64), List.of(completeCapture.invocations().getFirst()), completeCapture.artifacts());

		PromptSystemChangeGuard.validate(discovered.inventory(), discovered.target(), discovered.boundary(), manifest,
			List.of(relatedEvidence("related", List.of("target"))), incompleteCapture);
	}

	/** Verifies a related split becomes part of every covered invocation. */
	@Test
	public void acceptsLoadedRelatedSplit()
	{
		PromptSystemDiscovery.Result discovered = discovered();
		PromptSystemChangeManifest manifest = new PromptSystemChangeManifest(1, "candidate", "target", "boundary",
			List.of(new PromptSystemChangeManifest.Change("add", null, "rules/examples.md", null, ADDED_SHA,
				List.of("added-evidence"))));

		PromptSystemChangeGuard.validate(discovered.inventory(), discovered.target(), discovered.boundary(), manifest,
			List.of(relatedEvidence("added", List.of("related"))), postChangeInventory(true, true, false));
	}

	/** Verifies a split addition must become loaded in every covered invocation. */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Added artifact is not loaded in every covered invocation.*")
	public void rejectsIncompleteAddedArtifact()
	{
		PromptSystemDiscovery.Result discovered = discovered();
		PromptSystemChangeManifest manifest = new PromptSystemChangeManifest(1, "candidate", "target", "boundary",
			List.of(new PromptSystemChangeManifest.Change("add", null, "rules/examples.md", null, ADDED_SHA,
				List.of("added-evidence"))));

		PromptSystemChangeGuard.validate(discovered.inventory(), discovered.target(), discovered.boundary(), manifest,
			List.of(relatedEvidence("added", List.of("related"))), postChangeInventory(true, false, false));
	}

	/** Verifies a candidate cannot delete or rename the optimization target. */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Candidate deletes, renames, or replaces the optimization target.*")
	public void rejectsTargetReplacement()
	{
		PromptSystemDiscovery.Result discovered = discovered();
		PromptSystemChangeManifest manifest = new PromptSystemChangeManifest(1, "candidate", "target", "boundary",
			List.of(new PromptSystemChangeManifest.Change("rename", "AGENTS.md", "renamed.md", TARGET_SHA,
				UPDATED_TARGET_SHA, List.of())));

		PromptSystemChangeGuard.validate(discovered.inventory(), discovered.target(), discovered.boundary(), manifest,
			List.of(), postChangeInventory(false, false, false));
	}

	/** Verifies a candidate cannot edit an artifact that the capture retained only for observation. */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Candidate changes an observation-only artifact: system/policy.*")
	public void rejectsObservationOnlyEdit()
	{
		PromptSystemDiscovery.Result discovered = discovered();
		PromptSystemChangeManifest manifest = new PromptSystemChangeManifest(1, "candidate", "target", "boundary",
			List.of(new PromptSystemChangeManifest.Change("modify", "system/policy", "system/policy", "f".repeat(64),
				"f".repeat(64), List.of("system-evidence"))));

		PromptSystemChangeGuard.validate(discovered.inventory(), discovered.target(), discovered.boundary(), manifest,
			List.of(relatedEvidence("system", List.of("target"))), postChangeInventory(false, false, false));
	}

	/**
	 * Returns one complete pre-change effective-context capture.
	 *
	 * @return complete immutable capture
	 */
	private static ObservationInventoryDocument inventory()
	{
		return new ObservationInventoryDocument(1, "inventory", "e".repeat(64),
			List.of(new ObservationInventoryDocument.Invocation("invocation-a", "codex", "AGENTS.md",
				List.of("target", "related", "system")), new ObservationInventoryDocument.Invocation("invocation-b",
				"codex", "AGENTS.md", List.of("target", "related", "system"))),
			List.of(new ObservationInventoryDocument.Artifact("target", "AGENTS.md", TARGET_SHA, "always", true,
				List.of(), List.of()), new ObservationInventoryDocument.Artifact("related", "rules/review.md", RELATED_SHA,
				"conditional", true, List.of("target"), List.of("condition")),
				new ObservationInventoryDocument.Artifact("system", "system/policy", "f".repeat(64),
					"observation_only", false, List.of(), List.of())));
	}

	/**
	 * Returns one discovered pre-change boundary.
	 *
	 * @return frozen discovery result
	 */
	private static PromptSystemDiscovery.Result discovered()
	{
		return PromptSystemDiscovery.discover(new PromptSystemDiscovery.Capture(inventory(), "target", "boundary",
			List.of("test")));
	}

	/**
	 * Returns a related-file evidence document.
	 *
	 * @param artifactId related artifact identifier
	 * @param sources source artifact identifiers
	 * @return immutable related-file evidence
	 */
	private static RelatedPromptEvidenceDocument relatedEvidence(String artifactId, List<String> sources)
	{
		return new RelatedPromptEvidenceDocument(1, artifactId + "-evidence", artifactId, "target", "loads_with",
			sources, List.of(), "1".repeat(64));
	}

	/**
	 * Returns one post-change capture.
	 *
	 * @param addArtifact whether the candidate creates a companion artifact
	 * @param loadAddedEverywhere whether every invocation loads the companion artifact
	 * @param targetChanged whether the candidate changes the optimization target
	 * @return complete post-change capture
	 */
	private static ObservationInventoryDocument postChangeInventory(boolean addArtifact, boolean loadAddedEverywhere,
		boolean targetChanged)
	{
		List<String> first = List.of("target", "related", "system", "added");
		List<String> second = first;
		String targetFingerprint = TARGET_SHA;
		if (!loadAddedEverywhere)
			second = List.of("target", "related", "system");
		if (targetChanged)
			targetFingerprint = UPDATED_TARGET_SHA;
		List<ObservationInventoryDocument.Artifact> artifacts = List.of(
			new ObservationInventoryDocument.Artifact("target", "AGENTS.md", targetFingerprint, "always", true,
				List.of(), List.of()), new ObservationInventoryDocument.Artifact("related", "rules/review.md", RELATED_SHA,
				"conditional", true, List.of("target"), List.of("condition")),
			new ObservationInventoryDocument.Artifact("system", "system/policy", "f".repeat(64), "observation_only",
				false, List.of(), List.of()), new ObservationInventoryDocument.Artifact("added", "rules/examples.md", ADDED_SHA,
				"always", true, List.of("related"), List.of()));
		if (!addArtifact)
		{
			first = List.of("target", "related", "system");
			second = first;
			artifacts = artifacts.subList(0, 3);
		}
		return new ObservationInventoryDocument(1, "post-inventory", "2".repeat(64),
			List.of(new ObservationInventoryDocument.Invocation("invocation-a", "codex", "AGENTS.md", first),
				new ObservationInventoryDocument.Invocation("invocation-b", "codex", "AGENTS.md", second)), artifacts);
	}
}
