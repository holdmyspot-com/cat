package com.holdmyspot.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.Choice;
import com.holdmyspot.cat.common.optimizer.campaign.FailureKind;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.campaign.PendingDecision;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorKind;
import com.holdmyspot.cat.common.optimizer.campaign.Purpose;
import com.holdmyspot.cat.common.optimizer.campaign.Scheduler;
import com.holdmyspot.cat.common.optimizer.campaign.SlowStart;
import com.holdmyspot.cat.common.optimizer.campaign.SoundTest;
import com.holdmyspot.cat.common.optimizer.campaign.TestRevision;
import com.holdmyspot.cat.common.optimizer.campaign.TestRevisionRequest;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests how a campaign requests, applies and answers test revisions.
 */
public final class TestRevisionTest
{
	private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
	private static final Sha256 REVISED = Builder.snapshot("revised tests");

	/**
	 * Creates the test.
	 */
	public TestRevisionTest()
	{
	}

	/**
	 * Returns a request to revise the {@code safety} test for a test defect.
	 *
	 * @return the request
	 */
	private static TestRevisionRequest safetyDefect()
	{
		return new TestRevisionRequest("safety", TestRevisionRequest.Trigger.TEST_DEFECT,
			List.of(new PromptAuthorKind("named the test", List.of("f1"), FailureKind.TEST_DEFECT, List.of(),
				"The turn tells the agent that it is tested.")), Map.of());
	}

	/**
	 * Verifies that a requested test revision holds new trials until it is answered.
	 */
	@Test
	public void requestHoldsAdmissions()
	{
		// Requirement: R-D10
		Campaign campaign = new Builder().confirmed().development("safety", 10, 1).build();
		requireThat(Scheduler.plan(campaign, NOW, 8).isEmpty(), "planBefore.isEmpty()").isFalse();

		campaign = Transitions.apply(campaign, new CampaignEvent.RequestTestRevision(safetyDefect()));

		requireThat(campaign.testRevisions().requests(), "requests").isEqualTo(List.of(safetyDefect()));
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEmpty();
	}

	/**
	 * Verifies that a test revision ends a running attempt with its own outcome, and that the confirmation that applies
	 * it starts a new lineage, keeps the certification index and records the revision.
	 */
	@Test
	public void revisionStopsAttemptAndKeepsIndex()
	{
		// Requirement: R-L3
		Campaign campaign = new Builder().confirmed().working(1).certificationAttempt(1).campaign().build();
		campaign = Transitions.apply(campaign, new CampaignEvent.RequestTestRevision(safetyDefect()));
		TestRevision revision = new TestRevision(1, "safety", "before", "after", "The turn no longer names the test.");

		campaign = Transitions.apply(campaign, new CampaignEvent.ReviseTests(List.of(revision)));
		requireThat(campaign.attempt(1).outcome(), "outcome").isEqualTo(Outcome.STOPPED_FOR_TEST_REVISION);
		requireThat(campaign.revisionRequested(), "revisionRequested").isTrue();
		campaign = Transitions.apply(campaign, new CampaignEvent.Confirm(campaign.contract(), REVISED, 7L));

		requireThat(campaign.lineages().size(), "lineages").isEqualTo(2);
		requireThat(campaign.nextCertificationIndex(), "nextCertificationIndex").isEqualTo(2);
		requireThat(campaign.testRevisions().requests(), "requests").isEmpty();
		requireThat(campaign.testRevisions().revisions(), "revisions").isEqualTo(List.of(revision));
	}

	/**
	 * Verifies that a report that the test is sound answers its request and is remembered for the lineage.
	 */
	@Test
	public void soundReportAnswersRequest()
	{
		// Requirement: R-D10
		Campaign campaign = new Builder().confirmed().development("safety", 10, 1).build();
		campaign = Transitions.apply(campaign, new CampaignEvent.RequestTestRevision(safetyDefect()));

		campaign = Transitions.apply(campaign, new CampaignEvent.ReportTestSound("safety", "The turn is organic."));

		requireThat(campaign.testRevisions().requests(), "requests").isEmpty();
		requireThat(campaign.testRevisions().sound(), "sound").
			isEqualTo(List.of(new SoundTest(1, "safety", "The turn is organic.")));
		requireThat(campaign.testRevisions().isSound(1, "safety"), "isSound").isTrue();
		requireThat(Scheduler.plan(campaign, NOW, 8).isEmpty(), "plan.isEmpty()").isFalse();
	}

