package io.github.cowwoc.cat.common.optimizer.proposal;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.optimizer.campaign.Baseline;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CellTier;
import io.github.cowwoc.cat.common.optimizer.campaign.CellCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationProcedure;
import io.github.cowwoc.cat.common.optimizer.campaign.Development;
import io.github.cowwoc.cat.common.optimizer.campaign.CommitGate;
import io.github.cowwoc.cat.common.optimizer.contract.Assertion;
import io.github.cowwoc.cat.common.optimizer.contract.Cell;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.Dependent;
import io.github.cowwoc.cat.common.optimizer.contract.DerivedSource;
import io.github.cowwoc.cat.common.optimizer.contract.FixtureFile;
import io.github.cowwoc.cat.common.optimizer.contract.JudgeProfile;
import io.github.cowwoc.cat.common.optimizer.contract.MappedAssertion;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.PluginUnderTest;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.Split;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTest;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestFiles;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.contract.TestValue;
import io.github.cowwoc.cat.common.optimizer.contract.VariableDeclaration;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveredTest;
import io.github.cowwoc.cat.common.optimizer.execution.PilotResult;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileDelta;
import io.github.cowwoc.cat.common.optimizer.inventory.UnifiedDiff;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.report.CertificationStatus;
import io.github.cowwoc.cat.common.optimizer.report.EnumNames;
import io.github.cowwoc.cat.common.optimizer.report.RemainingWork;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;
import io.github.cowwoc.cat.common.optimizer.workspace.LinkReport;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Writes a {@link Proposal} as Markdown, listing its requirements, tests, measurement procedure and
 * confirmation conditions.
 */
public final class Markdown
{
	private static final MathContext PERCENT_DIGITS = new MathContext(3);
	/**
	 * The initial capacity of the text, which fits a proposal with a few tests without growing.
	 */
	private static final int INITIAL_CAPACITY = 16 * 1024;
	private final DerivedProposal derived;
	private final Contract contract;
	private final LinkReport links;
	private final Path controlFile;
	private final HostLogin login;
	private final int ceiling;
	private final Map<CertificationScope, Campaign> previews;
	private final List<String> caps;
	private final CertificationStatus incumbent;
	private final Map<String, PilotResult> pilots;
	private final Map<String, DeliveredTest> delivered;
	private final PluginCheck plugin;
	private final String goal;
	private final StringBuilder text = new StringBuilder(INITIAL_CAPACITY);

	/**
	 * Creates a writer.
	 *
	 * @param derived the contract author's proposal
	 * @param contract the contract that confirmation freezes
	 * @param links the symbolic links
	 * @param controlFile the target prompt file, which the control source empties, relative to the project directory
	 * @param login how the host's harness account is billed
	 * @param ceiling the campaign's concurrency ceiling
	 * @param previews the campaign as it would be after confirmation with each certification scope that the user
	 *   may choose, in choice order; empty while an inventory model is unranked
	 * @param caps the level and cap of each of the first attempts, as text
	 * @param incumbent the delivered certification of the target, or {@code null} if the target has none
	 * @param pilots the guard pilot of each prompt test that has one, by prompt test
	 * @param delivered each prompt test as delivery would write it, by prompt test
	 * @param plugin the check of the plugin under test
	 * @param goal what the user wants the target to achieve, or {@code null} if the user stated no goal
	 */
	Markdown(DerivedProposal derived, Contract contract, LinkReport links, Path controlFile,
		HostLogin login, int ceiling, Map<CertificationScope, Campaign> previews, List<String> caps,
		CertificationStatus incumbent, Map<String, PilotResult> pilots, Map<String, DeliveredTest> delivered,
		PluginCheck plugin, String goal)
	{
		this.derived = derived;
		this.contract = contract;
		this.links = links;
		this.controlFile = controlFile;
		this.login = login;
		this.ceiling = ceiling;
		this.previews = previews;
		this.caps = caps;
		this.incumbent = incumbent;
		this.pilots = pilots;
		this.delivered = delivered;
		this.plugin = plugin;
		this.goal = goal;
	}

	/**
	 * Returns a certification level as a fraction and a percentage.
	 *
	 * @param level the level
	 * @return text such as {@code 1/50 (2.00%)}
	 */
	static String level(SignificanceLevel level)
	{
		BigDecimal percent = BigDecimal.valueOf(100).divide(new BigDecimal(level.threshold()), PERCENT_DIGITS);
		// Show three significant digits, keeping trailing zeros
		percent = percent.setScale(Math.max(percent.scale(),
			percent.scale() + PERCENT_DIGITS.getPrecision() - percent.precision()));
		return "1/" + level.threshold() + " (" + percent.toPlainString() + "%)";
	}

