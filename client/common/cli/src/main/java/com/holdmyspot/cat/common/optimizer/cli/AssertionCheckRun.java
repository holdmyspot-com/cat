package com.holdmyspot.cat.common.optimizer.cli;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.contract.EvidenceScope;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.ProposalSchema;
import com.holdmyspot.cat.common.optimizer.contract.TestValueJson;
import com.holdmyspot.cat.common.optimizer.execution.AssertionReview.Finding;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionChecks;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionChecks.ExampleResult;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionChecks.JudgedExample;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionChecks.PreparedExample;
import com.holdmyspot.cat.common.optimizer.proposal.DerivedProposal.ExampleJudgment;
import com.holdmyspot.cat.common.optimizer.proposal.DerivedProposal.TestReport;
import com.holdmyspot.cat.common.optimizer.proposal.JudgedAssertionChecks;
import com.holdmyspot.cat.common.optimizer.proposal.ProposalCheck.Assessment;
import com.holdmyspot.cat.common.optimizer.proposal.ProposalCheck.JudgeInputs;
import com.holdmyspot.cat.common.PortablePaths;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the judged checks for one author operation and keeps its latest report for each checked test.
 * <p>
 * <b>Thread Safety:</b> The caller runs one assessment at a time.
 */
final class AssertionCheckRun implements Function<Assessment, List<String>>
{
	private static final int ATTEMPTS = Campaign.INFRASTRUCTURE_FAILURE_LIMIT;
	private final JsonMapper json;
	private final ProposalSchema schema;
	private final JudgedAssertionChecks checks;
	private final Path project;
	private final Path directory;
	private final Sha256 base;
	private final AssertionAttemptFile attempts;
	private final Map<String, TestReport> reports = new LinkedHashMap<>();

	/**
	 * Creates a check run for the staged default project.
	 *
	 * @param json the campaign's mapper
	 * @param schema the schema that records the checked inputs
	 * @param checks the executor with the campaign's records and session port
	 * @param project the staged default project
	 * @param directory the private judge directory
	 * @param base the snapshot of the default project
	 * @param attempts persists failures across slices
	 */
	AssertionCheckRun(JsonMapper json, ProposalSchema schema, JudgedAssertionChecks checks, Path project,
		Path directory, Sha256 base, AssertionAttemptFile attempts)
	{
		this.json = json;
		this.schema = schema;
		this.checks = checks;
		this.project = project;
		this.directory = directory;
		this.base = base;
		this.attempts = attempts;
	}