	/**
	 * Verifies that a test revision that cannot proceed within the confirmed requirements pauses the campaign and
	 * offers its proposed requirement change, when it has one, and stopping.
	 */
	@Test
	public void blockedRevisionPauses()
	{
		// Requirement: R-D10
		Campaign campaign = new Builder().confirmed().development("safety", 10, 1).build();
		campaign = Transitions.apply(campaign, new CampaignEvent.RequestTestRevision(safetyDefect()));

		Campaign proposed = Transitions.apply(campaign, new CampaignEvent.BlockTestRevision("safety",
			PendingDecision.TestRevisionBlocked.Reason.REQUIREMENT_CHANGE, "r2 names no observable outcome.",
			"Require a question before any deletion."));
		requireThat(proposed.pending(), "pending").isEqualTo(new PendingDecision.TestRevisionBlocked("safety",
			PendingDecision.TestRevisionBlocked.Reason.REQUIREMENT_CHANGE, "r2 names no observable outcome.",
			"Require a question before any deletion."));
		requireThat(proposed.testRevisions().requests(), "requests").isEmpty();
		requireThat(proposed.choices(), "choices").isEqualTo(List.of(Choice.PROPOSED_CHANGE, Choice.STOP));
		requireThat(Scheduler.plan(proposed, NOW, 8), "plan").isEmpty();

		Campaign unprovable = Transitions.apply(campaign, new CampaignEvent.BlockTestRevision("safety",
			PendingDecision.TestRevisionBlocked.Reason.UNPROVABLE, "No organic turn can show the question.", ""));
		requireThat(unprovable.choices(), "unprovableChoices").isEqualTo(List.of(Choice.STOP));
	}

	/**
	 * Returns a campaign whose {@code formatting} test reaches its review checkpoint with its next outcome.
	 *
	 * @return the campaign
	 */
	private static Campaign beforeCheckpoint()
	{
		return new Builder().confirmed().development("formatting", 197, List.of(0, 0, 0, 2)).
			development("safety", 150, 0).slowStart(new SlowStart(4, 0)).build();
	}

	/**
	 * Verifies that a review checkpoint first requests a test revision of a test that no revision examined, and asks
	 * the user only after the revision reports the test sound.
	 */
	@Test
	public void checkpointRevisesUnexaminedTestFirst()
	{
		// Requirement: R-D5
		Campaign campaign = TransitionSteps.adopt(beforeCheckpoint(), Purpose.DEVELOPMENT, "formatting",
			Verdict.PASS);
		requireThat(campaign.pending(), "pending").isNull();
		requireThat(campaign.testRevisions().requests(), "requests").isEqualTo(List.of(
			new TestRevisionRequest("formatting", TestRevisionRequest.Trigger.REVIEW_CHECKPOINT, List.of(), Map.of())));

		campaign = Transitions.apply(campaign, new CampaignEvent.ReportTestSound("formatting", "It is minimal."));

		requireThat(campaign.pending(), "pendingAfterSound").isEqualTo(new PendingDecision.CheckpointReached(
			"formatting", 200));
	}

	/**
	 * Verifies that the new lineage keeps the sound reports of the tests that the revision left unchanged and drops
	 * those of the revised tests.
	 */
	@Test
	public void keepsSoundReportsOfUnchangedTests()
	{
		// Requirement: R-D10
		Campaign campaign = new Builder().confirmed().development("safety", 10, 1).build();
		campaign = Transitions.apply(campaign, new CampaignEvent.ReportTestSound("safety", "It is organic."));
		campaign = Transitions.apply(campaign, new CampaignEvent.ReportTestSound("formatting", "It is minimal."));
		campaign = Transitions.apply(campaign, new CampaignEvent.ReviseTests(List.of(
			new TestRevision(1, "formatting", "before", "after", "Shorter notes suffice."))));
		campaign = Transitions.apply(campaign, new CampaignEvent.Confirm(campaign.contract(), REVISED, 7L));

		requireThat(campaign.testRevisions().isSound(2, "safety"), "safetySound").isTrue();
		requireThat(campaign.testRevisions().isSound(2, "formatting"), "formattingSound").isFalse();
	}
}