	/**
	 * Returns the proposal.
	 *
	 * @return the Markdown text
	 */
	String render()
	{
		text.append("# Contract proposal\n");
		incumbent();
		requirements();
		loading();
		tests();
		trialProfiles();
		judge();
		controlSource();
		pluginUnderTest();
		guardPilot();
		fixtures();
		procedure();
		symbolicLinks();
		authentication();
		certificationScope();
		conflicts();
		scope();
		limitations();
		return text.toString();
	}

	/**
	 * Shows the target's delivered certification and whether it applies to what CAT knows now, if the target has one.
	 */
	private void incumbent()
	{
		if (incumbent == null)
			return;
		heading("Incumbent certification");
		text.append(incumbent.summary()).append('\n');
	}

	/**
	 * Lists the user's goal, then every requirement, its source, the condition under which it applies and the ways in
	 * which a reply can fail it. When the user stated a goal, a requirement that the goal does not state is marked as
	 * an extra requirement, which the user keeps or drops.
	 */
	private void requirements()
	{
		heading("Requirements");
		if (goal != null)
			text.append("Goal: ").append(goal).append("\n\n");
		for (Requirement requirement : contract.requirements())
		{
			text.append("- `").append(requirement.id()).append("`: ").append(requirement.description()).append('\n').
				append("  Source: ").append(requirement.source()).append('\n');
			if (goal != null && !requirement.source().equals(Requirement.GOAL))
			{
				text.append("  Extra requirement: the goal does not ask for it; keep it or drop it through revise " +
					"feedback.\n");
			}
			if (requirement.trigger() == null)
				text.append("  Applies: always\n");
			else
				text.append("  Applies when: ").append(requirement.trigger()).append('\n');
			text.append("  Failure behaviors: ").append(String.join(", ", requirement.behaviors())).append('\n');
		}
	}

	/**
	 * Lists the prompt loading that the contract author session observed and the loading that was derived.
	 */
	private void loading()
	{
		heading("Prompt loading");
		text.append("Observed in the contract author session's harness events:\n\n");
		for (String line : derived.observedSources().lines().toList())
			text.append("- ").append(line).append('\n');
		text.append("\nDerived from documented loading rules:\n\n");
		if (derived.derivedSources().isEmpty())
			text.append("- none\n");
		for (DerivedSource source : derived.derivedSources())
			text.append("- ").append(source.path()).append(": ").append(source.rule()).append('\n');
		text.append('\n').append(Proposal.DERIVED_LOADING_LIMIT).append('\n');
	}

	/**
	 * Lists every prompt test.
	 */
	private void tests()
	{
		heading("Prompt tests");
		text.append("Each prompt test is certified on its own, in its own cells. This is a balanced benchmark: a " +
			"trial draws each test case of its part with equal probability. These weights are testing assumptions, " +
			"not usage frequencies; actual future usage frequencies are unknown.\n\n" +
			"Each distinct expected outcome of a test belongs to the development part or the held-out part, each " +
			"with probability 1/2, under a random value that confirmation freezes. Test cases whose expected " +
			"outcomes differ only in case or spacing share a part, and test cases that an earlier campaign " +
			"committed belong to the development part. Development trials and every session that edits the " +
			"prompts use development test cases only; formal trials and the incumbent's baseline draw held-out " +
			"test cases only. Confirmation needs at least " + Proposal.MINIMUM_OUTCOMES + " distinct expected " +
			"outcomes in each part of every test.\n");
		for (PromptTest test : contract.tests())
			test(test);
	}

	/**
	 * Lists the trial profiles: the inventory and its source, the bindings, the restriction and each test's cells.
	 */
	private void trialProfiles()
	{
		heading("Trial profiles");
		text.append("A trial profile records model and effort choices; an unspecified choice uses the harness default. " +
			"A cell pairs a test with one allowed trial " +
			"profile. A file's binding selects a model, an effort, or both. CAT computes each test's profiles " +
			"from the bindings of its requirements' source files. When those " +
			"files share a complete profile, CAT uses it as written. Otherwise, CAT selects inventory profiles " +
			"matching their shared bound dimensions, or uses those bindings as written if none match. Every cell " +
			"needs its own commit " +
			"gate, baseline and certification trials. Confirmation freezes the profile set. In revision feedback, " +
			"you may restrict it to part of the " +
			"inventory.\n\n");
		for (String line : contract.profiles().describe())
			text.append(line).append('\n');
	}

