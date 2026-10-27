package com.holdmyspot.cat.common.optimizer.execution;

import com.holdmyspot.cat.common.optimizer.campaign.AttemptResult;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorApproach;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorDiagnosis;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CellCounts;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.DecisionTable;
import com.holdmyspot.cat.common.optimizer.campaign.Development;
import com.holdmyspot.cat.common.optimizer.campaign.Failure;
import com.holdmyspot.cat.common.optimizer.campaign.FailureSummary;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.campaign.PairCounts;
import com.holdmyspot.cat.common.optimizer.campaign.Pairing;
import com.holdmyspot.cat.common.optimizer.campaign.Reference;
import com.holdmyspot.cat.common.optimizer.campaign.Round;
import com.holdmyspot.cat.common.optimizer.campaign.SoundTest;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.inventory.AuthorityWarning;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.statistics.PairedComparison;
import com.holdmyspot.cat.common.optimizer.statistics.SignificanceLevel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The contract and outcomes that a prompt author session may see: development failures and counts for the working
 * candidate, and certification outcomes from attempts that have ended. Certification test cases are held out, so an
 * ended attempt's failures appear only as their cell, test and failed requirements. It excludes outcomes from
 * certification attempts in progress.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param cell                the identifier of the cell (one prompt test under one trial profile) that the prompt
 *                            author attempt targets
 * @param approach            the editing approach assigned by the cell's frozen round order
 * @param contract            the confirmed contract
 * @param developmentFailures the working candidate's development failures, in adoption order
 * @param evidenceSections    the evidence section of each development failure's trial, keyed by the failure's
 *                            {@link #developmentId(int) citation identifier}: the trial's tool calls with their exit
 *                            statuses and outputs, child activity, hook outcomes and file changes, rendered as for a
 *                            judge session; a failure whose trial's transcript or effects are no longer retained has no
 *                            entry
 * @param developmentCounts   the working candidate's development outcomes per cell
 * @param endedAttempts       the certification attempts that have ended, in index order
 * @param links               the resolved target of each symbolic link recorded when the campaign copied the base
 *                            project, keyed by the link's path; a link whose links form a cycle is absent
 * @param campaignEdits       the unified diff of prompt files from confirmation to the working candidate
 * @param answeredWarnings    the needed edits outside the edit authority that the user declined by choosing to use an
 *                            authorized source, in the order they were answered; the prompt author is told not to
 *                            report them again
 * @param earlierManifests    the manifests of the lineage's earlier prompt author attempts with their promotion
 *                            results, in the order CAT retained them
 * @param soundTests          the tests of the current lineage that a test revision reported sound, in report order;
 *                            the prompt author may not classify their failures as test defects
 */
public record PromptAuthorBrief(String cell, PromptAuthorApproach approach, Contract contract,
	List<Failure> developmentFailures, Map<String, String> evidenceSections, Map<String, CellCounts> developmentCounts,
	List<EndedAttempt> endedAttempts, Map<Path, Path> links, String campaignEdits,
	List<AuthorityWarning> answeredWarnings, List<EarlierManifest> earlierManifests, List<SoundTest> soundTests)
{
	/**
	 * Creates a brief.
	 *
	 * @param cell                the identifier of the cell (one prompt test under one trial profile) that the prompt
	 *                            author attempt targets
	 * @param approach            the editing approach assigned to the reserved attempt
	 * @param contract            the confirmed contract
	 * @param developmentFailures the working candidate's development failures, in adoption order
	 * @param evidenceSections    the evidence section of each development failure's trial, keyed by the failure's
	 *                            citation identifier; a failure whose trial's transcript or effects are no longer
	 *                            retained has no entry
	 * @param developmentCounts   the working candidate's development outcomes per cell
	 * @param endedAttempts       the certification attempts that have ended, in index order
	 * @param links               the resolved target of each symbolic link recorded when the campaign copied the base
	 *                            project, keyed by the link's path; a link whose links form a cycle is absent
	 * @param campaignEdits       the unified diff since confirmation, empty if there are no prompt edits
	 * @param answeredWarnings    the needed edits outside the edit authority that the user declined by choosing to use an
	 *                            authorized source, in the order they were answered
	 * @param earlierManifests    the manifests of the lineage's earlier prompt author attempts with their promotion
	 *                            results, in the order CAT retained them
	 * @param soundTests          the tests of the current lineage that a test revision reported sound, in report
	 *                            order
	 * @throws NullPointerException     if any of the arguments are null or contain null
	 * @throws IllegalArgumentException if {@code cell} is blank
	 */
	public PromptAuthorBrief
	{
		requireThat(cell, "cell").isNotBlank();
		requireThat(approach, "approach").isNotNull();
		requireThat(contract, "contract").isNotNull();
		requireThat(developmentFailures, "developmentFailures").isNotNull();
		requireThat(evidenceSections, "evidenceSections").isNotNull();
		requireThat(developmentCounts, "developmentCounts").isNotNull();
		requireThat(endedAttempts, "endedAttempts").isNotNull();
		developmentFailures = List.copyOf(developmentFailures);
		evidenceSections = Map.copyOf(evidenceSections);
		developmentCounts = Map.copyOf(developmentCounts);
		endedAttempts = List.copyOf(endedAttempts);
		links = Map.copyOf(links);
		requireThat(campaignEdits, "campaignEdits").isNotNull();
		answeredWarnings = List.copyOf(answeredWarnings);
		earlierManifests = List.copyOf(earlierManifests);
		soundTests = List.copyOf(soundTests);
	}

	/**
	 * Returns every supplied failure by its citation identifier.
	 *
	 * @return the failures, in adoption order
	 */
	public Map<String, Failure> failuresById()
	{
		Map<String, Failure> failures = new LinkedHashMap<>();
		for (int index = 0; index < developmentFailures.size(); ++index)
			failures.put(developmentId(index), developmentFailures.get(index));
		return failures;
	}

	/**
	 * Returns the test of each supplied failure whose test a test revision reported sound.
	 *
	 * @return the test identifiers, keyed by the failure's citation identifier
	 */
	public Map<String, String> soundFailures()
	{
		Set<String> sound = new HashSet<>();
		for (SoundTest report : soundTests)
			sound.add(report.test());
		Map<String, String> failures = new HashMap<>();
		for (Map.Entry<String, Failure> entry : failuresById().entrySet())
		{
			String test = entry.getValue().testCase().testId();
			if (sound.contains(test))
				failures.put(entry.getKey(), test);
		}
		return failures;
	}

	/**
	 * Returns every supplied failure's citation identifier, preserving its occurrence in this brief. Only development
	 * failures are supplied failures; an ended attempt's failure summaries are context that the prompt author neither
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
		return developmentId(occurrence, developmentFailures.get(occurrence));
	}

	/**
	 * Identifies one supplied development failure.
	 *
	 * @param occurrence the failure's list index, from zero
	 * @param failure    the failure at that index
	 * @return its scoped citation identifier
	 */
	private static String developmentId(int occurrence, Failure failure)
	{
		// The occurrence keeps repeated failures of one drawn test case distinct
		return Sha256.of(("development\n" + occurrence + "\n" +
			PromptAuthorManifest.drawnTestCaseId(failure.testCase())). getBytes(StandardCharsets.UTF_8)).hex();
	}

	/**
	 * Returns the brief of a prompt author attempt.
	 *
	 * @param campaign         a campaign with a confirmed contract
	 * @param number           the attempt reserved in the campaign's round, which determines its cell and approach
	 * @param campaignEdits    the unified diff since confirmation, restricted to prompt files
	 * @param fork             the files recorded at the fork, whose symbolic links the prompt author is told about
	 * @param evidenceSections the evidence section of each development failure's trial, keyed by the failure's index in
	 *                         the working candidate's development failures; a failure whose trial's transcript or effects
	 *                         are no longer retained has no entry
	 * @return the brief
	 */
	static PromptAuthorBrief of(Campaign campaign, int number, FileInventory fork, String campaignEdits,
		Map<Integer, String> evidenceSections)
	{
		Round round = campaign.development().round();
		String cell = round.reservations().get(number);
		PromptAuthorApproach approach = round.approach(number, campaign.optimization().promptAuthorAttempts(cell,
			round.number()));
		return of(campaign, cell, approach, fork, campaignEdits, evidenceSections);
	}

	/**
	 * Builds the shared failure context for an addressed cell before or after reservation.
	 *
	 * @param campaign         the confirmed campaign
	 * @param cell             the addressed cell
	 * @param approach         the editing approach; ignored by a ranking session
	 * @param fork             the working overlay's original inventory
	 * @param campaignEdits    the edits since confirmation
	 * @param evidenceSections retained trial sections, empty for ranking
	 * @return the failure context
	 */
	static PromptAuthorBrief of(Campaign campaign, String cell, PromptAuthorApproach approach, FileInventory fork,
		String campaignEdits, Map<Integer, String> evidenceSections)
	{
		Development development = campaign.development();
		Map<String, String> sections = new HashMap<>();
		for (Map.Entry<Integer, String> entry : evidenceSections.entrySet())
		{
			int index = entry.getKey();
			sections.put(developmentId(index, development.failures().get(index)), entry.getValue());
		}
		List<EndedAttempt> ended = new ArrayList<>();
		for (CertificationAttempt attempt : campaign.attempts())
		{
			if (!attempt.isRunning())
				ended.add(EndedAttempt.of(campaign.contract(), attempt));
		}
		Map<Path, Path> links = new HashMap<>();
		for (Map.Entry<Path, FileEntry> entry : fork.entries().entrySet())
		{
			if (entry.getValue() instanceof FileEntry.SymbolicLink)
				fork.resolve(entry.getKey()).ifPresent(target -> links.put(entry.getKey(), target));
		}
		List<EarlierManifest> earlier = new ArrayList<>();
		int lineage = campaign.lineage().number();
		for (PromptAuthorDiagnosis diagnosis : campaign.optimization().promptAuthors())
		{
			if (diagnosis.lineage() == lineage)
				earlier.add(new EarlierManifest(diagnosis, result(diagnosis, campaign.optimization().attempts())));
		}
		List<SoundTest> sound = new ArrayList<>();
		for (SoundTest report : campaign.testRevisions().sound())
		{
			if (report.lineage() == lineage)
				sound.add(report);
		}
		return new PromptAuthorBrief(cell, approach,
			campaign.contract(), development.failures(), sections, development.counts(), ended,
			links, campaignEdits, campaign.answeredWarnings(), earlier, sound);
	}

	/**
	 * Returns what became of an earlier prompt author attempt.
	 *
	 * @param diagnosis the attempt's retained manifest
	 * @param attempts  the retained promotion results of the campaign's prompt author attempts
	 * @return {@link PromotionResult#REJECTED} if CAT rejected the manifest; otherwise the attempt's promotion result,
	 *   or {@link PromotionResult#NOT_MEASURED} if none is retained
	 */
	private static PromotionResult result(PromptAuthorDiagnosis diagnosis, List<AttemptResult> attempts)
	{
		if (!diagnosis.rejections().isEmpty())
			return PromotionResult.REJECTED;
		for (AttemptResult attempt : attempts)
		{
			if (attempt.number() != diagnosis.number())
				continue;
			if (attempt.snapshot().equals(attempt.working()))
				return PromotionResult.NO_CANDIDATE;
			if (attempt.winner())
				return PromotionResult.PROMOTED;
			return PromotionResult.NOT_PROMOTED;
		}
		return PromotionResult.NOT_MEASURED;
	}

	/**
	 * What became of an earlier prompt author attempt.
	 */
	public enum PromotionResult
	{
		/**
		 * CAT rejected the attempt's manifest, so the attempt contributed no candidate.
		 */
		REJECTED,
		/**
		 * The attempt changed no prompt file or classified no kind routing or guidance, so it produced no candidate.
		 */
		NO_CANDIDATE,
		/**
		 * CAT holds no promotion result for the attempt: its promotion runs have not decided it, or it reported a
		 * conflict.
		 */
		NOT_MEASURED,
		/**
		 * The attempt's promotion runs did not make it the working candidate.
		 */
		NOT_PROMOTED,
		/**
		 * The attempt became the working candidate.
		 */
		PROMOTED
	}

	/**
	 * The manifest of an earlier prompt author attempt of the same lineage, with what became of the attempt.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param diagnosis the attempt's retained manifest
	 * @param result    what became of the attempt
	 */
	public record EarlierManifest(PromptAuthorDiagnosis diagnosis, PromotionResult result)
	{
		/**
		 * Creates an earlier manifest.
		 *
		 * @param diagnosis the attempt's retained manifest
		 * @param result    what became of the attempt
		 * @throws NullPointerException if any of the arguments are null
		 */
		public EarlierManifest
		{
			requireThat(diagnosis, "diagnosis").isNotNull();
			requireThat(result, "result").isNotNull();
		}
	}

	/**
	 * The certification outcomes of an attempt that has ended.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param index        the attempt's certification index
	 * @param outcome      the attempt's outcome
	 * @param counts       the attempt's adopted certification outcomes per cell
	 * @param failures     the summaries of the attempt's certification failures, in adoption order
	 * @param belowControl   the assertions that the candidate passed less often than the control, in contract order
	 * @param belowIncumbent the assertions that the candidate passed less often than the incumbent, in contract order
	 */
	public record EndedAttempt(int index, Outcome outcome, Map<String, CellCounts> counts, List<FailureSummary> failures,
		List<BelowReference> belowControl, List<BelowReference> belowIncumbent)
	{
		/**
		 * Creates the outcomes of an ended attempt.
		 *
		 * @param index          the attempt's certification index
		 * @param outcome        the attempt's outcome
		 * @param counts         the attempt's adopted certification outcomes per cell
		 * @param failures       the summaries of the attempt's certification failures, in adoption order
		 * @param belowControl   the assertions that the candidate passed less often than the control, in contract
		 *                       order
		 * @param belowIncumbent the assertions that the candidate passed less often than the incumbent, in contract
		 *                       order
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code index} is not positive
		 */
		public EndedAttempt
		{
			requireThat(index, "index").isPositive();
			requireThat(outcome, "outcome").isNotNull();
			requireThat(counts, "counts").isNotNull();
			requireThat(failures, "failures").isNotNull();
			requireThat(belowControl, "belowControl").isNotNull();
			requireThat(belowIncumbent, "belowIncumbent").isNotNull();
			counts = Map.copyOf(counts);
			failures = List.copyOf(failures);
			belowControl = List.copyOf(belowControl);
			belowIncumbent = List.copyOf(belowIncumbent);
		}

		/**
		 * Returns the outcomes of an ended attempt that a prompt author may see.
		 *
		 * @param contract the attempt's confirmed contract
		 * @param attempt  the ended attempt
		 * @return the outcomes
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if the attempt is running, or the contract lacks one of its cells
		 */
		public static EndedAttempt of(Contract contract, CertificationAttempt attempt)
		{
			requireThat(contract, "contract").isNotNull();
			requireThat(attempt, "attempt").isNotNull();
			requireThat(attempt.isRunning(), "attempt.isRunning()").isFalse();
			return new EndedAttempt(attempt.index(), attempt.outcome(), attempt.counts(),
				attempt.failures().stream().map(Failure::summary).toList(), below(contract, attempt, Reference.CONTROL),
				below(contract, attempt, Reference.INCUMBENT));
		}

		/**
		 * Returns the assertions that an attempt that ended below control or below incumbent proved the candidate to
		 * pass less often than one kind of reference trial, in the cells that the comparisons with that kind decided.
		 *
		 * @param contract  the attempt's confirmed contract
		 * @param attempt   the ended attempt
		 * @param reference the kind of reference trial
		 * @return the assertions, in contract order
		 * @throws IllegalArgumentException if the contract lacks one of the attempt's cells
		 */
		private static List<BelowReference> below(Contract contract, CertificationAttempt attempt, Reference reference)
		{
			if (attempt.outcome() != Outcome.BELOW_CONTROL && attempt.outcome() != Outcome.BELOW_INCUMBENT)
				return List.of();
			DecisionTable.CellDecision belowReference = switch (reference)
			{
				case CONTROL -> DecisionTable.CellDecision.BELOW_CONTROL;
				case INCUMBENT -> DecisionTable.CellDecision.BELOW_INCUMBENT;
			};
			SignificanceLevel level = attempt.procedure().boundaries().level();
			List<BelowReference> below = new ArrayList<>();
			for (String cell : attempt.procedure().cells())
			{
				if (DecisionTable.cellDecision(attempt, cell) != belowReference)
					continue;
				String testId = contract.findCell(cell).orElseThrow(() -> new IllegalArgumentException(
					"The contract has no cell identified by " + cell + ".")).testId();
				Pairing pairing = attempt.pairing(reference, cell);
				for (Map.Entry<String, PairCounts> comparison : pairing.comparisons().entrySet())
				{
					PairCounts pairs = comparison.getValue();
					if (PairedComparison.isBelow(pairs.tally(), level))
						below.add(new BelowReference(cell, testId, comparison.getKey(), pairs));
				}
			}
			return below;
		}
	}

	/**
	 * An assertion that a certification attempt proved the candidate to pass less often than its reference trials:
	 * the control trials or the incumbent trials.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param cellId      the identifier of the cell
	 * @param testId      the identifier of the cell's prompt test
	 * @param assertionId the identifier of the assertion
	 * @param pairs       the assertion's adopted pairs of a candidate formal trial and its reference trial
	 */
	public record BelowReference(String cellId, String testId, String assertionId, PairCounts pairs)
	{
		/**
		 * Creates a finding.
		 *
		 * @param cellId      the identifier of the cell
		 * @param testId      the identifier of the cell's prompt test
		 * @param assertionId the identifier of the assertion
		 * @param pairs       the assertion's adopted pairs of a candidate formal trial and its reference trial
		 * @throws NullPointerException if any of the arguments are null
		 */
		public BelowReference
		{
			requireThat(cellId, "cellId").isNotNull();
			requireThat(testId, "testId").isNotNull();
			requireThat(assertionId, "assertionId").isNotNull();
			requireThat(pairs, "pairs").isNotNull();
		}
	}
}
