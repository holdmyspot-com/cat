package io.github.cowwoc.cat.common.optimizer.proposal;

import io.github.cowwoc.cat.common.optimizer.campaign.Baseline;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CellTier;
import io.github.cowwoc.cat.common.optimizer.campaign.CellCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationProcedure;
import io.github.cowwoc.cat.common.optimizer.campaign.Development;
import io.github.cowwoc.cat.common.optimizer.campaign.ReadinessGate;
import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.Cell;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.ControlEvidence;
import io.github.cowwoc.cat.common.optimizer.contract.DeterministicCheck;
import io.github.cowwoc.cat.common.optimizer.contract.DiscardedCheck;
import io.github.cowwoc.cat.common.optimizer.contract.EvidenceFile;
import io.github.cowwoc.cat.common.optimizer.contract.JudgeProfile;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.Split;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
import io.github.cowwoc.cat.common.optimizer.contract.ToolCall;
import io.github.cowwoc.cat.common.optimizer.contract.VariationRule;
import io.github.cowwoc.cat.common.optimizer.contract.VariationValue;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.report.EnumNames;
import io.github.cowwoc.cat.common.optimizer.report.RemainingWork;
import io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;
import io.github.cowwoc.cat.common.optimizer.workspace.LinkReport;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writes a {@link Proposal} as Markdown, listing its requirements, tests, measurement procedure and
 * confirmation conditions.
 */
final class Markdown
{
	private static final MathContext PERCENT_DIGITS = new MathContext(3);
	/**
	 * The initial capacity of the text, which fits a proposal with a few categories without growing.
	 */
	private static final int INITIAL_CAPACITY = 16 * 1024;
	private final DerivedProposal derived;
	private final Contract contract;
	private final LinkReport links;
	private final HostLogin login;
	private final int ceiling;
	private final Map<CertificationScope, Campaign> previews;
	private final List<String> caps;
	private final StringBuilder text = new StringBuilder(INITIAL_CAPACITY);