	/**
	 * Lists one prompt test.
	 *
	 * @param test the prompt test
	 */
	private void test(PromptTest test)
	{
		text.append("\n### Test `").append(test.id()).append("`\n\n").
			append("- Risk rank: ").append(test.riskRank().position()).append(" (1 is the riskiest)\n");
		requirements(test);
		if (!test.absentTriggers().isEmpty())
		{
			text.append("- Leaves these triggers absent, so the obligated action must not occur: ").
				append(test.absentTriggers().stream().map(id -> "`" + id + "`").collect(Collectors.joining(", "))).
				append('\n');
		}
		text.append("- Applies when: ").append(test.applicability()).append('\n').
			append("- Time limit: ").append(test.timeLimit().toSeconds()).append(" seconds\n").
			append("- Turns:\n");
		for (int i = 0; i < test.turns().size(); ++i)
			text.append("  ").append(i + 1).append(". ").append(test.turns().get(i)).append('\n');
		text.append("- Expected behaviour: ").append(test.expectedBehaviour()).append('\n');
		scenario(test);
		if (test.variables().isEmpty())
			text.append("- Variables: none\n");
		else
		{
			text.append("- Variables:\n");
			for (VariableDeclaration variable : test.variables())
				text.append("  - `").append(variable.name()).append("`: ").append(variable.description()).append('\n');
		}
		fixtures(test);
		testCases(test);
		if (test.uncoveredInteractions().isEmpty())
			text.append("- Interactions this test cannot cover: none\n");
		else
		{
			text.append("- Interactions this test cannot cover:\n");
			for (String interaction : test.uncoveredInteractions())
				text.append("  - ").append(interaction).append('\n');
		}
	}

	/**
	 * Shows how a test's {@value PromptTestFiles#SCENARIO} arises: written by the contract author, or derived from each
	 * recorded regression scenario that the test cites, with any change shown as a difference from the recorded file.
	 *
	 * @param test the prompt test
	 */
	private void scenario(PromptTest test)
	{
		DeliveredTest deliveredTest = delivered.get(test.id());
		Path file = deliveredTest.location().directory().resolve(PromptTestFiles.SCENARIO);
		if (test.regressionScenarios().isEmpty())
		{
			text.append("- Scenario: written by the contract author; delivery creates `").append(file).append("`\n");
			return;
		}
		for (String citation : test.regressionScenarios())
		{
			String recorded = derived.citedScenarios().get(citation);
			if (recorded == null)
			{
				text.append("- Scenario: converted from the recorded scenario `").append(citation).append("`\n");
				continue;
			}
			boolean edited = !deliveredTest.location().created();
			String recordedFile = null;
			if (edited)
				recordedFile = recorded;
			String written = deliveredTest.scenarioFile(recordedFile, List.of());
			if (written.equals(recorded))
			{
				text.append("- Scenario: recorded at `").append(citation).append("`, unchanged\n");
				continue;
			}
			if (edited)
			{
				text.append("- Scenario: recorded at `").append(citation).append("` and edited; delivery rewrites it " +
					"with these changes:\n");
			}
			else
			{
				text.append("- Scenario: converted from the recorded scenario `").append(citation).
					append("`; delivery creates `").append(file).append("`, which differs from it by:\n");
			}
			String diff = UnifiedDiff.of(List.of(new FileDelta(file, new FileContent(recorded.getBytes(UTF_8)),
				new FileContent(written.getBytes(UTF_8)), Set.of())));
			String fence = "`".repeat(Math.max(3, longestBacktickRun(diff) + 1));
			text.append("\n    ").append(fence).append("diff\n");
			for (String line : diff.substring(0, diff.length() - 1).split("\n", -1))
				text.append("    ").append(line).append('\n');
			text.append("    ").append(fence).append("\n\n");
		}
	}

