package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.contract.ConfirmedContract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractTestFixtures;
import io.github.cowwoc.cat.common.optimizer.statistics.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.trial.TrialSampler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Tests the choice producer and assignment consumer without replaying already validated outcome histories.
 */
public final class DevelopmentChoiceHandoffTest
{
	private static final String DIGEST = "a".repeat(64);
	private static final LineageKey LINEAGE = new LineageKey("lineage", DIGEST);
	private static final EvidenceRef EPOCH = new EvidenceRef("measurement-epochs", DIGEST);

	/** Creates the test instance. */
	public DevelopmentChoiceHandoffTest()
	{
	}

	/**
	 * Resumed coverage produces batch four for the second category and binds one draw to that exact choice.
	 *
	 * @throws IOException if the contract fixture, readiness policy, or retained handoff cannot be evaluated
	 */
	@Test
	public void bindsDrawToResumedCoverage() throws IOException
	{
		ConfirmedContract contract = ContractTestFixtures.createInMemoryContract(DIGEST);
		DevelopmentBatchSelector.Progress position = new DevelopmentBatchSelector.Progress(
			DevelopmentBatchSelector.Turn.COVERAGE, 1);
		DevelopmentBatchSelector.Selection selected = DevelopmentCategoryDecisionService.select(
			contract.developmentReadiness(), contract.developmentAllocationPolicy(), List.of("first", "second"),
			Map.of("first", new DevelopmentEvidenceService.CategorySeries(new CategoryCounts(144, 150),
				List.of(new CategoryCounts(45, 50), new CategoryCounts(49, 50), new CategoryCounts(50, 50))),
				"second", new DevelopmentEvidenceService.CategorySeries(new CategoryCounts(50, 50),
					List.of(new CategoryCounts(50, 50)))), position, Set.of());
		EvidenceRef source = new EvidenceRef("development-evidence-revisions", "b".repeat(64));
		DevelopmentCategoryDecision expected = DevelopmentCategoryDecisionService.createDecision(campaign(), LINEAGE,
			contract, source, 4, position, selected, List.of());
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("development-choice-handoff-"));
			CampaignStore store = new CampaignStore(root);
			CampaignLocator locator = new CampaignLocator(root);
			DevelopmentCategoryDecisionService choices = new DevelopmentCategoryDecisionService(locator, store);
			EvidenceRef choiceRef = choices.retainDecision(expected);
			byte[] retained = store.readImmutableJson(choiceRef);
			DevelopmentCategoryDecision choice = DevelopmentCategoryDecisionService.decodeRecord(retained);
			byte[] noncanonical = Arrays.copyOf(retained, retained.length + 1);
			noncanonical[retained.length] = '\n';
			IOException rejected = Assert.expectThrows(IOException.class, () ->
				DevelopmentCategoryDecisionService.decodeRecord(noncanonical));
			requireThat(rejected.getMessage(), "noncanonicalRecord").isEqualTo(
				"Development category choice is noncanonical");
			TrialAssignmentService assignments = new TrialAssignmentService(locator, store);
			requireThat(choice.batchNumber(), "resumedBatchNumber").isEqualTo(4);
			requireThat(choice.position(), "resumedCoveragePosition").isEqualTo(position);
			requireThat(choice.nextPosition(), "afterResumedCoverage").isEqualTo(
				new DevelopmentBatchSelector.Progress(DevelopmentBatchSelector.Turn.ADAPTIVE, 0));
			requireThat(choice.sourceRevisionRef(), "retainedSource").isEqualTo(source);
			requireThat(choice.categoryId(), "resumedCoverageCategory").isEqualTo("second");

			Instant assignedAt = Instant.parse("2026-01-01T00:00:00Z");
			TrialAssignment sampled = TrialAssignmentService.createPostCoverageAssignment(choice, choiceRef, 0, "trial",
				new TrialSampler(contract), Clock.fixed(assignedAt, ZoneOffset.UTC));
			EvidenceRef assignmentRef = assignments.retainAssignment(sampled);
			TrialAssignment assignment = store.readTrialAssignment(assignmentRef);
			requireThat(assignment.categoryDecisionRef(), "drawChoice").isEqualTo(choiceRef);
			requireThat(assignment.instance().definition().categoryId(), "drawCategory").isEqualTo("second");
			requireThat(assignment.lineage(), "drawLineage").isEqualTo(LINEAGE);
			requireThat(assignment.candidateId(), "drawCandidate").isEqualTo(DIGEST);
			requireThat(assignment.measurementEpochRef(), "drawEpoch").isEqualTo(EPOCH);
			requireThat(assignment.assignedAt(), "assignmentTime").isEqualTo(assignedAt);
		}
	}

	/**
	 * Supplies only the campaign metadata consumed by the choice producer.
	 *
	 * @return developing candidate and its measurement identity
	 */
	private static Campaign campaign()
	{
		Campaign initial = Campaign.create("campaign", ProspectiveCampaignState.profile());
		return new Campaign(initial.schemaVersion(), initial.campaignId(), CampaignStatus.DEVELOPING,
			LINEAGE.lineageId(), DIGEST, null, null, 0, null,
			List.of(new LineageSummary(LINEAGE.lineageId(), LINEAGE.contractId(), 1, List.of())), List.of(), List.of(),
			initial.optimizationProfile(), List.of(), initial.scheduler(), initial.preparationScope(), null,
			new MeasurementState(EPOCH, Optional.empty(), Optional.empty()), List.of(), null);
	}
}
