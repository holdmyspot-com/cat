package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Development;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What an author session may see: the contract, the working candidate's development instances and outcomes, and the
 * formal outcomes of certification attempts that have ended. It never holds an instance of an attempt in progress.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param category the category that the author attempt targets
 * @param contract the confirmed contract
 * @param developmentFailures the working candidate's development failures, in adoption order
 * @param developmentCounts the working candidate's development outcomes per category
 * @param endedAttempts the certification attempts that have ended, in index order
 */
public record AuthorBrief(String category, Contract contract, List<Failure> developmentFailures,
	Map<String, CategoryCounts> developmentCounts, List<EndedAttempt> endedAttempts)
{
	/**
	 * Creates a brief.
	 *
	 * @param category the category that the author attempt targets
	 * @param contract the confirmed contract
	 * @param developmentFailures the working candidate's development failures, in adoption order
	 * @param developmentCounts the working candidate's development outcomes per category
	 * @param endedAttempts the certification attempts that have ended, in index order
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code category} is blank
	 */
	public AuthorBrief
	{
		requireThat(category, "category").isNotBlank();
		requireThat(contract, "contract").isNotNull();
		requireThat(developmentFailures, "developmentFailures").isNotNull();
		requireThat(developmentCounts, "developmentCounts").isNotNull();
		requireThat(endedAttempts, "endedAttempts").isNotNull();
		developmentFailures = List.copyOf(developmentFailures);
		developmentCounts = Map.copyOf(developmentCounts);
		endedAttempts = List.copyOf(endedAttempts);
	}

	/**
	 * Returns the brief of an author attempt.
	 *
	 * @param campaign a campaign with a confirmed contract
	 * @param category the category that the author attempt targets
	 * @return the brief
	 */
	static AuthorBrief of(Campaign campaign, String category)
	{
		Development development = campaign.development();
		List<EndedAttempt> ended = new ArrayList<>();
		for (CertificationAttempt attempt : campaign.attempts())
		{
			if (!attempt.isRunning())
			{
				ended.add(new EndedAttempt(attempt.index(), attempt.outcome(), attempt.counts(),
					attempt.failures()));
			}
		}
		return new AuthorBrief(category, campaign.contract(), development.failures(), development.counts(), ended);
	}

	/**
	 * The formal outcomes of a certification attempt that has ended.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param index the attempt's certification index
	 * @param outcome the attempt's outcome
	 * @param counts the attempt's adopted formal outcomes per category
	 * @param failures the attempt's formal failures, in adoption order
	 */
	public record EndedAttempt(int index, Outcome outcome, Map<String, CategoryCounts> counts,
		List<Failure> failures)
	{
		/**
		 * Creates the outcomes of an ended attempt.
		 *
		 * @param index the attempt's certification index
		 * @param outcome the attempt's outcome
		 * @param counts the attempt's adopted formal outcomes per category
		 * @param failures the attempt's formal failures, in adoption order
		 * @throws NullPointerException if any of the arguments are null
		 * @throws IllegalArgumentException if {@code index} is not positive
		 */
		public EndedAttempt
		{
			requireThat(index, "index").isPositive();
			requireThat(outcome, "outcome").isNotNull();
			requireThat(counts, "counts").isNotNull();
			requireThat(failures, "failures").isNotNull();
			counts = Map.copyOf(counts);
			failures = List.copyOf(failures);
		}
	}
}
