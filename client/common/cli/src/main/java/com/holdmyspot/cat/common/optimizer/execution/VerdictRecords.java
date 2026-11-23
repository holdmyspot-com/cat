package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInToolResult;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.JudgeProfile;
import com.holdmyspot.cat.common.optimizer.contract.JudgedAssertion;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.store.CampaignStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The verdicts that a campaign's judges made, each stored under the key of its judged assertion's evidence. A trial
 * whose evidence has a stored verdict takes it in place of a judge session, and the first verdict stored under a key
 * is final. The records belong to one campaign and are never found by another.
 * <p>
 * <b>Thread Safety:</b> This class is as thread-safe as its store.
 */
public final class VerdictRecords
{
	private final CampaignStore store;
	private final String campaignId;
	private final Harness harness;
	private final int judgeVersion;

	/**
	 * Creates the verdict records of a campaign.
	 *
	 * @param store        the store that holds the campaign
	 * @param campaignId   the campaign's identifier
	 * @param harness      the harness that runs the campaign's judges
	 * @param judgeVersion the version of the harness's judge
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code campaignId} is blank
	 */
	public VerdictRecords(CampaignStore store, String campaignId, Harness harness, int judgeVersion)
	{
		requireThat(store, "store").isNotNull();
		requireThat(campaignId, "campaignId").isNotBlank();
		requireThat(harness, "harness").isNotNull();
		this.store = store;
		this.campaignId = campaignId;
		this.harness = harness;
		this.judgeVersion = judgeVersion;
	}

	/**
	 * Returns the keys of the judged assertions of a trial.
	 *
	 * @param judge           the profile of the judge that decides the assertions
	 * @param testCase        the trial's test case
	 * @param assertions      the assertions that the judge decides
	 * @param trial           the trial session's result
	 * @param evidenceSection the rendered trial evidence; empty when the template does not name it
	 * @param results         the results of the test's built-in tools
	 * @return the key of each assertion, in order, or empty if the evidence that the template names cannot be read
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the evidence cannot be read
	 */
	public Optional<List<VerdictKey>> keys(JudgeProfile judge, DrawnTestCase testCase,
		List<JudgedAssertion> assertions, SessionResult.Completed trial, String evidenceSection,
		List<BuiltInToolResult> results) throws IOException
	{
		return VerdictKey.of(harness, judgeVersion, judge, testCase, assertions, trial, evidenceSection, results);
	}

	/**
	 * Returns the verdict stored under a key.
	 *
	 * @param key the key of a judged assertion's evidence
	 * @return the verdict, or empty if none is stored
	 * @throws NullPointerException if {@code key} is null
	 * @throws IOException          if the verdict cannot be read
	 */
	public Optional<VerdictRecord> find(VerdictKey key) throws IOException
	{
		return store.readVerdict(campaignId, key);
	}

	/**
	 * Returns a trial's judgment from the verdict that counts for each of its judged assertions. A verdict that another
	 * trial's session made names that trial.
	 *
	 * @param assertions the trial's judged assertions, in order
	 * @param counted    the verdict that counts for each assertion, by assertion identifier
	 * @param trial      the description of the trial that is judged
	 * @return the judgment
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if an assertion has no verdict
	 */
	public static Judgment judgment(List<JudgedAssertion> assertions, Map<String, VerdictRecord> counted,
		String trial)
	{
		requireThat(assertions, "assertions").isNotNull();
		requireThat(counted, "counted").isNotNull();
		requireThat(trial, "trial").isNotNull();
		List<AssertionVerdict> verdicts = new ArrayList<>(assertions.size());
		for (JudgedAssertion assertion : assertions)
		{
			VerdictRecord record = counted.get(assertion.assertion().id());
			requireThat(record, "counted.get(" + assertion.assertion().id() + ")").isNotNull();
			AssertionVerdict verdict = record.verdict();
			if (!record.trial().equals(trial))
			{
				verdict = new AssertionVerdict(verdict.assertion(), verdict.behavior(),
					verdict.rationale() + " (Recorded verdict of " + record.trial() + ".)");
			}
			verdicts.add(verdict);
		}
		return AssertionVerdict.judgment(verdicts);
	}

	/**
	 * Stores a verdict under a key unless one is stored already.
	 *
	 * @param key     the key of a judged assertion's evidence
	 * @param verdict the verdict
	 * @return the verdict that counts for the key: {@code verdict}, or the verdict that was stored first
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the verdict cannot be read or written
	 */
	public VerdictRecord write(VerdictKey key, VerdictRecord verdict) throws IOException
	{
		return store.writeVerdict(campaignId, key, verdict);
	}
}