	/**
	 * Shows the files that a test writes into each trial's workspace: one that the contract author wrote with its
	 * content, and one that CAT copies from a cited scenario by the file that it is copied from.
	 *
	 * @param test the prompt test
	 */
	private void fixtures(PromptTest test)
	{
		if (test.fixtures().isEmpty())
		{
			text.append("- Fixtures: none\n");
			return;
		}
		text.append("- Fixtures, written into each trial's workspace with each test case's values:\n");
		for (FixtureFile fixture : test.fixtures())
		{
			if (!fixture.source().isEmpty())
			{
				text.append("  - `").append(fixture.path()).append("`, copied from `").append(fixture.source()).
					append("`\n");
				continue;
			}
			String fence = "`".repeat(Math.max(3, longestBacktickRun(fixture.content()) + 1));
			text.append("  - `").append(fixture.path()).append("`:\n\n    ").append(fence).append('\n');
			String content = fixture.content();
			if (content.endsWith("\n"))
				content = content.substring(0, content.length() - 1);
			for (String line : content.split("\n", -1))
				text.append("    ").append(line).append('\n');
			text.append("    ").append(fence).append("\n\n");
		}
	}

	/**
	 * Returns a test case's value as the proposal shows it.
	 *
	 * @param value the value
	 * @return the quoted text of a text value, or the call of a generated value
	 */
	private static String value(TestValue value)
	{
		return switch (value)
		{
			case TestValue.Text text -> quote(text.text());
			case TestValue.Generated generated -> generated.describe();
		};
	}

	/**
	 * Returns the length of the longest run of backticks in text.
	 *
	 * @param value the text
	 * @return the number of backticks in the longest run, or {@code 0} if there are none
	 */
	private static int longestBacktickRun(String value)
	{
		int longest = 0;
		int run = 0;
		for (int i = 0; i < value.length(); ++i)
		{
			if (value.charAt(i) == '`')
			{
				++run;
				longest = Math.max(longest, run);
			}
			else
				run = 0;
		}
		return longest;
	}

	/**
	 * Lists a test's test cases with the part of each, and the number of distinct expected outcomes in each part.
	 *
	 * @param test the prompt test
	 */
	private void testCases(PromptTest test)
	{
		Split split = contract.split();
		if (test.allTestCases().isEmpty())
			text.append("- Test cases: none\n");
		else
		{
			text.append("- Test cases:\n");
			for (TestCase testCase : test.allTestCases())
			{
				String values = testCase.values().entrySet().stream().
					map(value -> "`" + value.getKey() + "` = " + value(value.getValue())).
					collect(Collectors.joining(", "));
				text.append("  - ").append(values).append("; expected outcome ").
					append(quote(testCase.expectedOutcome())).append("; ").
					append(split.partOf(test, testCase)).append(" part");
				if (test.committedTestCases().contains(testCase))
					text.append(", committed");
				text.append('\n');
			}
		}
		int development = split.outcomeCount(test, Part.DEVELOPMENT);
		int heldOut = split.outcomeCount(test, Part.HELD_OUT);
		text.append("- Distinct expected outcomes: ").append(development).append(" in the development part, ").
			append(heldOut).append(" in the held-out part\n");
		if (development < Proposal.MINIMUM_OUTCOMES || heldOut < Proposal.MINIMUM_OUTCOMES)
		{
			text.append("  Confirmation is refused until each part holds at least ").
				append(Proposal.MINIMUM_OUTCOMES).
				append(". Ask for test cases with more expected outcomes in revision feedback.\n");
		}
	}

	/**
	 * Lists a test's requirements and its assertions: each declared one with its kind, the requirement it checks and
	 * what decides it, the built-in tool, the judge or both, then the assertion that CAT adds.
	 *
	 * @param test the prompt test
	 */
	private void requirements(PromptTest test)
	{
		text.append("- Requirements: ").
			append(test.requirementIds().stream().map(id -> "`" + id + "`").collect(Collectors.joining(", "))).
			append('\n').
			append("- Assertions, each of which a trial must pass:\n");
		for (MappedAssertion mapped : test.assertions())
		{
			Assertion assertion = mapped.assertion();
			text.append("  - `").append(assertion.id()).append("` (").append(EnumNames.lowerCase(assertion.kind())).
				append(", requirement `").append(mapped.requirementId()).append("`): ").
				append(assertion.statement()).append(' ');
			decider(assertion);
		}
		Assertion expectedOutcome = test.expectedOutcome();
		text.append("  - `").append(expectedOutcome.id()).append("` (").
			append(EnumNames.lowerCase(expectedOutcome.kind())).append(", added by CAT): ").
			append(expectedOutcome.statement()).append(' ');
		decider(expectedOutcome);
	}