	/**
	 * Checks all mechanically eligible tests. The result holds refusals for the author.
	 *
	 * @param assessment the mechanically checked draft
	 * @return refusals for the correction turn, in test order
	 * @throws Interrupted if the command or a judge is interrupted
	 * @throws Unavailable if a judge gives no usable result within its attempts
	 * @throws UncheckedIOException if evidence or a held-out record cannot be read or written
	 */
	@Override
	public List<String> apply(Assessment assessment)
	{
		JudgeInputs inputs = assessment.judgeInputs().orElse(null);
		if (inputs == null)
			return List.of();

		List<String> refusals = new ArrayList<>();
		try
		{
			for (PromptTest test : inputs.tests())
				reports.put(test.id(), checkTest(test, inputs, assessment.examples(), refusals));
		}
		catch (InterruptedException e)
		{
			throw new Interrupted(e);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		return List.copyOf(refusals);
	}

	/**
	 * Returns the reports from this operation, with judgments only.
	 *
	 * @return each checked test's latest report by ID
	 */
	Map<String, TestReport> reports()
	{
		return Map.copyOf(reports);
	}

	/**
	 * Checks a test's prepared examples and reviews its assertion declarations.
	 *
	 * @param test the test that passed its mechanical checks
	 * @param inputs the eligible declarations and frozen judge
	 * @param examples the prepared examples of the proposal
	 * @param refusals the correction diagnostics to append to
	 * @return the accepted verdicts and warnings for the proposal
	 * @throws IOException if the judge's input or record cannot be read or written
	 * @throws InterruptedException if a session or the command is interrupted
	 */
	private TestReport checkTest(PromptTest test, JudgeInputs inputs, List<PreparedExample> examples,
		List<String> refusals) throws IOException, InterruptedException
	{
		String definition = schema.judgedCheckDefinition(test, inputs.requirements(), inputs.judge());
		List<ExampleJudgment> judgments = new ArrayList<>();
		for (PreparedExample prepared : examples)
		{
			if (!prepared.test().id().equals(test.id()))
				continue;
			JudgedExample selected = AssertionChecks.selectExample(test, inputs.requirements(), prepared.check()).
				orElse(null);
			if (selected == null)
				continue;
			ExampleResult result = example(definition, inputs, prepared, selected);
			if (result.disposition() == AssertionChecks.Disposition.REFUSAL)
				refusals.add(result.diagnostic(prepared).orElseThrow());
			judgments.add(ExampleJudgment.of(result));
		}

		Sha256 key = Sha256.of(("review\n" + definition).getBytes(StandardCharsets.UTF_8));
		for (int count = attempts.failures(key); count < ATTEMPTS; ++count)
		{
			Optional<List<Finding>> review = checks.review(key, inputs.judge(), test, inputs.requirements(), directory);
			if (review.isPresent())
			{
				attempts.decided(key);
				return report(test, judgments, review.orElseThrow(), refusals);
			}
			attempts.failed(key, count + 1);
		}
		throw new Unavailable("The assertion review of prompt test `" + test.id() + "`");
	}

	/**
	 * Runs an example with a fresh directory on each attempt, or reads the complete recorded judgment.
	 *
	 * @param definition the checked test's serialized definition and judge
	 * @param inputs the eligible declarations and frozen judge
	 * @param prepared the example and case selected by its authored order
	 * @param selected the one assertion to judge
	 * @return the example judgment
	 * @throws IOException if the example or its record cannot be read or written
	 * @throws InterruptedException if a session or the command is interrupted
	 */
	private ExampleResult example(String definition, JudgeInputs inputs, PreparedExample prepared,
		JudgedExample selected) throws IOException, InterruptedException
	{
		Sha256 key = exampleKey(definition, prepared, selected);
		for (int count = attempts.failures(key); count < ATTEMPTS; ++count)
		{
			try (TemporaryDirectories temporary = new TemporaryDirectories())
			{
				Path scratch = temporary.add(Files.createTempDirectory(directory.getParent(), "assertion-example-"));
				Optional<ExampleResult> result = checks.example(key, inputs.judge(), prepared, selected, project, scratch);
				if (result.isPresent())
				{
					attempts.decided(key);
					return result.orElseThrow();
				}
			}
			attempts.failed(key, count + 1);
		}
		throw new Unavailable("The judge for prompt test `" + prepared.test().id() + "`, assertion `" +
			prepared.check().assertionId() + "`, example `" + prepared.check().exampleId() + "`");
	}

	/**
	 * Identifies a check by its exact inputs and, for actions, the default workspace evidence.
	 *
	 * @param definition the checked test's serialized definition and judge
	 * @param prepared the selected example and case
	 * @param selected the assertion to decide
	 * @return the held-out record key
	 */
	private Sha256 exampleKey(String definition, PreparedExample prepared, JudgedExample selected)
	{
		ObjectNode identity = json.createObjectNode().put("kind", "example").put("definition", definition).
			put("assertion", prepared.check().assertionId()).put("example", prepared.check().exampleId()).
			put("position", prepared.casePosition()).put("expected_outcome", prepared.testCase().expectedOutcome());
		TestValueJson.write(prepared.testCase().values(), identity.putArray("values"));
		if (selected.assertion().scope() == EvidenceScope.ACTIONS)
		{
			identity.put("base", base.hex());
			ArrayNode excluded = identity.putArray("excluded");
			prepared.excluded().stream().map(PortablePaths::text).sorted().forEach(excluded::add);
		}
		return Sha256.of(json.writeValueAsBytes(identity));
	}

	/**
	 * Collects reportable findings and adds a refusal when an assertion needs unavailable evidence.
	 *
	 * @param test the reviewed test
	 * @param examples the test's example judgments
	 * @param findings the test's review findings
	 * @param refusals the correction diagnostics to append to
	 * @return the report
	 */
	private static TestReport report(PromptTest test, List<ExampleJudgment> examples, List<Finding> findings,
		List<String> refusals)
	{
		for (Finding finding : findings)
		{
			if (finding.kind().refuses())
			{
				refusals.add("For prompt test `" + test.id() + "`, assertion `" + finding.assertionId() +
					"`, a response judge would need evidence of actions. " + finding.rationale());
			}
		}
		return new TestReport(examples, findings);
	}

	/**
	 * Carries a judge interruption across the synchronous author validation callback.
	 */
	static final class Interrupted extends RuntimeException
	{
		private static final long serialVersionUID = 1L;

		/**
		 * Creates an interruption.
		 *
		 * @param cause the underlying interruption
		 */
		Interrupted(InterruptedException cause)
		{
			super(cause);
		}
	}

	/**
	 * Carries work that remains pending when a slice stops admitting sessions.
	 */
	static final class Deferred extends RuntimeException
	{
		private static final long serialVersionUID = 1L;

		/**
		 * Creates a deferred check.
		 *
		 * @param reason why the check will run in another slice
		 */
		Deferred(String reason)
		{
			super(reason);
		}
	}

	/**
	 * Reports a check whose attempts did not return a usable result.
	 */
	static final class Unavailable extends RuntimeException
	{
		private static final long serialVersionUID = 1L;

		/**
		 * Creates a failure.
		 *
		 * @param check the check that did not finish
		 */
		Unavailable(String check)
		{
			super(check + " did not produce a valid result after " + ATTEMPTS + " attempts.");
		}
	}
}
