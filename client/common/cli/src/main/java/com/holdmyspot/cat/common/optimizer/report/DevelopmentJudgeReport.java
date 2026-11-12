package com.holdmyspot.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.DevelopmentJudge;
import com.holdmyspot.cat.common.optimizer.campaign.ExaminedJudge;
import com.holdmyspot.cat.common.optimizer.campaign.RejectedJudge;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The judge that decides a prompt test's development trials and how cheaper candidates have fared as that judge.
 * <p>
 * Certification attempts, incumbent baselines and control comparisons count the frozen judge's verdicts whatever
 * this report shows; a shadowed candidate also judges them, and those trials count as held-out shadowed trials.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param test        the prompt test's identifier
 * @param judge       the label of the profile that judges the test's development trials
 * @param frozen      {@code true} if the frozen judge decides the test's development trials, {@code false} if a
 *                    qualified candidate does
 * @param stage       how far the test has come: {@value #NOT_EXAMINED} before CAT examined a candidate, otherwise the
 *                    lower-case name of the {@link DevelopmentJudge.Stage}
 * @param examining   the label of the candidate that qualified or, before any did, of the first candidate in
 *                    examination, or {@code null} if there is none
 * @param shadowed    the number of trials that the frozen judge and that candidate both judged during shadowing
 * @param heldOut     the number of those shadowed trials that were not development trials
 * @param spotChecked the number of trials that the frozen judge also judged after the candidate qualified
 * @param lenient     the number of those trials in which the candidate passed an assertion that the frozen judge failed
 * @param strict      the number of those trials in which the candidate failed an assertion that the frozen judge passed
 * @param candidates  every candidate in examination, or the one that qualified, in the order they started
 * @param rejected    the candidates that the test turned down, in the order they were examined
 */
public record DevelopmentJudgeReport(String test, String judge, boolean frozen, String stage, String examining,
	int shadowed, int heldOut, int spotChecked, int lenient, int strict, List<ExaminedJudge> candidates,
	List<RejectedJudge> rejected)
{
	/**
	 * The stage of a test for which CAT has not examined a candidate yet.
	 */
	public static final String NOT_EXAMINED = "not_examined";

	/**
	 * Creates a report of one prompt test.
	 *
	 * @param test        the prompt test's identifier
	 * @param judge       the label of the profile that judges the test's development trials
	 * @param frozen      {@code true} if the frozen judge decides the test's development trials
	 * @param stage       how far the first remaining candidate has come
	 * @param examining   the label of the candidate that is being examined or that qualified, or {@code null}
	 * @param shadowed    the number of shadowed trials
	 * @param heldOut     the number of shadowed trials that were not development trials
	 * @param spotChecked the number of spot-checked trials
	 * @param lenient     the number of trials with a lenient disagreement
	 * @param strict      the number of trials with a strict disagreement
	 * @param candidates  every candidate in examination, or the one that qualified
	 * @param rejected    the candidates that the test turned down
	 * @throws NullPointerException     if {@code test}, {@code judge}, {@code stage}, {@code candidates} or
	 *                                  {@code rejected} are null
	 * @throws IllegalArgumentException if {@code test}, {@code judge} or {@code stage} are blank, or a count is
	 *                                  negative
	 */
	public DevelopmentJudgeReport
	{
		requireThat(test, "test").isNotBlank();
		requireThat(judge, "judge").isNotBlank();
		requireThat(stage, "stage").isNotBlank();
		requireThat(shadowed, "shadowed").isNotNegative();
		requireThat(heldOut, "heldOut").isNotNegative();
		requireThat(spotChecked, "spotChecked").isNotNegative();
		requireThat(lenient, "lenient").isNotNegative();
		requireThat(strict, "strict").isNotNegative();
		requireThat(candidates, "candidates").isNotNull();
		requireThat(rejected, "rejected").isNotNull();
		candidates = List.copyOf(candidates);
		rejected = List.copyOf(rejected);
	}

	/**
	 * Returns the report of each prompt test of a campaign's contract.
	 *
	 * @param campaign the campaign
	 * @return the reports in the contract's order, or an empty list before a contract is confirmed
	 * @throws NullPointerException if {@code campaign} is null
	 */
	public static List<DevelopmentJudgeReport> of(Campaign campaign)
	{
		requireThat(campaign, "campaign").isNotNull();
		if (!campaign.isConfirmed())
			return List.of();
		String frozenLabel = campaign.contract().judge().profile().label();
		List<DevelopmentJudgeReport> reports = new ArrayList<>();
		for (PromptTest test : campaign.contract().tests())
		{
			DevelopmentJudge state = campaign.judges().get(test.id());
			if (state == null)
			{
				reports.add(new DevelopmentJudgeReport(test.id(), frozenLabel, true, NOT_EXAMINED, null, 0, 0, 0, 0, 0,
					List.of(), List.of()));
				continue;
			}
			String examining = state.current().map(TrialProfile::label).orElse(null);
			Optional<ExaminedJudge> qualified = state.qualified();
			String judge = qualified.map(candidate -> candidate.profile().label()).orElse(frozenLabel);
			reports.add(new DevelopmentJudgeReport(test.id(), judge, qualified.isEmpty(),
				EnumNames.lowerCase(state.stage()), examining, state.shadowed(), state.heldOut(), state.spotChecked(),
				state.lenient(), state.strict(), state.examined(), state.rejected()));
		}
		return reports;
	}
}