	/**
	 * Ends an assertion's line with what decides it.
	 *
	 * @param assertion the assertion
	 */
	private void decider(Assertion assertion)
	{
		if (assertion.tool() == null)
		{
			text.append("The judge decides it.\n");
			return;
		}
		text.append("The built-in tool `").append(assertion.tool());
		switch (assertion.onToolFailure())
		{
			case FAIL -> text.append("` decides it; a failure is the behavior `").
				append(assertion.builtInTool().orElseThrow().behavior()).append("`.\n");
			case JUDGE -> text.append("` decides it when it passes; when it fails, the judge decides it.\n");
		}
	}

	/**
	 * Quotes text on one line.
	 *
	 * @param value the text
	 * @return the text in double quotes, with backslashes, double quotes and line breaks escaped
	 */
	private static String quote(String value)
	{
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").
			replace("\n", "\\n") + "\"";
	}

	/**
	 * Shows the judge profile.
	 */
	private void judge()
	{
		heading("Judge");
		JudgeProfile judge = contract.judge();
		text.append("- Role profile: ").append(judge.profile().label()).
			append(" (used by the contract author, judge and " + "prompt author sessions)\n").
			append("- Prompt: CAT's judge prompt, which states the judge's authority and output rules; the prompt " +
				"system cannot change it\n").
			append("- Rubric: ").append(judge.rubric()).append('\n').
			append("- Decision rule: ").append(judge.decisionRule()).append('\n').
			append("- Result schema: `").append(judge.resultSchema()).append("`\n");
	}

	/**
	 * Lists the ignored paths that trials need.
	 */
	private void fixtures()
	{
		heading("Trial fixtures");
		text.append("Ignored paths that are copied into every trial:\n\n");
		if (contract.fixtures().ignoredPaths().isEmpty())
			text.append("- none\n");
		for (String path : contract.fixtures().ignoredPaths())
			text.append("- ").append(path).append('\n');
	}

	/**
	 * States the statistical procedure.
	 */
	private void procedure()
	{
		heading("Statistical procedure");
		BigDecimal minimumPassRate = contract.minimumPassRate();
		String minimumRate = percent(minimumPassRate);
		int batchFailureLimit = CommitGate.batchFailureLimit(minimumPassRate);
		String batchFailures = "failures";
		if (batchFailureLimit == 1)
			batchFailures = "failure";
		text.append("- Joint error budget: 0.05, split into 0.04 for certification, 0.005 for improvement claims " +
			"and 0.005 for the incumbent's bounds. Certification attempt i uses α_i = 0.04/(i(i + 1)).\n").
			append("- Certification: every cell must show a pass rate above ").append(minimumRate).
			append(". Bets: ").append(CertificationProcedure.BETS).append(".\n").
			append("- Caps of the first attempts, in formal trials per cell:\n");
		for (String cap : caps)
			text.append("  - ").append(cap).append('\n');
		text.append("- Commit gate: a cell meets the commit gate after at least ").
			append(CommitGate.MINIMUM_TRIALS).
			append(" representative development trials with at least ").
			append(percent(CommitGate.minimumObservedPassRate(minimumPassRate))).
			append(" observed passes, at most ").append(batchFailureLimit).append(' ').append(batchFailures).
			append(" in each of the latest two complete batches of ").
			append(CellCounts.BATCH_SIZE).
			append(" trials, and no unresolved reproducible defect or known regression.\n").
			append("- Review checkpoint: at ").append(Development.DEFAULT_REVIEW_POINT).
			append(" representative trials in a cell for an unchanged candidate with the cell still " +
				"unresolved, CAT stops scheduling that cell and asks you how to continue.\n").
			append("- Baseline targets: the incumbent runs ").append(Baseline.TARGET).
			append(" representative trials in every cell, and ").
			append(Baseline.EXTENDED_TARGET - Baseline.TARGET).
			append(" more in a cell whose observed rate is below ").append(minimumRate).
			append(" but not yet proven deficient, ending at ").append(Baseline.EXTENDED_TARGET).
			append(". A commit requires these targets.\n");
	}

	/**
	 * Formats a rate as a percentage.
	 *
	 * @param rate a rate between 0 and 1
	 * @return the percentage, without trailing zeros
	 */
	private static String percent(BigDecimal rate)
	{
		return rate.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
	}

