package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.AuthorDiagnosis;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.OptimizationEvidence;
import io.github.cowwoc.cat.common.optimizer.campaign.PromptSize;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialInputs;
import io.github.cowwoc.cat.common.optimizer.contract.Cell;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Snapshot size comparisons, trial input medians, and retained author diagnoses.
 *
 * @param workingSnapshot the working snapshot
 * @param incumbentSnapshot the incumbent snapshot
 * @param working the working prompt sizes
 * @param incumbent the incumbent prompt sizes
 * @param inputs per-cell trial input comparisons
 * @param authors retained author diagnoses, including attempts that produced no candidate
 * @param removed original hunk identifiers removed by minimality
 * @param attempts original promotion results and unfinished author reservations
 */
public record OptimizationReport(Sha256 workingSnapshot, Sha256 incumbentSnapshot, PromptSize working,
	PromptSize incumbent, List<InputComparison> inputs, List<AuthorDiagnosis> authors, List<String> removed,
	List<AttemptProgress> attempts)
{
	/**
	 * A cell's descriptive medians of completed trial sessions that reported usage.
	 *
	 * @param cell the cell
	 * @param workingMedian the working median, or {@code null} if unavailable
	 * @param incumbentMedian the incumbent median, or {@code null} if unavailable
	 * @param workingOutcomes completed working outcomes
	 * @param incumbentOutcomes completed incumbent outcomes
	 * @param workingSessions working sessions reporting usage
	 * @param incumbentSessions incumbent sessions reporting usage
	 * @param unavailable the reason a comparison is unavailable, or {@code null}
	 */
	public record InputComparison(String cell, Long workingMedian, Long incumbentMedian, long workingOutcomes,
		long incumbentOutcomes, int workingSessions, int incumbentSessions, String unavailable)
	{
		/**
		 * Creates the comparison.
		 *
		 * @param cell the cell
		 * @param workingMedian the working median, or {@code null}
		 * @param incumbentMedian the incumbent median, or {@code null}
		 * @param workingOutcomes completed working outcomes
		 * @param incumbentOutcomes completed incumbent outcomes
		 * @param workingSessions sessions reporting working usage
		 * @param incumbentSessions sessions reporting incumbent usage
		 * @param unavailable the reason a comparison is unavailable, or {@code null}
		 * @throws NullPointerException if cell is null
		 * @throws IllegalArgumentException if a count is negative or a cell is blank
		 */
		public InputComparison
		{
			requireThat(cell, "cell").isNotBlank();
			requireThat(workingOutcomes, "workingOutcomes").isNotNegative();
			requireThat(incumbentOutcomes, "incumbentOutcomes").isNotNegative();
			requireThat(workingSessions, "workingSessions").isNotNegative();
			requireThat(incumbentSessions, "incumbentSessions").isNotNegative();
			requireThat((long) workingSessions, "workingSessions").isLessThanOrEqualTo(workingOutcomes);
			requireThat((long) incumbentSessions, "incumbentSessions").isLessThanOrEqualTo(incumbentOutcomes);
			if (workingMedian == null || incumbentMedian == null)
				requireThat(unavailable, "unavailable").isNotBlank();
			else
				requireThat(unavailable, "unavailable").isNull();
			if (workingMedian != null)
				requireThat(workingMedian.longValue(), "workingMedian").isNotNegative();
			if (incumbentMedian != null)
				requireThat(incumbentMedian.longValue(), "incumbentMedian").isNotNegative();
		}
	}

	/**
	 * Creates the report.
	 *
	 * @param workingSnapshot the working snapshot
	 * @param incumbentSnapshot the incumbent snapshot
	 * @param working the working sizes
	 * @param incumbent the incumbent sizes
	 * @param inputs trial inputs by cell
	 * @param authors author diagnoses
	 * @param removed removed hunk identifiers
	 * @param attempts original attempt results and progress
	 * @throws NullPointerException if any argument or collection element is null
	 */
	public OptimizationReport
	{
		requireThat(workingSnapshot, "workingSnapshot").isNotNull();
		requireThat(incumbentSnapshot, "incumbentSnapshot").isNotNull();
		requireThat(working, "working").isNotNull();
		requireThat(incumbent, "incumbent").isNotNull();
		inputs = List.copyOf(inputs);
		authors = List.copyOf(authors);
		removed = List.copyOf(removed);
		attempts = List.copyOf(attempts);
	}

	/**
	 * Builds descriptive comparisons from durable snapshot and completed-trial measurements.
	 *
	 * @param campaign the campaign
	 * @return comparisons and diagnoses
	 * @throws NullPointerException if campaign is null
	 */
	public static OptimizationReport of(Campaign campaign)
	{
		requireThat(campaign, "campaign").isNotNull();
		OptimizationEvidence evidence = campaign.optimization();
		Sha256 working = campaign.development().working().snapshot();
		Sha256 incumbent = campaign.incumbent().snapshot();
		List<InputComparison> comparisons = new ArrayList<>();
		if (campaign.isConfirmed())
		{
			for (Cell cell : campaign.contract().cells())
			{
				TrialInputs workingInputs = evidence.trialInputs().getOrDefault(working, Map.of()).
					getOrDefault(cell.id(), TrialInputs.EMPTY);
				TrialInputs incumbentInputs = evidence.trialInputs().getOrDefault(incumbent, Map.of()).
					getOrDefault(cell.id(), TrialInputs.EMPTY);
				comparisons.add(new InputComparison(cell.id(), median(workingInputs), median(incumbentInputs),
					workingInputs.outcomes(), incumbentInputs.outcomes(), workingInputs.tokens().size(),
					incumbentInputs.tokens().size(), unavailable(workingInputs, incumbentInputs)));
			}
		}
		return new OptimizationReport(working, incumbent, evidence.promptSizes().getOrDefault(working, PromptSize.UNKNOWN),
			evidence.promptSizes().getOrDefault(incumbent, PromptSize.UNKNOWN), comparisons, evidence.authors(),
			evidence.removed(), AttemptProgress.of(campaign));
	}

	/**
	 * Returns the nearest-rank median when input usage exists.
	 *
	 * @param inputs the observations
	 * @return the median, or {@code null}
	 */
	private static Long median(TrialInputs inputs)
	{
		if (inputs.tokens().isEmpty())
			return null;
		return TokenStatistics.median(inputs.tokens());
	}

	/**
	 * Explains missing outcomes or missing harness usage without forecasting a value.
	 *
	 * @param working the working observations
	 * @param incumbent the incumbent observations
	 * @return the reason, or {@code null} when both medians exist
	 */
	private static String unavailable(TrialInputs working, TrialInputs incumbent)
	{
		if (working.outcomes() == 0 || incumbent.outcomes() == 0)
			return "Both snapshots need completed trial outcomes in this cell before their input tokens can be compared.";
		if (working.tokens().isEmpty() || incumbent.tokens().isEmpty())
			return "The harness reported no trial input-token usage for one or both snapshots in this cell.";
		return null;
	}
}
