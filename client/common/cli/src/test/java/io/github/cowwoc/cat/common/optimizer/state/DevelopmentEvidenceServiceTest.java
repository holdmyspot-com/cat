package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorLabel;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorResponse;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorResultSchema;
import io.github.cowwoc.cat.common.optimizer.contract.SessionMode;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
import io.github.cowwoc.cat.common.optimizer.statistics.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.trial.SampledTrialInstance;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Checks category totals derived from validated representative batches.
 */
public final class DevelopmentEvidenceServiceTest
{
	/**
	 * Creates the test fixture.
	 */
	public DevelopmentEvidenceServiceTest()
	{
	}

	/**
	 * An unfinished batch contributes its outcomes to the total but leaves completed-batch order unchanged.
	 */
	@Test
	public void countsCompletedAndPartialBatchesInOrder()
	{
		DevelopmentEvidenceService.Snapshot snapshot = new DevelopmentEvidenceService.Snapshot(
			Campaign.create("campaign", ProspectiveCampaignState.profile()), List.of(
				batch("first", 1, EvaluatorLabel.COMPLIANT, EvaluatorLabel.COMPLIANT),
				batch("second", 2, EvaluatorLabel.COMPLIANT, null),
				batch("first", 3, EvaluatorLabel.NONCOMPLIANT, EvaluatorLabel.COMPLIANT),
				batch("first", 4, EvaluatorLabel.COMPLIANT, null)));

		Map<String, DevelopmentEvidenceService.CategorySeries> series = snapshot.categorySeries(2);
		requireThat(series.get("first"), "firstCategorySeries").isEqualTo(
			new DevelopmentEvidenceService.CategorySeries(new CategoryCounts(4, 5),
				List.of(new CategoryCounts(2, 2), new CategoryCounts(1, 2))));
		requireThat(series.get("second"), "secondCategorySeries").isEqualTo(
			new DevelopmentEvidenceService.CategorySeries(new CategoryCounts(1, 1), List.of()));
	}

	/**
	 * Builds one in-memory batch with semantic outcomes for non-null labels.
	 *
	 * @param categoryId category assigned to each slot
	 * @param batchId distinct decision and assignment identity
	 * @param labels semantic outcomes, or null for an unfinished slot
	 * @return representative batch consumed by the summary
	 */
	private static DevelopmentEvidenceService.Batch batch(String categoryId, int batchId,
		EvaluatorLabel... labels)
	{
		EvidenceRef decisionRef = ref("category-decisions", batchId);
		Map<Integer, DevelopmentEvidenceService.Slot> slots = new LinkedHashMap<>();
		for (int ordinal = 0; ordinal < labels.length; ++ordinal)
		{
			EvidenceRef assignmentRef = ref("trial-assignments", batchId * 10 + ordinal);
			String logicalTrialId = "trial-" + batchId + "-" + ordinal;
			TestDefinition definition = new TestDefinition("test", categoryId, List.of("requirement"),
				"always", List.of("task"), "expected", SessionMode.SINGLE_TURN, List.of(), List.of());
			SampledTrialInstance instance = new SampledTrialInstance("draw-" + logicalTrialId,
				EvidencePurpose.DEVELOPMENT, "generator", "contract", definition, List.of(), List.of("task"));
			TrialAssignment assignment = new TrialAssignment(TrialAssignment.SCHEMA_VERSION, "campaign",
				new LineageKey("lineage", "contract"), ref("measurement-epochs", 1), decisionRef,
				logicalTrialId, ordinal, "candidate", instance, Instant.EPOCH);
			EvaluatorLabel label = labels[ordinal];
			EvidenceRef outcomeRef = null;
			TrialOutcomeRecord outcome = null;
			if (label != null)
			{
				outcomeRef = ref("trial-outcomes", 1000 + batchId * 10 + ordinal);
				outcome = new TrialOutcomeRecord(TrialOutcomeRecord.SCHEMA_VERSION, "campaign",
					assignment.lineage(), assignmentRef, ref("trial-execution-records", 1),
					ref("evaluator-execution-records", 1), EvaluatorResultSchema.LABEL_AND_REASON_JSON_V1,
					new EvaluatorResponse(label, "Observed result"));
			}
			TrialStatus status = switch (label)
			{
				case null -> TrialStatus.ASSIGNED;
				case COMPLIANT -> TrialStatus.COMPLIANT;
				case NONCOMPLIANT -> TrialStatus.NONCOMPLIANT;
			};
			FailureKind failure = null;
			if (label == EvaluatorLabel.NONCOMPLIANT)
				failure = FailureKind.COMPLIANCE;
			TrialState trial = new TrialState(logicalTrialId, "lineage", "candidate",
				EvidencePurpose.DEVELOPMENT, status, failure, assignmentRef, outcomeRef);
			slots.put(ordinal, new DevelopmentEvidenceService.Slot(assignment, trial, outcome));
		}
		return new DevelopmentEvidenceService.Batch(decisionRef, categoryId, slots);
	}

	/**
	 * Creates a valid in-memory content address for one fixture identity.
	 *
	 * @param collection evidence collection
	 * @param id distinct numeric identity within that collection
	 * @return fixture evidence reference
	 */
	private static EvidenceRef ref(String collection, int id)
	{
		return new EvidenceRef(collection, "%064x".formatted(id));
	}
}
