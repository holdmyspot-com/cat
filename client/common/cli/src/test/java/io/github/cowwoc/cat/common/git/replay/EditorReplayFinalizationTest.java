package io.github.cowwoc.cat.common.git.replay;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.git.Git;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests absorbed-source and origin-placement decisions before a validated plan is published.
 */
public final class EditorReplayFinalizationTest
{
	/**
	 * Creates the test fixture.
	 */
	public EditorReplayFinalizationTest()
	{
	}

	/**
	 * Verifies an absorbed correction may contribute its batch to the retained feature output.
	 *
	 * @throws IOException if the manifest or origin evidence cannot be written
	 */
	@Test
	public void sealsAbsorbedCorrection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "origin-plan-"));
			OriginPlanFixtures.Facts facts = OriginPlanFixtures.correction();
			Path manifestFile = writeInputs(ReplayJson.newMapper(), directory, facts, facts.origins());

			validateSourcePlacements(facts);
			sealing(ReplayJson.newMapper(), directory, facts).sealValidatedPlan(manifestFile, facts.manifest(), facts.plan());

			requireThat(readManifest(ReplayJson.newMapper(), manifestFile).outputPlan(), "sealedPlan").
				isEqualTo(facts.plan().plan());
			requireThat(facts.plan().discardedOutputs(), "discardedOutputs").isEqualTo(List.of("output_2"));
			requireThat(facts.plan().batches().stream().map(Batch::source).toList(), "contributingSources").isEqualTo(
				List.of("source-1", "source-2"));
		}
	}

	/**
	 * Verifies origin contradictions report both paths and the selected output without publishing a plan or witness.
	 *
	 * @throws IOException if the plan conflicts with recorded origin decisions
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		"(?s).*Topic-replay output plan conflicts with recorded origin decisions.*feature.txt.*output_1.*secondary.txt.*")
	public void rejectsConflictingOriginPlan() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "origin-plan-"));
			OriginPlanFixtures.Facts facts = OriginPlanFixtures.conflict();
			Path manifestFile = writeInputs(ReplayJson.newMapper(), directory, facts, facts.origins());
			String original = Files.readString(manifestFile);
			validateSourcePlacements(facts);

			try
			{
				sealing(ReplayJson.newMapper(), directory, facts).sealValidatedPlan(manifestFile, facts.manifest(),
					facts.plan());
			}
			finally
			{
				requireThat(Files.readString(manifestFile), "storedManifest").isEqualTo(original);
				BatchManifest unchanged = readManifest(ReplayJson.newMapper(), manifestFile);
				requireThat(unchanged.outputPlan(), "outputPlan").isNull();
				requireThat(unchanged.outputWitnesses(), "outputWitnesses").isEmpty();
			}
		}
	}

	/**
	 * Verifies a later same-output correction may supersede an earlier reviewed source's path state.
	 *
	 * @throws IOException if the manifest or origin evidence cannot be written
	 */
	@Test
	public void sealsLaterSameOutputCorrection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "origin-plan-"));
			OriginPlanFixtures.Facts facts = OriginPlanFixtures.laterCorrection();
			Path manifestFile = writeInputs(ReplayJson.newMapper(), directory, facts, facts.origins());

			validateSourcePlacements(facts);
			sealing(ReplayJson.newMapper(), directory, facts).sealValidatedPlan(manifestFile, facts.manifest(), facts.plan());

			BatchManifest stored = readManifest(ReplayJson.newMapper(), manifestFile);
			requireThat(stored.outputPlan(), "sealedPlan").isEqualTo(facts.plan().plan());
			requireThat(stored.outputPlan().outputs().getFirst().batches().stream().map(PlannedBatch::source).toList(),
				"batchOrder").isEqualTo(List.of("source-1", "source-2", "source-3"));
			requireThat(facts.plan().witnesses().getFirst().afterTree(), "retainedFinalTree").isEqualTo("tree-3");
			requireThat(facts.read("tree-2", "feature.txt"), "earlierCorrection").isNotEqualTo(
				facts.read("tree-3", "feature.txt"), "retainedResult");
		}
	}

	/**
	 * Verifies an absorbed source needs no batch when the retained target-relative patch already contains its state.
	 *
	 * @throws IOException if the manifest or origin evidence cannot be written
	 */
	@Test
	public void sealsAbsorbedSourceWithoutBatch() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "origin-plan-"));
			OriginPlanFixtures.Facts facts = OriginPlanFixtures.absorbedWithoutBatch();
			Path manifestFile = writeInputs(ReplayJson.newMapper(), directory, facts, facts.origins());

			validateSourcePlacements(facts);
			sealing(ReplayJson.newMapper(), directory, facts).sealValidatedPlan(manifestFile, facts.manifest(), facts.plan());

			requireThat(readManifest(ReplayJson.newMapper(), manifestFile).outputPlan(), "sealedPlan").
				isEqualTo(facts.plan().plan());
			requireThat(facts.plan().batches().stream().map(Batch::source).toList(), "contributingSources").isEqualTo(
				List.of("source-1"));
			requireThat(facts.plan().discardedOutputs(), "discardedOutputs").isEqualTo(List.of("output_2"));
			requireThat(facts.read("tree-2", "feature.txt"), "absorbedState").isEqualTo(
				facts.read(facts.plan().witnesses().getFirst().afterTree(), "feature.txt"));
		}
	}

	/**
	 * Verifies a discarded absorbed source needs no batch even when a later retained result supersedes its path state.
	 *
	 * @throws IOException if the manifest or origin evidence cannot be written
	 */
	@Test
	public void sealsSupersededAbsorbedSource() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "origin-plan-"));
			OriginPlanFixtures.Facts facts = OriginPlanFixtures.supersededAbsorbed();
			Path manifestFile = writeInputs(ReplayJson.newMapper(), directory, facts, facts.origins());

			validateSourcePlacements(facts);
			sealing(ReplayJson.newMapper(), directory, facts).sealValidatedPlan(manifestFile, facts.manifest(), facts.plan());

			requireThat(readManifest(ReplayJson.newMapper(), manifestFile).outputPlan(), "sealedPlan").
				isEqualTo(facts.plan().plan());
			requireThat(facts.plan().batches().stream().map(Batch::source).toList(), "contributingSources").isEqualTo(
				List.of("source-1"));
			requireThat(facts.plan().discardedOutputs(), "discardedOutputs").isEqualTo(List.of("output_2", "output_3"));
			requireThat(facts.plan().witnesses().getFirst().afterTree(), "retainedFinalTree").isEqualTo("tree-3");
			requireThat(facts.read("tree-2", "feature.txt"), "absorbedIntermediateState").isNotEqualTo(
				facts.read("tree-3", "feature.txt"), "retainedResult");
		}
	}

	/**
	 * Verifies a split source cannot leave the removed invalid test without an explicit placement decision.
	 *
	 * @throws IOException if the source review leaves a changed path unreviewed
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*explicitly place every changed path.*")
	public void rejectsUnplacedSplitArtifact() throws IOException
	{
		OriginPlanFixtures.Facts facts = OriginPlanFixtures.removal();
		SourceReview complete = facts.manifest().sourceReviews().getLast();
		SourceReview incomplete = new SourceReview(complete.source(), complete.disposition(), complete.destinations(),
			List.of(complete.placements().getFirst()), complete.counterfactual(), complete.evidence());

		new SourceReviewValidation().validateInput(facts.manifest(),
			List.of(facts.manifest().sourceReviews().getFirst(), incomplete), true);
	}

	/**
	 * Verifies a removal with an exact-path predecessor needs an origin review before the plan is published.
	 *
	 * @throws IOException if required origin reviews are absent
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*requires origin reviews.*")
	public void rejectsMissingRemovalOrigin() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "origin-plan-"));
			OriginPlanFixtures.Facts facts = OriginPlanFixtures.removal();
			Path manifestFile = writeInputs(ReplayJson.newMapper(), directory, facts, List.of());
			String original = Files.readString(manifestFile);
			validateSourcePlacements(facts);

			try
			{
				sealing(ReplayJson.newMapper(), directory, facts).sealValidatedPlan(manifestFile, facts.manifest(),
					facts.plan());
			}
			finally
			{
				requireThat(Files.readString(manifestFile), "storedManifest").isEqualTo(original);
			}
		}
	}

	/**
	 * Verifies invalid-test removal seals with its feature while an independent routing batch stays in another output.
	 *
	 * @throws IOException if the manifest or origin evidence cannot be written
	 */
	@Test
	public void sealsRemovalAndIndependentRouting() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "origin-plan-"));
			OriginPlanFixtures.Facts facts = OriginPlanFixtures.removal();
			Path manifestFile = writeInputs(ReplayJson.newMapper(), directory, facts, facts.origins());

			validateSourcePlacements(facts);
			sealing(ReplayJson.newMapper(), directory, facts).sealValidatedPlan(manifestFile, facts.manifest(), facts.plan());

			OutputPlan stored = readManifest(ReplayJson.newMapper(), manifestFile).outputPlan();
			requireThat(stored, "sealedPlan").isEqualTo(facts.plan().plan());
			requireThat(stored.outputs().getFirst().batches().stream().map(PlannedBatch::id).toList(), "featureBatches").
				isEqualTo(List.of("feature", "removal"));
			requireThat(stored.outputs().getLast().batches().stream().map(PlannedBatch::id).toList(), "routingBatches").
				isEqualTo(List.of("routing"));
		}
	}

	/**
	 * Runs the production source-placement policies against captured projected batches.
	 *
	 * @param facts the captured manifest and projected output decisions
	 * @throws IOException if a completed source review contradicts its projected destinations
	 */
	private static void validateSourcePlacements(OriginPlanFixtures.Facts facts) throws IOException
	{
		SourceReviewValidation validation = new SourceReviewValidation();
		validation.validateInput(facts.manifest(), facts.manifest().sourceReviews(), true);
		Map<String, Set<String>> destinations = facts.plan().batches().stream().collect(Collectors.groupingBy(
			Batch::source, Collectors.mapping(Batch::destination, Collectors.toSet())));
		OutputPlanValidation.validateSourceDestinations(facts.manifest(), facts.plan().plan().outputs(), destinations);
		BatchManifest projected = facts.manifest().withBatches(facts.plan().batches()).withOutputWitnesses(
			facts.plan().witnesses()).withDiscardedOutputs(facts.plan().discardedOutputs());
		validation.validateRecordedReviews(projected, projected.sources().stream().map(SourceSnapshot::hash).
			collect(Collectors.toSet()));
	}

	/**
	 * Writes the original unsealed manifest and its origin decisions.
	 *
	 * @param json the JSON mapper
	 * @param directory the owned manifest directory
	 * @param facts the captured manifest
	 * @param origins the origin decisions to record
	 * @return the manifest file
	 * @throws IOException if either evidence artifact cannot be written
	 */
	private static Path writeInputs(JsonMapper json, Path directory, OriginPlanFixtures.Facts facts,
		List<OriginReview> origins) throws IOException
	{
		Path file = directory.resolve("manifest.json");
		json.writeValue(file.toFile(), facts.manifest());
		new ReviewEvidence(json).writeOriginReviews(file, origins);
		return file;
	}

	/**
	 * Creates the production publication boundary using captured origin facts.
	 *
	 * @param json the JSON mapper
	 * @param directory the owned manifest directory
	 * @param facts captured tree-path states
	 * @return the publication boundary
	 */
	private static OutputPlanSealing sealing(JsonMapper json, Path directory, OriginPlanFixtures.Facts facts)
	{
		ManifestStore<BatchManifest> manifests = new ManifestStore<>(directory, json, BatchManifest.class);
		Git git = new Git(directory);
		History history = new History(directory, git);
		PatchArtifacts patches = new PatchArtifacts();
		OutputPlanValidation outputPlan = new OutputPlanValidation(history, patches,
			new PatchApplication(git, manifests, patches));
		return new OutputPlanSealing(json, manifests, new SourceReviewValidation(), new OriginCandidates(),
			new OriginReviewValidation(facts::read), outputPlan, new ReviewEvidence(json));
	}

	/**
	 * Reads the plan publication's stored manifest.
	 *
	 * @param json the JSON mapper
	 * @param file the published manifest
	 * @return the stored manifest
	 * @throws IOException if the manifest cannot be read
	 */
	private static BatchManifest readManifest(JsonMapper json, Path file) throws IOException
	{
		return json.readValue(file.toFile(), BatchManifest.class);
	}
}
