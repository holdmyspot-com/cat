package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorLabel;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorResponse;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorResultSchema;
import io.github.cowwoc.cat.common.optimizer.contract.SessionMode;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
import io.github.cowwoc.cat.common.optimizer.statistics.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.trial.SampledTrialInstance;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks category aggregation from the minimum retained outcome records for each claim.
 */
public final class DevelopmentEvidenceRevisionServiceTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String DIGEST = "a".repeat(64);
	private static final LineageKey LINEAGE = new LineageKey("lineage", DIGEST);
	private static final EvidenceRef EPOCH = new EvidenceRef("measurement-epochs", DIGEST);

	/** Creates the test fixture. */
	public DevelopmentEvidenceRevisionServiceTest()
	{
	}

	/**
	 * Three outcomes prove mixed labels within a category and independent totals across categories.
	 *
	 * @throws IOException if immutable fixture records cannot be retained or read
	 */
	@Test
	public void countsLabelsAcrossCategories() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("revision-category-counts-"));
			CampaignStore store = new CampaignStore(directory);
			DevelopmentEvidenceRevision source = revision(List.of(
				outcome(store, "first", 0, EvaluatorLabel.COMPLIANT),
				outcome(store, "first", 1, EvaluatorLabel.NONCOMPLIANT),
				outcome(store, "second", 0, EvaluatorLabel.COMPLIANT)));
			requireThat(new DevelopmentEvidenceRevisionService(store).counts(source), "categoryCounts").
				isEqualTo(Map.of("first", new CategoryCounts(1, 2), "second", new CategoryCounts(1, 1)));
		}
	}

	/**
	 * A category absent from a nonempty revision retains the single-category reader's zero counts.
	 *
	 * @throws IOException if the one outcome cannot be retained or read
	 */
	@Test
	public void countsAbsentCategoryAsZero() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("revision-absent-category-"));
			CampaignStore store = new CampaignStore(directory);
			DevelopmentEvidenceRevision source = revision(List.of(
				outcome(store, "first", 0, EvaluatorLabel.COMPLIANT)));
			requireThat(new DevelopmentEvidenceRevisionService(store).counts(source, "second"),
				"absentCategoryCounts").isEqualTo(new CategoryCounts(0, 0));
		}
	}

	/**
	 * Supplies the revision identity without invoking allocation or native execution.
	 *
	 * @param outcomes exact retained outcomes
	 * @return source for aggregation
	 */
	private static DevelopmentEvidenceRevision revision(List<EvidenceRef> outcomes)
	{
		return new DevelopmentEvidenceRevision(DevelopmentEvidenceRevision.SCHEMA_VERSION, "campaign",
			LINEAGE, DIGEST, EPOCH, outcomes.size(), outcomes);
	}

	/**
	 * Retains one canonical assignment and semantic result for the counting operation.
	 *
	 * @param store test-owned immutable evidence store
	 * @param category category to count
	 * @param ordinal distinct draw within the category
	 * @param label observed semantic label
	 * @return retained outcome reference
	 * @throws IOException if immutable records cannot be retained
	 */
	private static EvidenceRef outcome(CampaignStore store, String category, int ordinal, EvaluatorLabel label)
		throws IOException
	{
		String identity = category + '-' + ordinal;
		TestDefinition definition = new TestDefinition("test-" + identity, category, List.of("r1"), "Always",
			List.of("task"), "Comply", SessionMode.SINGLE_TURN, List.of(), List.of());
		SampledTrialInstance instance = new SampledTrialInstance(identity, EvidencePurpose.DEVELOPMENT,
			"sampler", DIGEST, definition, List.of(), definition.turnTemplates());
		TrialAssignment assignment = new TrialAssignment(TrialAssignment.SCHEMA_VERSION, "campaign", LINEAGE,
			EPOCH, new EvidenceRef("category-decisions", DIGEST), "trial-" + identity, ordinal, DIGEST,
			instance, Instant.EPOCH);
		EvidenceRef assignmentRef = store.appendImmutableJson("trial-assignments", JSON.writeValueAsBytes(assignment));
		TrialOutcomeRecord outcome = new TrialOutcomeRecord(TrialOutcomeRecord.SCHEMA_VERSION, "campaign", LINEAGE,
			assignmentRef, new EvidenceRef("trial-execution-records", DIGEST),
			new EvidenceRef("evaluator-execution-records", DIGEST), EvaluatorResultSchema.LABEL_AND_REASON_JSON_V1,
			new EvaluatorResponse(label, "Observed " + identity));
		return store.appendImmutableJson("trial-outcomes", JSON.writeValueAsBytes(outcome));
	}
}
