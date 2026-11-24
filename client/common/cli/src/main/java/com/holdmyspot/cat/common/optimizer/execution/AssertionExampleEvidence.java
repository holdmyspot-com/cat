package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.EvidenceScope;
import com.holdmyspot.cat.common.optimizer.contract.JudgeProfile;
import com.holdmyspot.cat.common.optimizer.contract.JudgedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.RecordedTrial;
import com.holdmyspot.cat.common.optimizer.evidence.ActivityFile;
import com.holdmyspot.cat.common.optimizer.evidence.EvidenceSection;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prepares a frozen judge request for one rendered assertion example.
 */
public final class AssertionExampleEvidence
{
	private final JsonMapper json;
	private final JudgeProtocol judge;

	/**
	 * Creates the request builder.
	 *
	 * @param json the operation-owned mapper
	 * @param judge the protocol for the frozen judge
	 * @throws NullPointerException if any argument is null
	 */
	public AssertionExampleEvidence(JsonMapper json, JudgeProtocol judge)
	{
		requireThat(json, "json").isNotNull();
		requireThat(judge, "judge").isNotNull();
		this.json = json;
		this.judge = judge;
	}

	/**
	 * Builds the request that judges an example using its assertion's evidence scope. An actions request uses the
	 * example's normalized activity in the transcript slot. A response request uses the replies and the supplied case.
	 * The caller keeps the rendered example and scratch directory available until the judge finishes with the request.
	 *
	 * @param profile the frozen judge profile
	 * @param testCase the drawn test case whose values filled the example
	 * @param example the rendered example
	 * @param assertion the only assertion the judge decides
	 * @param scratch an existing directory owned by the caller
	 * @return the judge request
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if actions evidence cannot be read or written
	 */
	public SessionRequest request(JudgeProfile profile, DrawnTestCase testCase, RecordedTrial example,
		JudgedAssertion assertion, Path scratch) throws IOException
	{
		requireThat(profile, "profile").isNotNull();
		requireThat(testCase, "testCase").isNotNull();
		requireThat(example, "example").isNotNull();
		requireThat(assertion, "assertion").isNotNull();
		requireThat(scratch, "scratch").isNotNull();

		Path activity = scratch.resolve(ActivityFile.EXAMPLE_NAME);
		String section = "";
		if (assertion.scope() == EvidenceScope.ACTIONS)
		{
			activity = Files.createTempDirectory(scratch, "example-activity-").resolve(ActivityFile.EXAMPLE_NAME);
			section = actionsSection(example, activity);
			ActivityFile.write(json, activity, example.activity());
		}
		SessionResult.Completed evidence = new SessionResult.Completed(activity, example.replies(), example.effects(),
			example.initialProject(), null, null, null, List.of(), "none", List.of());
		return judge.request(profile, testCase, evidence, List.of(assertion), List.of(), section);
	}

	/**
	 * Adds the example's evidence description after the paths that the judge protocol remaps.
	 *
	 * @param example the rendered example
	 * @param activity the example's materialized activity path
	 * @return the rendered evidence section
	 * @throws IOException if the project's file changes cannot be read
	 */
	private static String actionsSection(RecordedTrial example, Path activity) throws IOException
	{
		Path effects = example.effects();
		String section = EvidenceSection.render(example.activity(), activity, effects, example.initialProject());
		String body = EvidenceSection.withoutPaths(section, activity, effects);
		return section.substring(0, section.length() - body.length()) + """
			This record is an authored assertion example. Decide its one listed assertion.
			The transcript path provides the example's normalized activity, including tool output.

			""" + body;
	}
}