	/**
	 * Shows what the control source changes: the target prompt file that it empties and each dependent that it
	 * removes.
	 */
	private void controlSource()
	{
		heading("Control source");
		text.append("Control trials run the incumbent with the target prompt file `").append(controlFile).
			append("` emptied to zero bytes");
		List<Dependent> dependents = contract.dependents();
		if (dependents.isEmpty())
		{
			text.append(". The contract lists no dependents of the target, so every other part of the prompt system " +
				"stays as the incumbent has it.\n");
			return;
		}
		text.append(" and these dependents of the target removed. Every other part of the prompt system stays as the " +
			"incumbent has it.\n\n");
		for (Dependent dependent : dependents)
		{
			text.append("- ");
			switch (dependent.location())
			{
				case PROJECT -> text.append("The project file `");
				case PLUGIN -> text.append("The plugin file `");
			}
			text.append(dependent.path()).append("`: ");
			switch (dependent.removal())
			{
				case Dependent.WholeFile _ -> text.append("deleted");
				case Dependent.Span _ -> text.append("the text below deleted");
				case Dependent.HookRegistration hook -> text.append("its ").append(hook.event()).
					append(" hook `").append(hook.command()).append("` unregistered");
			}
			text.append(". Serves: ").append(dependent.serves()).append('\n');
			if (dependent.removal() instanceof Dependent.Span span)
			{
				String fence = "`".repeat(Math.max(3, longestBacktickRun(span.text()) + 1));
				text.append("\n  ").append(fence).append('\n');
				String content = span.text();
				if (content.endsWith("\n"))
					content = content.substring(0, content.length() - 1);
				for (String line : content.split("\n", -1))
					text.append("  ").append(line).append('\n');
				text.append("  ").append(fence).append("\n\n");
			}
		}
	}

	/**
	 * Shows the plugin under test, if the contract has one: the plugin that each trial installs, each mapped file
	 * whose content differs from the base's, and whether trials install CAT, with the contract author's evidence.
	 */
	private void pluginUnderTest()
	{
		if (plugin.plugin().isEmpty())
			return;
		heading("Plugin under test");
		PluginUnderTest underTest = plugin.plugin().get();
		switch (underTest)
		{
			case PluginUnderTest.Root root -> text.append("Each trial installs the plugin root `").
				append(root.directory()).append("` of its staged source.\n");
			case PluginUnderTest.Built built ->
			{
				text.append("Each trial installs a copy of the built plugin `").append(built.base()).
					append("` with these directories of its staged source written over it:\n\n");
				for (PluginUnderTest.Mapping mapping : built.mappings())
				{
					text.append("- `").append(mapping.source()).append("` → `").append(mapping.target()).
						append("`\n");
				}
			}
		}
		if (!plugin.differing().isEmpty())
		{
			text.append("\nWarning: the content of these mapped files differs from the base's, because the base " +
				"predates them or the build changes them. Trials load the project's content:\n\n");
			for (Path path : plugin.differing())
				text.append("- `").append(path).append("`\n");
		}
		if (underTest.dependsOnCat())
			text.append("\nTrials install CAT: ");
		else
			text.append("\nTrials do not install CAT: ");
		text.append(underTest.catEvidence()).append('\n');
	}

	/**
	 * Shows how often each assertion passed in the guard pilot's control trials, or why none ran.
	 */
	private void guardPilot()
	{
		heading("Guard pilot");
		if (plugin.refusals().isEmpty())
			text.append(PilotReport.render(contract, pilots));
		else
		{
			text.append("CAT ran no control trials, because trials cannot install a plugin under test that " +
				"confirmation refuses. Once a revision corrects the plugin under test, CAT runs them.\n");
		}
	}

	/**
	 * Lists every symbolic link.
	 */
	private void symbolicLinks()
	{
		heading("Symbolic links");
		if (links.entries().isEmpty())
		{
			text.append("No symbolic link was found in the prompt system or the staged fixtures.\n");
			return;
		}
		for (LinkReport.Entry entry : links.entries())
		{
			text.append("- `").append(entry.link()).append('`');
			switch (entry.status())
			{
				case CYCLE -> text.append(": the links form a cycle; this blocks confirmation");
				case MISSING_TARGET -> text.append(" -> `").append(entry.target().orElseThrow()).
					append("`: the target is missing; this blocks confirmation");
				case RESOLVED ->
				{
					text.append(" -> `").append(entry.target().orElseThrow()).append("`: ");
					if (entry.isInsideProject())
						text.append("inside the project; ");
					else
						text.append("outside the project; ");
					if (entry.editLimit().isEmpty())
						text.append("its content may be edited");
					else
						text.append(EnumNames.lowerCase(entry.editLimit().get()).replace('_', ' '));
				}
			}
			text.append('\n');
		}
		text.append("\nStaging consequence: ").append(LinkReport.STAGING_CONSEQUENCE).append('\n');
	}

