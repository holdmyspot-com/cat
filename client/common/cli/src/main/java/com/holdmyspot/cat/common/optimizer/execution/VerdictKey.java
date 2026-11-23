package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInToolResult;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.JudgeProfile;
import com.holdmyspot.cat.common.optimizer.contract.JudgedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.contract.TestValue;
import com.holdmyspot.cat.common.optimizer.evidence.EvidenceSection;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryScanner;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.report.EnumNames;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The key under which the verdict of one judged assertion is stored.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param digest the digest of the test case, the assertion, the judge's identity and the evidence that the judge
 *               reads
 */
public record VerdictKey(Sha256 digest)
{
	private static final String FORMAT = "cat-verdict-key-v1";
	private static final String TRANSCRIPT = "{{transcript}}";
	private static final String EFFECTS = "{{effects}}";

	/**
	 * Creates a key.
	 *
	 * @param digest the digest of the test case, the assertion, the judge's identity and the evidence that the judge
	 *               reads
	 * @throws NullPointerException if {@code digest} is null
	 */
	public VerdictKey
	{
		requireThat(digest, "digest").isNotNull();
	}

	/**
	 * Returns the key of each judged assertion of a trial.
	 *
	 * @param harness         the harness that runs the judge
	 * @param judgeVersion    the version of the harness's judge
	 * @param judge           the profile of the judge that decides the assertions
	 * @param testCase        the trial's test case
	 * @param assertions      the assertions that the judge decides
	 * @param trial           the trial session's result
	 * @param evidenceSection the rendered trial evidence; empty when the template does not name it
	 * @param results         the results of the test's built-in tools
	 * @return the key of each assertion, in order, or empty if the template names a transcript or an effects directory
	 *   that cannot be read
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the evidence cannot be read
	 */
	public static Optional<List<VerdictKey>> of(Harness harness, int judgeVersion, JudgeProfile judge,
		DrawnTestCase testCase, List<JudgedAssertion> assertions, SessionResult.Completed trial,
		String evidenceSection, List<BuiltInToolResult> results) throws IOException
	{
		requireThat(harness, "harness").isNotNull();
		requireThat(judge, "judge").isNotNull();
		requireThat(testCase, "testCase").isNotNull();
		requireThat(assertions, "assertions").isNotNull();
		requireThat(trial, "trial").isNotNull();
		requireThat(evidenceSection, "evidenceSection").isNotNull();
		requireThat(results, "results").isNotNull();
		Map<String, String> textByName = PromptTest.textByName(testCase.testCase());
		Encoder shared = new Encoder();
		shared.text(FORMAT);
		writeTestCase(shared, testCase);
		writeJudge(shared, harness, judgeVersion, judge);
		if (!writeEvidence(shared, judge, trial, evidenceSection, results, textByName))
			return Optional.empty();
		byte[] common = shared.bytes();
		List<VerdictKey> keys = new ArrayList<>(assertions.size());
		for (JudgedAssertion assertion : assertions)
		{
			Encoder encoder = new Encoder();
			encoder.raw(common);
			writeAssertion(encoder, assertion, textByName);
			keys.add(new VerdictKey(Sha256.of(encoder.bytes())));
		}
		return Optional.of(List.copyOf(keys));
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
	 * Adds the judge's identity: its harness, model, effort and version, and the digests of its prompt template,
	 * rubric, decision rule and result schema.
	 *
	 * @param encoder      the encoder
	 * @param harness      the harness that runs the judge
	 * @param judgeVersion the version of the harness's judge
	 * @param judge        the judge's profile
	 */
	private static void writeJudge(Encoder encoder, Harness harness, int judgeVersion, JudgeProfile judge)
	{
		encoder.text(EnumNames.lowerCase(harness)).text(judge.profile().model()).text(judge.profile().effort()).
			count(judgeVersion);
		for (String text : List.of(judge.prompt(), judge.rubric(), judge.decisionRule(), judge.resultSchema()))
			encoder.text(Sha256.of(text.getBytes(UTF_8)).hex());
	}

	/**
	 * Adds the evidence that the judge reads: the trial's replies, its evidence section without the heading that names
	 * its directories, the built-in tool results, and the bytes of the transcript and of the effects directory when
	 * the template names them.
	 *
	 * @param encoder         the encoder
	 * @param judge           the judge's profile
	 * @param trial           the trial session's result
	 * @param evidenceSection the rendered trial evidence
	 * @param results         the results of the test's built-in tools
	 * @param textByName      the test case's text of each value, by name
	 * @return {@code false} if the template names a transcript or an effects directory that cannot be read
	 * @throws IOException if the evidence cannot be read
	 */
	private static boolean writeEvidence(Encoder encoder, JudgeProfile judge, SessionResult.Completed trial,
		String evidenceSection, List<BuiltInToolResult> results, Map<String, String> textByName) throws IOException
	{
		encoder.count(trial.responses().size());
		for (List<String> turn : trial.responses())
		{
			encoder.count(turn.size());
			for (String message : turn)
				encoder.text(message);
		}
		encoder.text(EvidenceSection.withoutPaths(evidenceSection, trial.transcript(), trial.effects()));
		encoder.count(results.size());
		for (BuiltInToolResult result : results)
		{
			Assertion assertion = result.assertion();
			encoder.text(assertion.id()).text(assertion.statement(textByName)).optional(assertion.tool()).
				text(EnumNames.lowerCase(assertion.onToolFailure())).text(Boolean.toString(result.passed())).
				text(result.observed());
		}
		if (judge.prompt().contains(TRANSCRIPT))
		{
			Optional<Sha256> transcript = identity(trial.transcript());
			if (transcript.isEmpty())
				return false;
			encoder.text("transcript").text(transcript.get().hex());
		}
		if (judge.prompt().contains(EFFECTS))
		{
			if (!Files.isDirectory(trial.effects(), LinkOption.NOFOLLOW_LINKS))
				return false;
			encoder.text("effects").text(InventoryScanner.scan(trial.effects(), Set.of()).identity().hex());
		}
		return true;
	}

	/**
	 * Returns the identity of a transcript: the digest of a regular file's bytes, or the identity of a directory's
	 * files.
	 *
	 * @param transcript the transcript
	 * @return the identity, or empty if the transcript is neither a regular file nor a directory
	 * @throws IOException if the transcript cannot be read
	 */
	private static Optional<Sha256> identity(Path transcript) throws IOException
	{
		if (Files.isRegularFile(transcript, LinkOption.NOFOLLOW_LINKS))
			return Optional.of(Sha256.of(Files.readAllBytes(transcript)));
		if (Files.isDirectory(transcript, LinkOption.NOFOLLOW_LINKS))
			return Optional.of(InventoryScanner.scan(transcript, Set.of()).identity());
		return Optional.empty();
	}

	/**
	 * Adds one judged assertion: its identifier, its statement with the test case's values, its requirement and the
	 * failure behaviors that it admits.
	 *
	 * @param encoder    the encoder
	 * @param assertion  the assertion
	 * @param textByName the test case's text of each value, by name
	 */
	private static void writeAssertion(Encoder encoder, JudgedAssertion assertion, Map<String, String> textByName)
	{
		encoder.text(assertion.assertion().id()).text(assertion.assertion().statement(textByName));
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
		 * Adds a text that may be absent.
		 *
		 * @param text the text, or {@code null}
		 * @return this encoder
		 */
		Encoder optional(String text)
		{
			if (text == null)
				return absent();
			return text(text);
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