	/**
	 * Creates a writer.
	 *
	 * @param derived the deriver's proposal
	 * @param contract the contract that confirmation freezes
	 * @param links the symbolic links
	 * @param login how the host's harness account is billed
	 * @param ceiling the campaign's concurrency ceiling
	 * @param previews the campaign as it would be after confirmation with each certification scope that the user
	 *   may choose, in choice order; empty while an inventory model is unranked
	 * @param caps the level and cap of each of the first attempts, as text
	 */
	Markdown(DerivedProposal derived, Contract contract, LinkReport links, HostLogin login, int ceiling,
		Map<CertificationScope, Campaign> previews, List<String> caps)
	{
		this.derived = derived;
		this.contract = contract;
		this.links = links;
		this.login = login;
		this.ceiling = ceiling;
		this.previews = previews;
		this.caps = caps;
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
		requirements();
		loading();
		categories();
		trialProfiles();
		judge();
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
	 * Lists every requirement, its source, the condition under which it applies and the ways in which a reply can fail
	 * it.
	 */
	private void requirements()
	{
		heading("Requirements");
		for (Requirement requirement : contract.requirements())
		{
			text.append("- `").append(requirement.id()).append("`: ").append(requirement.description()).append('\n').
				append("  Source: ").append(requirement.source()).append('\n');
			if (requirement.trigger() == null)
				text.append("  Applies: always\n");
			else
				text.append("  Applies when: ").append(requirement.trigger()).append('\n');
			text.append("  Failure behaviors: ").append(String.join(", ", requirement.behaviors())).append('\n');
		}
	}

	/**
	 * Lists the prompt loading that the deriver session observed and the loading that was derived.
	 */
	private void loading()
	{
		heading("Prompt loading");
		text.append("Observed in the deriver session's harness events:\n\n");
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
	 * Lists every category and test definition.
	 */
	private void categories()
	{
		heading("Categories and tests");
		text.append("This is a balanced benchmark: every test in a category has equal weight, and the variation " +
			"probabilities are chosen for coverage. They are testing assumptions, not usage frequencies; actual " +
			"future usage frequencies are unknown.\n\n" +
			"A test's key dimensions carry its content. Each combination of their values belongs to the development " +
			"part or the held-out part, each with probability 1/2, under a random value that confirmation freezes. " +
			"Development trials and every session that edits the prompts use development instances only; formal " +
			"trials and the incumbent's baseline draw held-out instances only. Confirmation needs at least 2 " +
			"key-value combinations in each part of every test.\n");
		for (Category category : contract.categories())
		{
			text.append("\n### Category `").append(category.id()).append("`\n\n").
				append("Risk rank: ").append(category.riskRank().position()).append(" (1 is the riskiest)\n").
				append("Why this category is measured separately: ").append(category.rationale()).append('\n');
			for (TestDefinition test : category.tests())
				test(test, category.tests().size());
		}
	}

	/**
	 * Lists the trial profiles: the inventory and its source, the bindings, the restriction and each category's cells.
	 */
	private void trialProfiles()
	{
		heading("Trial profiles");
		text.append("A trial profile records model and effort choices; an unspecified choice uses the harness default. " +
			"A cell pairs a category with one allowed trial " +
			"profile. A file's binding selects a model, an effort, or both. CAT computes each category's profiles " +
			"from the bindings of its requirements' source files, excluding CAT's core-task requirement. When those " +
			"files share a complete profile, CAT uses it as written. Otherwise, CAT selects inventory profiles " +
			"matching their shared bound dimensions, or uses those bindings as written if none match. Every cell " +
			"needs its own readiness " +
			"gate, baseline and certification trials. Confirmation freezes the profile set. In revision feedback, " +
			"you may restrict it to part of the " +
			"inventory.\n\n");
		for (String line : contract.profiles().describe())
			text.append(line).append('\n');
	}

	/**
	 * Lists one test definition.
	 *
	 * @param test the test definition
	 * @param tests the number of tests in its category
	 */
	private void test(TestDefinition test, int tests)
	{
		text.append("\n#### Test `").append(test.id()).append("`\n\n");
		requirements(test);
		if (!test.absentTriggers().isEmpty())
		{
			text.append("- Leaves these triggers absent, so the obligated action must not occur: ").
				append(test.absentTriggers().stream().map(id -> "`" + id + "`").collect(Collectors.joining(", "))).
				append('\n');
		}
		text.append("- Applies when: ").append(test.applicability()).append('\n').
			append("- Weight within the category: 1/").append(tests).append('\n').
			append("- Time limit: ").append(test.timeLimit().toSeconds()).append(" seconds\n").
			append("- Turns:\n");
		for (int i = 0; i < test.turns().size(); ++i)
			text.append("  ").append(i + 1).append(". ").append(test.turns().get(i)).append('\n');
		text.append("- Expected behaviour: ").append(test.expectedBehaviour()).append('\n');
		if (!test.regressionScenarios().isEmpty())
		{
			text.append("- Recorded regression scenarios:\n");
			for (String citation : test.regressionScenarios())
				text.append("  - ").append(citation).append('\n');
		}
		if (test.variations().isEmpty())
			text.append("- Variations: none\n");
		else
		{
			text.append("- Variations:\n");
			for (VariationRule rule : test.variations())
			{
				text.append("  - `").append(rule.dimension()).append("`:\n");
				for (VariationValue value : rule.values())
				{
					text.append("    - `").append(value.label()).append("`: \"").append(value.text()).
						append("\", probability ").
						append(BigDecimal.valueOf(value.probability()).stripTrailingZeros().toPlainString()).
						append('\n');
				}
			}
		}
		keyDimensions(test);
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
	 * Lists a test's key dimensions and the number of its key-value combinations in each part.
	 *
	 * @param test the test definition
	 */
	private void keyDimensions(TestDefinition test)
	{
		String names = test.keyRules().stream().map(rule -> "`" + rule.dimension() + "`").
			collect(Collectors.joining(", "));
		text.append("- Key dimensions: ");
		if (test.keyDimensions().isEmpty())
		{
			if (names.isEmpty())
				text.append("none, so the test has one key-value combination");
			else
				text.append("every dimension (").append(names).append(')');
		}
		else
			text.append(names);
		Split split = contract.split();
		int development = split.combinations(test, Part.DEVELOPMENT).size();
		int heldOut = split.combinations(test, Part.HELD_OUT).size();
		text.append("\n- Key-value combinations: ").append(development).append(" in the development part, ").
			append(heldOut).append(" in the held-out part\n");
		if (development < Proposal.MINIMUM_COMBINATIONS || heldOut < Proposal.MINIMUM_COMBINATIONS)
		{
			text.append("  Confirmation is refused until each part holds at least ").
				append(Proposal.MINIMUM_COMBINATIONS).append(". Ask for more key values in revision feedback.\n");
		}
	}

	/**
	 * Marks each requirement of a test as deterministic, with its check and controls, or semantic, and lists the
	 * checks that CAT discarded with the reason.
	 *
	 * @param test the test definition
	 */
	private void requirements(TestDefinition test)
	{
		Map<String, DeterministicCheck> checkByRequirement = new HashMap<>();
		for (DeterministicCheck check : test.checks())
			checkByRequirement.put(check.requirementId(), check);
		text.append("- Requirements:\n");
		for (String requirementId : test.requirementIds())
		{
			text.append("  - `").append(requirementId).append("`: ");
			DeterministicCheck check = checkByRequirement.get(requirementId);
			if (check == null)
			{
				text.append("semantic; the judge decides it\n");
				continue;
			}
			text.append("deterministic; a ").append(check.criterion().kind()).append(" check decides it: ").
				append(check.criterion().describe()).append(". A failure is the behavior `").append(check.behavior()).
				append("`.\n").
				append("    - Known-good control, which the check must accept:\n");
			control(check.knownGood());
			text.append("    - Known-bad control, which the check must reject:\n");
			control(check.knownBad());
		}
		if (test.discardedChecks().isEmpty())
		{
			text.append("- Discarded checks: none\n");
			return;
		}
		text.append("- Discarded checks:\n");
		for (DiscardedCheck discarded : test.discardedChecks())
		{
			DeterministicCheck check = discarded.check();
			text.append("  - `").append(check.requirementId()).append("`, ").append(check.criterion().kind()).
				append(" check (").append(check.criterion().describe()).append("): ").append(discarded.reason()).
				append(" The judge decides `").append(check.requirementId()).append("` instead.\n");
		}
	}

	/**
	 * Lists the evidence of a check's control.
	 *
	 * @param control the control
	 */
	private void control(ControlEvidence control)
	{
		String indent = "      - ";
		for (int i = 0; i < control.replies().size(); ++i)
		{
			text.append(indent).append("Reply to turn ").append(i + 1).append(": ").
				append(quote(String.join("\n", control.replies().get(i)))).append('\n');
		}
		for (EvidenceFile file : control.files())
		{
			text.append(indent).append("File `").append(file.path()).append("`: ").append(quote(file.content())).
				append('\n');
		}
		for (ToolCall call : control.calls())
		{
			text.append(indent).append("Tool call: `").append(call.tool()).append('`');
			if (call.command() != null)
				text.append(" running `").append(call.command()).append('`');
			if (call.exitStatus() != null)
				text.append(", exit status ").append(call.exitStatus());
			text.append('\n');
		}
		for (Map.Entry<String, String> choice : control.choices().entrySet())
		{
			text.append(indent).append("Variation value of `").append(choice.getKey()).append("`: `").
				append(choice.getValue()).append("`\n");
		}
		if (control.replies().isEmpty() && control.files().isEmpty() && control.calls().isEmpty())
			text.append(indent).append("no replies, files or tool calls\n");
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
		text.append("- Role profile: ").append(judge.profile().label()).append(" (used by the deriver, judge and " +
			"author sessions)\n").
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
		String requiredRate = BettingProcess.REQUIRED_PASS_RATE.movePointRight(2).stripTrailingZeros().
			toPlainString() + "%";
		text.append("- Joint error budget: 0.05, split into 0.04 for certification, 0.005 for improvement claims " +
			"and 0.005 for the incumbent's bounds. Certification attempt i uses α_i = 0.04/(i(i + 1)).\n").
			append("- Certification: every cell must show a pass rate above ").append(requiredRate).
			append(". Bets: ").append(CertificationProcedure.BETS).append(".\n").
			append("- Caps of the first attempts, in formal trials per cell:\n");
		for (String cap : caps)
			text.append("  - ").append(cap).append('\n');
		text.append("- Readiness gate: a cell is ready for a certification attempt after at least ").
			append(ReadinessGate.MINIMUM_TRIALS).
			append(" representative development trials with at least 99% observed passes, at most ").
			append(ReadinessGate.BATCH_FAILURE_LIMIT).append(" failure in each of the latest two complete batches of ").
			append(CellCounts.BATCH_SIZE).
			append(" trials, and no unresolved reproducible defect or known regression.\n").
			append("- Review checkpoint: at ").append(Development.DEFAULT_REVIEW_POINT).
			append(" representative trials in a cell for an unchanged candidate with the cell still " +
				"unresolved, CAT stops scheduling that cell and asks you how to continue.\n").
			append("- Baseline targets: the incumbent runs ").append(Baseline.TARGET).
			append(" representative trials in every cell, and ").
			append(Baseline.EXTENDED_TARGET - Baseline.TARGET).
			append(" more in a cell whose observed rate is below 95% but not yet proven deficient, ending at ").
			append(Baseline.EXTENDED_TARGET).append(". A commit requires these targets.\n");
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
			"judges and authors, within the harness capacity that every CAT process shares. Concurrency starts at 1 " +
			"and grows gradually.\n");
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
	 * Shows the capability ranking's outcome and each certification scope choice: its cells, each category's weakest
	 * cell and its minimum session count.
	 */
	private void certificationScope()
	{
		heading("Certification scope");
		text.append("Confirm with `confirm <campaign> <choice>`, choosing the cells that certification covers. " +
			"Development always runs in each category's weakest cell, the lowest effort of the least capable model " +
			"that the choice covers, and in every cell where a certification attempt fails. The choice sets the " +
			"cells that the incumbent baseline and every certification attempt run in, and the cells that the " +
			"claim covers. A category with one cell is certified in that cell under every choice. To certify " +
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
		for (Category category : scoped.categories())
			weakest.add("`" + category.id() + "`: `" + CellTier.weakestCell(scoped, category.id()).id() + "`");
		text.append("Weakest cells: ").append(String.join("; ", weakest)).append("\n\n");
		RemainingWork remaining = RemainingWork.of(preview);
		int trials = remaining.total();
		text.append("At least ").append(2 * trials).append(" nested sessions remain if every trial passes: ").
			append(trials).append(" trials, each with one trial session and one judge session. Failures add " +
				"reruns, author sessions and promotion runs.\n\n").
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
	 * Names cells compactly: a category with several cells, all of them present, as every cell of the category, and
	 * each other cell by its identifier, with the cell count when there is more than one cell.
	 *
	 * @param group the cells
	 * @return the description
	 */
	private String describeCells(List<Cell> group)
	{
		Map<String, Integer> cellsByCategory = new HashMap<>();
		for (Cell cell : contract.cells())
			cellsByCategory.merge(cell.categoryId(), 1, Integer::sum);
		Map<String, List<Cell>> groupByCategory = new LinkedHashMap<>();
		for (Cell cell : group)
			groupByCategory.computeIfAbsent(cell.categoryId(), _ -> new ArrayList<>()).add(cell);
		List<String> whole = new ArrayList<>();
		List<String> single = new ArrayList<>();
		for (Map.Entry<String, List<Cell>> entry : groupByCategory.entrySet())
		{
			int size = entry.getValue().size();
			if (size > 1 && size == cellsByCategory.get(entry.getKey()))
				whole.add("`" + entry.getKey() + "`");
			else
			{
				for (Cell cell : entry.getValue())
					single.add("`" + cell.id() + "`");
			}
		}
		List<String> names = new ArrayList<>();
		if (whole.size() == 1)
			names.add("every cell of category " + whole.getFirst());
		else if (whole.size() > 1)
			names.add("every cell of categories " + String.join(", ", whole));
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
			text.append("The deriver found no requirements that conflict.\n");
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
			text.append("The deriver found no condition that makes certification unavailable.\n");
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
