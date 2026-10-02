package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Development;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;

/**
 * The contract and outcomes that an author session may see: development failures and counts for the working
 * candidate, and certification outcomes from attempts that have ended. It excludes trial instances and outcomes
 * from certification attempts in progress.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param category the identifier of the cell (one category under one trial profile) that the author attempt targets
 * @param contract the confirmed contract
 * @param developmentFailures the working candidate's development failures, in adoption order
 * @param developmentCounts the working candidate's development outcomes per cell
 * @param endedAttempts the certification attempts that have ended, in index order
 * @param authorizedTargets the absolute paths of the external files whose content the user authorized author attempts
 *   to edit
 * @param links the resolved target of each symbolic link recorded when the campaign copied the base project,
 *   keyed by the link's path; a link whose links form a cycle is absent
 */
public record AuthorBrief(String category, Contract contract, List<Failure> developmentFailures,
	Map<String, CategoryCounts> developmentCounts, List<EndedAttempt> endedAttempts, Set<Path> authorizedTargets,
	Map<Path, Path> links)
{
	/**
	 * Creates a brief.
	 *
	 * @param category the identifier of the cell (one category under one trial profile) that the author attempt targets
	 * @param contract the confirmed contract
	 * @param developmentFailures the working candidate's development failures, in adoption order
	 * @param developmentCounts the working candidate's development outcomes per cell
	 * @param endedAttempts the certification attempts that have ended, in index order
	 * @param authorizedTargets the absolute paths of the external files whose content the user authorized author
	 *   attempts to edit
	 * @param links the resolved target of each symbolic link recorded when the campaign copied the base project,
	 *   keyed by the link's path; a link whose links form a cycle is absent
	 * @throws NullPointerException if any of the arguments are null or contain null
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
		authorizedTargets = Set.copyOf(authorizedTargets);
		links = Map.copyOf(links);
	}

	/**
	 * Returns every supplied failure's citation identifier, preserving its occurrence in this brief.
	 *
	 * @return the unique identifiers
	 */
	public Set<String> failureIds()
	{
		Set<String> identifiers = new HashSet<>();
		for (int index = 0; index < developmentFailures.size(); ++index)
			identifiers.add(developmentId(index));
		for (EndedAttempt attempt : endedAttempts)
		{
			for (int index = 0; index < attempt.failures().size(); ++index)
				identifiers.add(endedId(attempt.index(), index));
		}
		return Set.copyOf(identifiers);
	}

	/**
	 * Identifies one supplied development failure.
	 *
	 * @param occurrence the failure's list index, from zero
	 * @return its scoped citation identifier
	 * @throws IndexOutOfBoundsException if the occurrence is absent
	 */
	public String developmentId(int occurrence)
	{
		return failureId("development", occurrence, developmentFailures.get(occurrence));
	}

	/**
	 * Identifies one supplied failure of an ended attempt.
	 *
	 * @param attemptIndex the ended attempt's index
	 * @param occurrence the failure's list index, from zero
	 * @return its scoped citation identifier
	 * @throws IllegalArgumentException if the ended attempt is absent
	 * @throws IndexOutOfBoundsException if the occurrence is absent
	 */
	public String endedId(int attemptIndex, int occurrence)
	{
		for (EndedAttempt attempt : endedAttempts)
		{
			if (attempt.index() == attemptIndex)
				return failureId("ended:" + attemptIndex, occurrence, attempt.failures().get(occurrence));
		}
		throw new IllegalArgumentException("No supplied ended attempt " + attemptIndex);
	}

	/**
	 * Frames the brief location and instance identity so repeated instances remain distinct supplied failures.
	 *
	 * @param origin the development or ended-attempt location
	 * @param occurrence the list index
	 * @param failure the supplied failure
	 * @return the scoped digest
	 */
	private static String failureId(String origin, int occurrence, Failure failure)
	{
		return Sha256.of((origin + "\n" + occurrence + "\n" + AuthorManifest.instanceId(failure.instance())).
			getBytes(StandardCharsets.UTF_8)).hex();
	}

	/**
	 * Returns the brief of an author attempt.
	 *
	 * @param campaign a campaign with a confirmed contract
	 * @param category the identifier of the cell (one category under one trial profile) that the author attempt targets
	 * @param fork the files recorded at the fork, whose symbolic links the author is told about
	 * @return the brief
	 */
	static AuthorBrief of(Campaign campaign, String category, FileInventory fork)
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
		Map<Path, Path> links = new HashMap<>();
		for (Map.Entry<Path, FileEntry> entry : fork.entries().entrySet())
		{
			if (entry.getValue() instanceof FileEntry.SymbolicLink)
				fork.resolve(entry.getKey()).ifPresent(target -> links.put(entry.getKey(), target));
		}
		return new AuthorBrief(category, campaign.contract(), development.failures(), development.counts(), ended,
			campaign.authorizedTargets(), links);
	}

	/**
	 * The certification outcomes of an attempt that has ended.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param index the attempt's certification index
	 * @param outcome the attempt's outcome
	 * @param counts the attempt's adopted certification outcomes per cell
	 * @param failures the attempt's certification failures, in adoption order
	 */
	public record EndedAttempt(int index, Outcome outcome, Map<String, CategoryCounts> counts,
		List<Failure> failures)
	{
		/**
		 * Creates the outcomes of an ended attempt.
		 *
		 * @param index the attempt's certification index
		 * @param outcome the attempt's outcome
		 * @param counts the attempt's adopted certification outcomes per cell
		 * @param failures the attempt's certification failures, in adoption order
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
