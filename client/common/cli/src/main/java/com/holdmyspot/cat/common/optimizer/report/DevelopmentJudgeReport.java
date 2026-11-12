package com.holdmyspot.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.DevelopmentJudge;
import com.holdmyspot.cat.common.optimizer.campaign.RejectedJudge;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * The judge that decides a prompt test's development trials and how a cheaper candidate has fared as that judge.
 * <p>
 * Certification attempts, incumbent baselines and control comparisons use the frozen judge whatever this report
 * shows.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param test        the prompt test's identifier
 * @param judge       the label of the profile that judges the test's development trials
 * @param frozen      {@code true} if the frozen judge decides the test's development trials, {@code false} if a
 *                    qualified candidate does
 * @param stage       how far the first remaining candidate has come: {@value #NOT_EXAMINED} before CAT examined one,
 *                    otherwise the lower-case name of the {@link DevelopmentJudge.Stage}
 * @param examining   the label of the candidate that is being examined or that qualified, or {@code null} if there is
 *                    none
 * @param shadowed    the number of trials that the frozen judge and the candidate both judged during shadowing
 * @param spotChecked the number of trials that the frozen judge also judged after the candidate qualified
 * @param lenient     the number of those trials in which the candidate passed an assertion that the frozen judge failed
 * @param strict      the number of those trials in which the candidate failed an assertion that the frozen judge passed
 * @param rejected    the candidates that the test turned down, in the order they were examined
 */
public record DevelopmentJudgeReport(String test, String judge, boolean frozen, String stage, String examining,
	int shadowed, int spotChecked, int lenient, int strict, List<RejectedJudge> rejected)
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
	 * @param spotChecked the number of spot-checked trials
	 * @param lenient     the number of trials with a lenient disagreement
	 * @param strict      the number of trials with a strict disagreement
	 * @param rejected    the candidates that the test turned down
	 * @throws NullPointerException     if {@code test}, {@code judge}, {@code stage} or {@code rejected} are null
	 * @throws IllegalArgumentException if {@code test}, {@code judge} or {@code stage} are blank, or a count is
	 *                                  negative
	 */
	public DevelopmentJudgeReport
	{
		requireThat(test, "test").isNotBlank();
		requireThat(judge, "judge").isNotBlank();
		requireThat(stage, "stage").isNotBlank();
		requireThat(shadowed, "shadowed").isNotNegative();
		requireThat(spotChecked, "spotChecked").isNotNegative();
		requireThat(lenient, "lenient").isNotNegative();
		requireThat(strict, "strict").isNotNegative();
		requireThat(rejected, "rejected").isNotNull();
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
				reports.add(new DevelopmentJudgeReport(test.id(), frozenLabel, true, NOT_EXAMINED, null, 0, 0, 0, 0,
					List.of()));
				continue;
			}
			String examining = state.current().map(TrialProfile::label).orElse(null);
			boolean qualified = state.stage() == DevelopmentJudge.Stage.QUALIFIED;
			String judge = frozenLabel;
			if (qualified)
				judge = examining;
			reports.add(new DevelopmentJudgeReport(test.id(), judge, !qualified, EnumNames.lowerCase(state.stage()),
				examining, state.shadowed(), state.spotChecked(), state.lenient(), state.strict(), state.rejected()));
		}
		return reports;
	}
}
