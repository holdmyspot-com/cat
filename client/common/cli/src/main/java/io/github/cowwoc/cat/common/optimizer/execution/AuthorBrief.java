package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.AuthorApproach;
import io.github.cowwoc.cat.common.optimizer.campaign.CellCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Development;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureSummary;
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
 * candidate, and certification outcomes from attempts that have ended. Certification instances are held out, so an
 * ended attempt's failures appear only as their cell, test and failed requirements. It excludes outcomes from
 * certification attempts in progress.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param cell the identifier of the cell (one category under one trial profile) that the author attempt targets
 * @param approach the editing approach selected by the reservation's position within its cell's round
 * @param contract the confirmed contract
 * @param developmentFailures the working candidate's development failures, in adoption order
 * @param developmentCounts the working candidate's development outcomes per cell
 * @param endedAttempts the certification attempts that have ended, in index order
 * @param links the resolved target of each symbolic link recorded when the campaign copied the base project,
 *   keyed by the link's path; a link whose links form a cycle is absent
 * @param campaignEdits the unified diff of prompt files from confirmation to the working candidate
 */
public record AuthorBrief(String cell, AuthorApproach approach, Contract contract, List<Failure> developmentFailures,
	Map<String, CellCounts> developmentCounts, List<EndedAttempt> endedAttempts, Map<Path, Path> links,
	String campaignEdits)
{
	/**
	 * Creates a brief.
	 *
	 * @param cell the identifier of the cell (one category under one trial profile) that the author attempt targets
	 * @param approach the editing approach assigned to the reserved attempt
	 * @param contract the confirmed contract
	 * @param developmentFailures the working candidate's development failures, in adoption order
	 * @param developmentCounts the working candidate's development outcomes per cell
	 * @param endedAttempts the certification attempts that have ended, in index order
	 * @param links the resolved target of each symbolic link recorded when the campaign copied the base project,
	 *   keyed by the link's path; a link whose links form a cycle is absent
	 * @param campaignEdits the unified diff since confirmation, empty if there are no prompt edits
	 * @throws NullPointerException if any of the arguments are null or contain null
	 * @throws IllegalArgumentException if {@code cell} is blank
	 */
	public AuthorBrief
	{
		requireThat(cell, "category").isNotBlank();
		requireThat(approach, "approach").isNotNull();
		requireThat(contract, "contract").isNotNull();
		requireThat(developmentFailures, "developmentFailures").isNotNull();
		requireThat(developmentCounts, "developmentCounts").isNotNull();
		requireThat(endedAttempts, "endedAttempts").isNotNull();
		developmentFailures = List.copyOf(developmentFailures);
		developmentCounts = Map.copyOf(developmentCounts);
		endedAttempts = List.copyOf(endedAttempts);
		links = Map.copyOf(links);
		requireThat(campaignEdits, "campaignEdits").isNotNull();
	}

	/**
	 * Returns every supplied failure's citation identifier, preserving its occurrence in this brief. Only development
	 * failures are supplied failures; an ended attempt's failure summaries are context that the author neither
	 * classifies nor cites.
	 *
	 * @return the unique identifiers
	 */
	public Set<String> failureIds()
	{
		Set<String> identifiers = new HashSet<>();
		for (int index = 0; index < developmentFailures.size(); ++index)
			identifiers.add(developmentId(index));
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
		Failure failure = developmentFailures.get(occurrence);
		// The occurrence keeps repeated instances distinct supplied failures
		return Sha256.of(("development\n" + occurrence + "\n" + AuthorManifest.instanceId(failure.instance())).
			getBytes(StandardCharsets.UTF_8)).hex();
	}

	/**
	 * Returns the brief of an author attempt.
	 *
	 * @param campaign a campaign with a confirmed contract
	 * @param number the attempt reserved in the campaign's round, which determines its cell and approach
	 * @param campaignEdits the unified diff since confirmation, restricted to prompt files
	 * @param fork the files recorded at the fork, whose symbolic links the author is told about
	 * @return the brief
	 */
	static AuthorBrief of(Campaign campaign, int number, FileInventory fork, String campaignEdits)
	{
		Development development = campaign.development();
		List<EndedAttempt> ended = new ArrayList<>();
		for (CertificationAttempt attempt : campaign.attempts())
		{
			if (!attempt.isRunning())
			{
				ended.add(new EndedAttempt(attempt.index(), attempt.outcome(), attempt.counts(),
					attempt.failures().stream().map(Failure::summary).toList()));
			}
		}
		Map<Path, Path> links = new HashMap<>();
		for (Map.Entry<Path, FileEntry> entry : fork.entries().entrySet())
		{
			if (entry.getValue() instanceof FileEntry.SymbolicLink)
				fork.resolve(entry.getKey()).ifPresent(target -> links.put(entry.getKey(), target));
		}
		return new AuthorBrief(development.round().reservations().get(number), development.round().approach(number),
			campaign.contract(), development.failures(), development.counts(), ended,
			links, campaignEdits);
	}

	/**
	 * The certification outcomes of an attempt that has ended.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param index the attempt's certification index
	 * @param outcome the attempt's outcome
	 * @param counts the attempt's adopted certification outcomes per cell
	 * @param failures the summaries of the attempt's certification failures, in adoption order
	 */
	public record EndedAttempt(int index, Outcome outcome, Map<String, CellCounts> counts,
		List<FailureSummary> failures)
	{
		/**
		 * Creates the outcomes of an ended attempt.
		 *
		 * @param index the attempt's certification index
		 * @param outcome the attempt's outcome
		 * @param counts the attempt's adopted certification outcomes per cell
		 * @param failures the summaries of the attempt's certification failures, in adoption order
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
