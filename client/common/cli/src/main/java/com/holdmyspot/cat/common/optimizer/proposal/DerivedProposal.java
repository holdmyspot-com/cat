package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.DerivedSource;
import com.holdmyspot.cat.common.optimizer.execution.AssertionReview.Finding;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionChecks.ExampleResult;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The validated contract and findings from the session that reads the prompt system and proposes its requirements
 * and tests (the contract author).
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param contract the proposed contract, with the judge model, effort, prompt and result schema that CAT supplies
 * @param derivedSources the prompt files that load although no harness event reports them
 * @param scopeConditions the conditions that make certification unavailable
 * @param conflicts the groups of requirements that no reply can satisfy together
 * @param observedSources non-blank text listing the prompt sources reported loading by the contract author session's
 *   harness events, one per line, or {@code none}
 * @param derivation the contract author's non-blank output as JSON, which a revision sends back to the contract author
 *   with the user's feedback
 * @param draft the draft directory that holds the prompt tests as files
 * @param changes how each prompt test relates to the recorded prompt tests, by prompt test identifier; empty until
 *   the caller compares the draft with the project
 * @param exampleChecks the mechanical results for the assertion examples, in test and assertion order
 * @param judgedReports the report summaries by test identifier
 */
public record DerivedProposal(Contract contract, List<DerivedSource> derivedSources,
	List<ScopeCondition> scopeConditions, List<Conflict> conflicts, String observedSources, String derivation,
	Path draft, Map<String, DraftChange> changes, List<AssertionExampleCheck> exampleChecks,
	Map<String, TestReport> judgedReports)
{
	/**
	 * Creates a derived proposal.
	 *
	 * @param contract the proposed contract, with the judge model, effort, prompt and result schema that CAT supplies
	 * @param derivedSources the prompt files that load although no harness event reports them
	 * @param scopeConditions the conditions that make certification unavailable
	 * @param conflicts the groups of requirements that no reply can satisfy together
	 * @param observedSources non-blank text listing the prompt sources reported loading by the contract author
	 *   session's harness events
	 * @param derivation the contract author's non-blank output as JSON
	 * @param draft the draft directory that holds the prompt tests as files
	 * @param changes how each prompt test relates to the recorded prompt tests, by prompt test identifier
	 * @param exampleChecks the mechanical results for the assertion examples
	 * @param judgedReports the report summaries by test identifier
	 * @throws NullPointerException if any of the arguments are null, or a list or map contains null
	 * @throws IllegalArgumentException if any report identifiers, {@code observedSources} or {@code derivation} are blank
	 */
	public DerivedProposal
	{
		requireThat(contract, "contract").isNotNull();
		requireThat(observedSources, "observedSources").isNotBlank();
		requireThat(derivation, "derivation").isNotBlank();
		derivedSources = List.copyOf(derivedSources);
		scopeConditions = List.copyOf(scopeConditions);
		conflicts = List.copyOf(conflicts);
		requireThat(draft, "draft").isNotNull();
		requireThat(changes, "changes").isNotNull();
		changes = Collections.unmodifiableMap(new LinkedHashMap<>(changes));
		exampleChecks = List.copyOf(exampleChecks);
		judgedReports = Map.copyOf(judgedReports);
		for (String testId : judgedReports.keySet())
			requireThat(testId, "testId").isNotBlank();
	}

	/**
	 * Creates a proposal before judged reports are attached.
	 *
	 * @param contract the proposed contract
	 * @param derivedSources the prompt files that load although no harness event reports them
	 * @param scopeConditions the conditions that make certification unavailable
	 * @param conflicts the groups of requirements that no reply can satisfy together
	 * @param observedSources the non-blank sources reported loading by the contract author session
	 * @param derivation the contract author's non-blank output as JSON
	 * @param draft the directory that holds the prompt tests
	 * @param changes the status of each prompt test relative to those recorded in the project
	 * @param exampleChecks the mechanical results for the assertion examples
	 * @throws NullPointerException     if a required value is null
	 * @throws IllegalArgumentException if {@code observedSources} or {@code derivation} are blank
	 */
	public DerivedProposal(Contract contract, List<DerivedSource> derivedSources, List<ScopeCondition> scopeConditions,
		List<Conflict> conflicts, String observedSources, String derivation, Path draft, Map<String, DraftChange> changes,
		List<AssertionExampleCheck> exampleChecks)
	{
		this(contract, derivedSources, scopeConditions, conflicts, observedSources, derivation, draft, changes,
			exampleChecks, Map.of());
	}

	/**
	 * Returns this proposal with other derived sources.
	 *
	 * @param sources the prompt files that load although no harness event reports them
	 * @return the proposal
	 * @throws NullPointerException if {@code sources} is null or contains null
	 */
	public DerivedProposal withDerivedSources(List<DerivedSource> sources)
	{
		return new DerivedProposal(contract, sources, scopeConditions, conflicts, observedSources, derivation, draft,
			changes, exampleChecks, judgedReports);
	}

	/**
	 * Returns this proposal with its draft directory at another path and with the relation of each prompt test to the
	 * recorded prompt tests.
	 *
	 * @param draft the draft directory
	 * @param changes how each prompt test relates to the recorded prompt tests, by prompt test identifier
	 * @return the proposal
	 * @throws NullPointerException if any of the arguments are null
	 */
	public DerivedProposal withDraft(Path draft, Map<String, DraftChange> changes)
	{
		return new DerivedProposal(contract, derivedSources, scopeConditions, conflicts, observedSources, derivation,
			draft, changes, exampleChecks, judgedReports);
	}

	/**
	 * Returns this proposal with the recorded mechanical results for its assertion examples.
	 *
	 * @param checks the mechanical results
	 * @return the proposal
	 * @throws NullPointerException if the results or an element is null
	 */
	public DerivedProposal withExampleChecks(List<AssertionExampleCheck> checks)
	{
		return new DerivedProposal(contract, derivedSources, scopeConditions, conflicts, observedSources, derivation,
			draft, changes, checks, judgedReports);
	}

	/**
	 * Returns this proposal with a complete snapshot of its judged reports.
	 *
	 * @param reports the reports by test identifier
	 * @return the proposal
	 * @throws NullPointerException     if the reports or a key or value is null
	 * @throws IllegalArgumentException if a test identifier is blank
	 */
	public DerivedProposal withJudgedReports(Map<String, TestReport> reports)
	{
		return new DerivedProposal(contract, derivedSources, scopeConditions, conflicts, observedSources, derivation,
			draft, changes, exampleChecks, reports);
	}

	/**
	 * Replaces one test's report and retains the reports for the other tests.
	 *
	 * @param testId the reported test
	 * @param report the test's new report, which may be empty
	 * @return the proposal
	 * @throws NullPointerException     if either argument is null
	 * @throws IllegalArgumentException if the test identifier is blank
	 */
	public DerivedProposal withJudgedReport(String testId, TestReport report)
	{
		Map<String, TestReport> reports = new LinkedHashMap<>(judgedReports);
		reports.put(testId, report);
		return withJudgedReports(reports);
	}

	/**
	 * The outcomes and review findings that the proposal reports for one prompt test.
	 *
	 * @param examples the example labels and rationales
	 * @param findings the assertion review's findings
	 */
	public record TestReport(List<ExampleJudgment> examples, List<Finding> findings)
	{
		/**
		 * A report without example decisions or review findings.
		 */
		public static final TestReport EMPTY = new TestReport(List.of(), List.of());

		/**
		 * Creates a test's report.
		 *
		 * @param examples the example labels and rationales
		 * @param findings the assertion review's findings
		 * @throws NullPointerException     if a list or an element is null
		 * @throws IllegalArgumentException if two decisions name the same assertion and example
		 */
		public TestReport
		{
			examples = List.copyOf(examples);
			findings = List.copyOf(findings);
			Set<List<String>> identities = new HashSet<>();
			for (ExampleJudgment example : examples)
			{
				if (!identities.add(List.of(example.assertionId(), example.exampleId())))
				{
					throw new IllegalArgumentException("Duplicate judgment for assertion " + example.assertionId() +
						" and example " + example.exampleId());
				}
			}
		}
	}

	/**
	 * An example's decision as reported to the user.
	 *
	 * @param assertionId the assertion identifier
	 * @param exampleId the example identifier
	 * @param label the judge's verdict
	 * @param rationale the judge's reason for its verdict
	 */
	public record ExampleJudgment(String assertionId, String exampleId, Verdict label, String rationale)
	{
		/**
		 * Creates an example judgment.
		 *
		 * @param assertionId the assertion identifier
		 * @param exampleId the example identifier
		 * @param label the judge's verdict
		 * @param rationale the judge's reason for its verdict
		 * @throws NullPointerException     if an argument is null
		 * @throws IllegalArgumentException if an identifier or rationale is blank
		 */
		public ExampleJudgment
		{
			requireThat(assertionId, "assertionId").isNotBlank();
			requireThat(exampleId, "exampleId").isNotBlank();
			requireThat(label, "label").isNotNull();
			requireThat(rationale, "rationale").isNotBlank();
		}

		/**
		 * Returns the label and rationale for an example that the judge decided.
		 *
		 * @param result the judged example result
		 * @return the reporting summary
		 * @throws NullPointerException if {@code result} is null
		 */
		public static ExampleJudgment of(ExampleResult result)
		{
			requireThat(result, "result").isNotNull();
			AssertionExampleCheck check = result.example().check();
			return new ExampleJudgment(check.assertionId(), check.exampleId(), result.verdict().verdict(),
				result.verdict().rationale());
		}
	}
}