	/**
	 * Shows how the harness signs in and the volume safeguards.
	 */
	private void authentication()
	{
		heading("Authentication and volume");
		Integer sessions = null;
		for (Campaign preview : previews.values())
		{
			int needed = 2 * RemainingWork.of(preview).total();
			if (sessions == null || needed < sessions)
				sessions = needed;
		}
		switch (login)
		{
			case SUBSCRIPTION -> volumeWarning("the harness signs in with a subscription login rather than an API key.",
				sessions);
			case UNKNOWN -> volumeWarning("CAT could not tell whether the harness signs in with a subscription login " +
				"or an API key.", sessions);
			case API_KEY -> text.append("The harness signs in with an API key.\n");
		}
		text.append("\nAt most ").append(ceiling).append(" nested sessions run at once, including " +
			"judges and prompt authors, within the harness capacity that every CAT process shares. Concurrency " +
			"starts at 1 and grows gradually.\n");
	}

	/**
	 * Warns that the campaign's volume may be flagged on a consumer subscription.
	 *
	 * @param reason why the warning applies, as a sentence
	 * @param sessions the fewest nested sessions that any certification scope choice needs, or {@code null} while
	 *   no choice is available
	 */
	private void volumeWarning(String reason, Integer sessions)
	{
		text.append("Warning: ").append(reason);
		if (sessions == null)
			text.append(" This campaign needs many nested sessions");
		else
			text.append(" This campaign needs at least ").append(sessions).append(" nested sessions");
		text.append(", and a provider may flag automated volume on a consumer subscription. Use an API key for " +
			"this campaign.\n");
	}

	/**
	 * Shows the capability ranking's outcome and each certification scope choice: its cells, each test's weakest
	 * cell and its minimum session count.
	 */
	private void certificationScope()
	{
		heading("Certification scope");
		text.append("Confirm with `confirm <campaign> <choice>`, choosing the cells that certification covers. " +
			"Development always runs in each test's weakest cell, the lowest effort of the least capable model " +
			"that the choice covers, and in every cell where a certification attempt fails. The choice sets the " +
			"cells that the incumbent baseline and every certification attempt run in, and the cells that the " +
			"claim covers. A test with one cell is certified in that cell under every choice. To certify " +
			"other cells, describe them in revision feedback; the next proposal shows them as the `described` " +
			"choice.\n");
		if (previews.isEmpty())
		{
			text.append("\nNo choice is available yet: the capability ranking (").
				append(contract.profiles().ranking().source()).append(") does not rank the family of the " +
					"inventory models ").append(String.join(", ", contract.profiles().unranked())).
				append(". Rank them in revision feedback; confirmation is refused until then.\n");
			return;
		}
		for (Map.Entry<CertificationScope, Campaign> entry : previews.entrySet())
			scopeChoice(entry.getKey(), entry.getValue());
	}

	/**
	 * Shows one certification scope choice.
	 *
	 * @param scope the choice
	 * @param preview the campaign as it would be after confirmation with the choice
	 */
	private void scopeChoice(CertificationScope scope, Campaign preview)
	{
		Contract scoped = preview.contract();
		text.append("\n### `").append(scope.id()).append("`: ").append(scope.summary()).append("\n\n").
			append("Cells: ").append(describeCells(CellTier.scopeCells(scoped))).append('\n');
		List<String> weakest = new ArrayList<>();
		for (PromptTest test : scoped.tests())
			weakest.add("`" + test.id() + "`: `" + CellTier.weakestCell(scoped, test.id()).id() + "`");
		text.append("Weakest cells: ").append(String.join("; ", weakest)).append("\n\n");
		RemainingWork remaining = RemainingWork.of(preview);
		int trials = remaining.total();
		text.append("At least ").append(2 * trials).append(" nested sessions remain if every trial passes: ").
			append(trials).append(" trials, each with one trial session and one judge session. Failures add " +
				"reruns, prompt author sessions and promotion runs.\n\n").
			append("Minimum trials by cell:\n\n");
		Map<String, List<Cell>> byTrials = new LinkedHashMap<>();
		for (Cell cell : CellTier.scopeCells(scoped))
		{
			List<String> parts = new ArrayList<>();
			for (RemainingWork.Entry entry : remaining.entries())
			{
				if (entry.cell().equals(cell.id()))
				{
					parts.add(EnumNames.lowerCase(entry.kind()).replace('_', ' ') + " " +
						entry.trials());
				}
			}
			byTrials.computeIfAbsent(String.join(", ", parts), _ -> new ArrayList<>()).add(cell);
		}
		for (Map.Entry<String, List<Cell>> entry : byTrials.entrySet())
			text.append("- ").append(describeCells(entry.getValue())).append(": ").append(entry.getKey()).append('\n');
	}

