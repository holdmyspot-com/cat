package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.EvidenceScope;
import com.holdmyspot.cat.common.optimizer.contract.JudgeProfile;
import com.holdmyspot.cat.common.optimizer.contract.JudgedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.contract.TestValue;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.report.EnumNames;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The key under which the verdict of one judged assertion is stored.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param digest the digest of the test case, the assertion with its scope, the judge's identity and the replies that
 *               the judge reads
 */
public record VerdictKey(Sha256 digest)
{
	private static final String FORMAT = "cat-verdict-key-v2";

	/**
	 * Creates a key.
	 *
	 * @param digest the digest of the test case, the assertion with its scope, the judge's identity and the replies
	 *               that the judge reads
	 * @throws NullPointerException if {@code digest} is null
	 */
	public VerdictKey
	{
		requireThat(digest, "digest").isNotNull();
	}

	/**
	 * Returns the key of each judged assertion of a trial. Only the verdicts of the {@link EvidenceScope#RESPONSE}
	 * scope are stored under their keys, because their judge reads only the test case and the replies, which repeat
	 * across trials of one test case.
	 *
	 * @param harness      the harness that runs the judge
	 * @param judgeVersion the version of the harness's judge
	 * @param judge        the profile of the judge that decides the assertions
	 * @param testCase     the trial's test case
	 * @param assertions   the assertions that the judge decides
	 * @param trial        the trial session's result
	 * @return the key of each assertion, in order
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if the judge has no response prompt
	 */
	public static List<VerdictKey> of(Harness harness, int judgeVersion, JudgeProfile judge,
		DrawnTestCase testCase, List<JudgedAssertion> assertions, SessionResult.Completed trial)
	{
		requireThat(harness, "harness").isNotNull();
		requireThat(judge, "judge").isNotNull();
		requireThat(testCase, "testCase").isNotNull();
		requireThat(assertions, "assertions").isNotNull();
		requireThat(trial, "trial").isNotNull();
		Map<String, String> textByName = PromptTest.textByName(testCase.testCase());
		Encoder shared = new Encoder();
		shared.text(FORMAT);
		writeTestCase(shared, testCase);
		writeJudge(shared, harness, judgeVersion, judge);
		writeReplies(shared, trial);
		byte[] common = shared.bytes();
		List<VerdictKey> keys = new ArrayList<>(assertions.size());
		for (JudgedAssertion assertion : assertions)
		{
			Encoder encoder = new Encoder();
			encoder.raw(common);
			writeAssertion(encoder, assertion, textByName);
			keys.add(new VerdictKey(Sha256.of(encoder.bytes())));
		}
		return List.copyOf(keys);
	}

	/**
	 * Adds the test case: the test, its values, its expected outcome, its turns and its expected behaviour.
	 *
	 * @param encoder  the encoder
	 * @param testCase the test case
	 */
	private static void writeTestCase(Encoder encoder, DrawnTestCase testCase)
	{
		encoder.text(testCase.testId());
		Map<String, TestValue> values = testCase.testCase().values();
		encoder.count(values.size());
		for (Map.Entry<String, TestValue> entry : values.entrySet())
		{
			encoder.text(entry.getKey());
			switch (entry.getValue())
			{
				case TestValue.Text text -> encoder.text("text").text(text.text());
				case TestValue.Generated generated ->
				{
					encoder.text("generated").text(generated.generator()).count(generated.version()).
						text(Long.toString(generated.seed()));
					Map<String, Long> parameters = new TreeMap<>(generated.parameters());
					encoder.count(parameters.size());
					for (Map.Entry<String, Long> parameter : parameters.entrySet())
						encoder.text(parameter.getKey()).text(Long.toString(parameter.getValue()));
				}
			}
		}
		encoder.text(testCase.testCase().expectedOutcome());
		encoder.count(testCase.turns().size());
		for (String turn : testCase.turns())
			encoder.text(turn);
		encoder.text(testCase.expectedBehaviour());
	}

