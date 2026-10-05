package io.github.cowwoc.cat.common.optimizer.port.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.JudgeProfile;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link JudgeVersionContent}.
 */
public final class JudgeVersionContentTest
{
	private static final TrialProfile ROLE = new TrialProfile("judge-model", "high");
	private static final JudgeProfile JUDGE = new JudgeProfile(ROLE, "Judge the trial.", "The reply is brief.",
		"Pass only if every rubric item holds.", "{\"type\":\"object\"}");
	private static final String SECTION = "Effects directory: <effects>\n\nNo tool calls\n";

	/**
	 * Creates the test.
	 */
	public JudgeVersionContentTest()
	{
	}

	/**
	 * Returns the judge and evidence section with one field changed, per row.
	 *
	 * @return the rows
	 */
	@DataProvider
	public static Object[][] changes()
	{
		return new Object[][]
			{
				{"model", new JudgeProfile(new TrialProfile("other-model", "high"), JUDGE.prompt(), JUDGE.rubric(),
					JUDGE.decisionRule(), JUDGE.resultSchema()), SECTION},
				{"effort", new JudgeProfile(new TrialProfile("judge-model", "low"), JUDGE.prompt(), JUDGE.rubric(),
					JUDGE.decisionRule(), JUDGE.resultSchema()), SECTION},
				{"prompt", new JudgeProfile(ROLE, "Judge the reply.", JUDGE.rubric(), JUDGE.decisionRule(),
					JUDGE.resultSchema()), SECTION},
				{"rubric", new JudgeProfile(ROLE, JUDGE.prompt(), "The reply is polite.", JUDGE.decisionRule(),
					JUDGE.resultSchema()), SECTION},
				{"decisionRule", new JudgeProfile(ROLE, JUDGE.prompt(), JUDGE.rubric(), "Pass if any rubric item holds.",
					JUDGE.resultSchema()), SECTION},
				{"resultSchema", new JudgeProfile(ROLE, JUDGE.prompt(), JUDGE.rubric(), JUDGE.decisionRule(),
					"{\"type\":\"array\"}"), SECTION},
				{"evidenceSection", JUDGE, SECTION + "File changes\n"}
			};
	}

	/**
	 * Verifies that a change to any field of the judge profile, or to the rendered evidence section, changes the
	 * hash that a judge version registry records.
	 *
	 * @param field the changed field
	 * @param judge the judge profile
	 * @param section the evidence section
	 */
	@Test(dataProvider = "changes")
	public void changeAltersHash(String field, JudgeProfile judge, String section)
	{
		// Requirement: R-M1
		String original = VersionRegistry.hash(JudgeVersionContent.of(JUDGE, SECTION));
		requireThat(VersionRegistry.hash(JudgeVersionContent.of(judge, section)), field).isNotEqualTo(original);
	}
}