	/**
	 * Names cells compactly: a test with several cells, all of them present, as every cell of the test, and
	 * each other cell by its identifier, with the cell count when there is more than one cell.
	 *
	 * @param group the cells
	 * @return the description
	 */
	private String describeCells(List<Cell> group)
	{
		Map<String, Integer> cellsByTest = new HashMap<>();
		for (Cell cell : contract.cells())
			cellsByTest.merge(cell.testId(), 1, Integer::sum);
		Map<String, List<Cell>> groupByTest = new LinkedHashMap<>();
		for (Cell cell : group)
			groupByTest.computeIfAbsent(cell.testId(), _ -> new ArrayList<>()).add(cell);
		List<String> whole = new ArrayList<>();
		List<String> single = new ArrayList<>();
		for (Map.Entry<String, List<Cell>> entry : groupByTest.entrySet())
		{
			int size = entry.getValue().size();
			if (size > 1 && size == cellsByTest.get(entry.getKey()))
				whole.add("`" + entry.getKey() + "`");
			else
			{
				for (Cell cell : entry.getValue())
					single.add("`" + cell.id() + "`");
			}
		}
		List<String> names = new ArrayList<>();
		if (whole.size() == 1)
			names.add("every cell of test " + whole.getFirst());
		else if (whole.size() > 1)
			names.add("every cell of tests " + String.join(", ", whole));
		names.addAll(single);
		String description = String.join(", ", names);
		if (group.size() > 1)
			description += " (" + group.size() + " cells)";
		return description;
	}

	/**
	 * Lists every group of requirements that no reply can satisfy together.
	 */
	private void conflicts()
	{
		heading("Conflicts");
		if (derived.conflicts().isEmpty())
		{
			text.append("The contract author found no requirements that conflict.\n");
			return;
		}
		text.append("No reply can satisfy each group of requirements below together. Confirmation is refused " +
			"until your feedback resolves each conflict, for example with one of the resolutions listed under it:\n\n");
		for (Conflict conflict : derived.conflicts())
		{
			text.append("- `").append(String.join("`, `", conflict.requirementIds())).append("`: ").
				append(conflict.evidence()).append('\n');
			for (int i = 0; i < conflict.resolutions().size(); ++i)
			{
				text.append("  ").append(i + 1).append(". ").append(conflict.resolutions().get(i));
				if (i + 1 == conflict.recommended())
					text.append(" (recommended: ").append(conflict.reason()).append(')');
				text.append('\n');
			}
		}
	}

	/**
	 * Lists every condition that makes certification unavailable.
	 */
	private void scope()
	{
		heading("Certifiable scope");
		if (derived.scopeConditions().isEmpty())
		{
			text.append("The contract author found no condition that makes certification unavailable.\n");
			return;
		}
		text.append("Confirming records these outcomes, and no certification attempt can commit while they hold:\n\n");
		for (ScopeCondition condition : derived.scopeConditions())
		{
			text.append("- ").append(condition.outcome().label()).append(" (requirements ").
				append(String.join(", ", condition.requirementIds())).append("): ").append(condition.evidence()).
				append('\n');
		}
	}

	/**
	 * Lists every known limitation.
	 */
	private void limitations()
	{
		heading("Known limitations");
		for (String limitation : contract.limitations())
			text.append("- ").append(limitation).append('\n');
	}

	/**
	 * Starts a section.
	 *
	 * @param title the section title
	 */
	private void heading(String title)
	{
		text.append("\n## ").append(title).append("\n\n");
	}
}
