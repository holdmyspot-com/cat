package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.Admission;
import com.holdmyspot.cat.common.optimizer.campaign.Allocation;
import com.holdmyspot.cat.common.optimizer.campaign.AllocationRule;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.DiagnosticRerun;
import com.holdmyspot.cat.common.optimizer.campaign.Failure;
import com.holdmyspot.cat.common.optimizer.campaign.Purpose;
import com.holdmyspot.cat.common.optimizer.campaign.Round;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.execution.WorktreeCheck;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * Verifies that the optimization worktree follows the working candidate when adopting a trial's outcome changes it.
 */
public final class AdoptionWorktreeTest
{
	/**
	 * Creates the test.
	 */
	public AdoptionWorktreeTest()
	{
	}

	/**
	 * Returns a campaign whose working candidate {@code candidate-2} replaced {@code candidate-1} after the final
	 * attempt of a sequential round. Candidate 1 holds 99 passes and a failure in its cell.
	 *
	 * @return the campaign
	 */
	private static Campaign promoted()
	{
		DrawnTestCase logs = Builder.targeted("safety", "logs");
		DiagnosticRerun failed = new DiagnosticRerun(Verdict.FAIL, "The defect reproduced");
		Campaign campaign = new Builder().confirmed().working(1).development("safety", 99, 1).
			failure(new Failure(logs, "Removed the directory", List.of(), null, List.of(failed, failed, failed), List.of(),
				Builder.FILES)).regression(logs, Verdict.FAIL).round(new Round(Map.of("safety", 1))).build();
		Sha256 snapshot = Builder.snapshot("candidate-2");
		campaign = Transitions.apply(campaign, new CampaignEvent.StartPromptAuthorAttempt("safety"));
		campaign = Transitions.apply(campaign, new CampaignEvent.SubmitPromptAuthorAttempt(1, "safety", snapshot,
			List.of()));
		for (int run = 0; run < 2; ++run)
		{
			campaign = Transitions.apply(campaign, new CampaignEvent.Assign(new Admission.Assign(Purpose.PROMOTION, 1,
				snapshot, "safety", logs, new Allocation(AllocationRule.TARGETED, campaign.evidenceRevision(),
					DriverHarness.START))));
			TrialKey key = campaign.trials().getLast().key();
			campaign = Transitions.apply(campaign, new CampaignEvent.Start(key));
			campaign = Transitions.apply(campaign, new CampaignEvent.Complete(key, Verdict.PASS, "Fixed", List.of(),
				null, campaign.identity(), null, List.of(), Builder.FILES));
		}
		return Transitions.apply(campaign, new CampaignEvent.AdvanceMinimality(snapshot));
	}

	/**
	 * Starts a representative development trial of the working candidate.
	 *
	 * @param campaign the campaign
	 * @return the campaign with the trial running
	 */
	private static Campaign developmentTrialRunning(Campaign campaign)
	{
		Campaign assigned = Transitions.apply(campaign, new CampaignEvent.Assign(new Admission.Assign(
			Purpose.DEVELOPMENT, campaign.development().series(), campaign.development().working().snapshot(), "safety",
			null, new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, campaign.evidenceRevision(),
				DriverHarness.START))));
		return Transitions.apply(assigned, new CampaignEvent.Start(assigned.trials().getLast().key()));
	}

	/**
	 * A failing outcome withdraws the promoted candidate, because its interval falls below its predecessor's. The
	 * worktree then holds the predecessor, the working candidate that the snapshot records.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void withdrawalRestoresWorktree() throws IOException
	{
		// Requirement: R-D15
		// Requirement: R-W7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Campaign running = developmentTrialRunning(promoted());
			requireThat(running.development().working().number(), "promoted").isEqualTo(2);
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("adoption")), running);
			FileInventory promotedWorktree = harness.overlays().require(Builder.snapshot("candidate-2")).snapshot();
			requireThat(harness.workspace().scanWorktree(), "initialWorktree").isEqualTo(promotedWorktree);
			CampaignEvent.Complete failure = new CampaignEvent.Complete(running.trials().getLast().key(), Verdict.FAIL,
				"Poor representative result", List.of(), null, running.identity(), null, List.of(), Builder.FILES);

			Campaign adopted = WorktreeCheck.adopt(harness.context(), running, failure);

			requireThat(adopted.development().working().number(), "working").isEqualTo(1);
			requireThat(adopted.development().reverts().size(), "reverts").isEqualTo(1);
			requireThat(harness.workspace().scanWorktree(), "worktree").
				isEqualTo(harness.overlays().require(Builder.snapshot("candidate-1")).snapshot());
			requireThat(adopted.pending(), "pending").isNull();
		}
	}
}