	/**
	 * Adds the judge's identity: its harness, model, effort and version, and the digests of its response prompt,
	 * rubric, decision rule and result schema.
	 *
	 * @param encoder      the encoder
	 * @param harness      the harness that runs the judge
	 * @param judgeVersion the version of the harness's judge
	 * @param judge        the judge's profile
	 * @throws IllegalArgumentException if the judge has no response prompt
	 */
	private static void writeJudge(Encoder encoder, Harness harness, int judgeVersion, JudgeProfile judge)
	{
		encoder.text(EnumNames.lowerCase(harness)).text(judge.profile().model()).text(judge.profile().effort()).
			count(judgeVersion);
		for (String text : List.of(judge.prompt(EvidenceScope.RESPONSE), judge.rubric(), judge.decisionRule(),
			judge.resultSchema()))
		{
			encoder.text(Sha256.of(text.getBytes(UTF_8)).hex());
		}
	}

	/**
	 * Adds the trial's replies: each turn's assistant messages, in order.
	 *
	 * @param encoder the encoder
	 * @param trial   the trial session's result
	 */
	private static void writeReplies(Encoder encoder, SessionResult.Completed trial)
	{
		encoder.count(trial.responses().size());
		for (List<String> turn : trial.responses())
		{
			encoder.count(turn.size());
			for (String message : turn)
				encoder.text(message);
		}
	}

	/**
	 * Adds one judged assertion: its identifier, its statement with the test case's values, its scope, its requirement
	 * and the failure behaviors that it admits.
	 *
	 * @param encoder    the encoder
	 * @param assertion  the assertion
	 * @param textByName the test case's text of each value, by name
	 */
	private static void writeAssertion(Encoder encoder, JudgedAssertion assertion, Map<String, String> textByName)
	{
		encoder.text(assertion.assertion().id()).text(assertion.assertion().statement(textByName)).
			text(EnumNames.lowerCase(assertion.scope()));
		Requirement requirement = assertion.requirement();
		if (requirement == null)
			encoder.absent();
		else
			encoder.text(requirement.id()).text(requirement.description()).text(requirement.source());
		List<String> behaviors = assertion.behaviors();
		encoder.count(behaviors.size());
		for (String behavior : behaviors)
			encoder.text(behavior);
	}

	/**
	 * Writes the parts of a key, each prefixed by its length, so that no two sequences of parts have the same bytes.
	 */
	private static final class Encoder
	{
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		/**
		 * Creates an empty encoder.
		 */
		Encoder()
		{
		}

		/**
		 * Adds a text.
		 *
		 * @param text the text
		 * @return this encoder
		 */
		Encoder text(String text)
		{
			byte[] encoded = text.getBytes(UTF_8);
			bytes.write('s');
			length(encoded.length);
			bytes.writeBytes(encoded);
			return this;
		}

		/**
		 * Adds the marker of an absent part.
		 *
		 * @return this encoder
		 */
		Encoder absent()
		{
			bytes.write('n');
			return this;
		}

		/**
		 * Adds a count.
		 *
		 * @param count the count
		 * @return this encoder
		 */
		Encoder count(int count)
		{
			bytes.write('c');
			length(count);
			return this;
		}

		/**
		 * Adds bytes that another encoder wrote.
		 *
		 * @param encoded the bytes
		 */
		void raw(byte[] encoded)
		{
			bytes.writeBytes(encoded);
		}

		/**
		 * Adds a 32-bit big-endian number.
		 *
		 * @param value the number
		 */
		private void length(int value)
		{
			bytes.write(value >>> 24);
			bytes.write(value >>> 16);
			bytes.write(value >>> 8);
			bytes.write(value);
		}

		/**
		 * Returns the bytes written so far.
		 *
		 * @return the bytes
		 */
		byte[] bytes()
		{
			return bytes.toByteArray();
		}
	}
}
