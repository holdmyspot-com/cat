package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.AttemptResult;
import io.github.cowwoc.cat.common.optimizer.campaign.AttemptSelection;
import io.github.cowwoc.cat.common.optimizer.campaign.AuthorAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.Promotion;
import io.github.cowwoc.cat.common.optimizer.campaign.Round;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One reserved author's original promotion progress, retained after winner minimality.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param round the round's first reserved attempt number
 * @param number the author's launch-order attempt number
 * @param working the starting working snapshot
 * @param cell the representative addressed cell
 * @param snapshot the original author snapshot, or {@code null} before the author submits
 * @param passingRuns the attempt's own passing promotion runs, excluding working reference runs
 * @param totalRuns the attempt's own planned promotion runs, excluding working reference runs
 * @param accepted whether promotion accepted the attempt, or {@code null} while pending
 * @param winner whether this attempt was selected for minimality
 */
public record AttemptProgress(int round, int number, Sha256 working, String cell, Sha256 snapshot,
	long passingRuns, long totalRuns, Boolean accepted, boolean winner)
{
	/**
	 * Creates an attempt's status.
	 *
	 * @param round the round identity
	 * @param number the attempt identity
	 * @param working the starting snapshot
	 * @param cell the addressed cell
	 * @param snapshot the original snapshot, or {@code null} before submission
	 * @param passingRuns passing promotion runs
	 * @param totalRuns planned promotion runs
	 * @param accepted the promotion decision, or {@code null} while pending
	 * @param winner whether this attempt won
	 * @throws NullPointerException if working or cell is null
	 * @throws IllegalArgumentException if an identity or count is invalid, or a winner is not accepted
	 */
	public AttemptProgress
	{
		requireThat(round, "round").isPositive();
		requireThat(number, "number").isGreaterThanOrEqualTo(round);
		requireThat(working, "working").isNotNull();
		requireThat(cell, "cell").isNotBlank();
		requireThat(passingRuns, "passingRuns").isNotNegative();
		requireThat(totalRuns, "totalRuns").isGreaterThanOrEqualTo(passingRuns);
		if (winner)
			requireThat(Boolean.TRUE.equals(accepted), "accepted").isTrue();
	}

	/**
	 * Collects retained results and unfinished reservations in launch order. Retained results take precedence over
	 * reduction runs so minimality cannot replace an original attempt's promotion totals.
	 *
	 * @param campaign the campaign
	 * @return the attempt statuses
	 * @throws NullPointerException if campaign is null
	 */
	public static List<AttemptProgress> of(Campaign campaign)
	{
		requireThat(campaign, "campaign").isNotNull();
		Map<Integer, AttemptProgress> progress = new TreeMap<>();
		for (AttemptResult result : campaign.optimization().attempts())
		{
			progress.put(result.number(), new AttemptProgress(result.round(), result.number(), result.working(),
				result.category(), result.snapshot(), result.passingRuns(), result.totalRuns(), result.accepted(),
				result.winner()));
		}
		Round round = campaign.development().round();
		if (round != null)
		{
			for (Map.Entry<Integer, String> entry : round.reservations().entrySet())
			{
				if (progress.containsKey(entry.getKey()))
					continue;
				AuthorAttempt attempt = findAttempt(campaign, entry.getKey());
				Sha256 working = campaign.development().working().snapshot();
				if (attempt == null)
				{
					progress.put(entry.getKey(), new AttemptProgress(round.number(), entry.getKey(), working,
						entry.getValue(), null, 0, 0, null, false));
					continue;
				}
				Boolean accepted;
				if (attempt.snapshot().equals(working))
					accepted = false;
				else
				{
					accepted = switch (Promotion.decide(attempt, campaign.lineage().regressionSet()))
					{
						case PENDING -> null;
						case PROMOTE -> true;
						case REJECT -> false;
					};
				}
				progress.put(entry.getKey(), new AttemptProgress(round.number(), entry.getKey(), working,
					entry.getValue(), attempt.snapshot(), AttemptSelection.passingRuns(attempt.runs()),
					AttemptSelection.plannedRuns(attempt.runs()), accepted, false));
			}
		}
		return List.copyOf(progress.values());
	}

	/**
	 * Finds the current sequential validation or the received weak contender.
	 *
	 * @param campaign the campaign
	 * @param number the reserved identity
	 * @return the validation attempt, or {@code null} while authoring
	 */
	private static AuthorAttempt findAttempt(Campaign campaign, int number)
	{
		AuthorAttempt selected = campaign.development().author();
		if (selected != null && selected.number() == number)
			return selected;
		return campaign.development().round().validationAttempts().stream().
			filter(attempt -> attempt.number() == number).findFirst().orElse(null);
	}
}
