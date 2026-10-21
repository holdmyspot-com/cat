package com.holdmyspot.cat.common.optimizer.proposal;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.campaign.Baseline;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CellTier;
import com.holdmyspot.cat.common.optimizer.campaign.CellCounts;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationProcedure;
import com.holdmyspot.cat.common.optimizer.campaign.Development;
import com.holdmyspot.cat.common.optimizer.campaign.CommitGate;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.Cell;
import com.holdmyspot.cat.common.optimizer.contract.CertificationScope;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.Dependent;
import com.holdmyspot.cat.common.optimizer.contract.DerivedSource;
import com.holdmyspot.cat.common.optimizer.contract.WorkspaceFile;
import com.holdmyspot.cat.common.optimizer.contract.JudgeProfile;
import com.holdmyspot.cat.common.optimizer.contract.MappedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.contract.Split;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TestValue;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.contract.ValuesFile;
import com.holdmyspot.cat.common.optimizer.contract.VariableDeclaration;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveredTest;
import com.holdmyspot.cat.common.optimizer.execution.PilotResult;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.report.CertificationStatus;
import com.holdmyspot.cat.common.optimizer.report.EnumNames;
import com.holdmyspot.cat.common.optimizer.report.RemainingWork;
import com.holdmyspot.cat.common.optimizer.statistics.SignificanceLevel;
import com.holdmyspot.cat.common.optimizer.workspace.LinkReport;

import java.io.File;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
	/**
	 * Text in backticks, such as a file that a derived source's rule names.
	 */
	private static final Pattern QUOTED = Pattern.compile("`([^`]+)`");
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
	private final List<String> controlRefusals;
	private final List<String> trialFiles;
	private final HookReport hooks;
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
	 * @param plugin the check of the trial plugins
	 * @param controlRefusals the reasons why the control source cannot be built, which block confirmation
	 * @param trialFiles the files that every trial receives from the incumbent, relative to the project
	 * @param hooks what running the hooks that run before the model acts showed
	 * @param goal what the user wants the target to achieve, or {@code null} if the user stated no goal
	 */
	Markdown(DerivedProposal derived, Contract contract, LinkReport links, Path controlFile,
		HostLogin login, int ceiling, Map<CertificationScope, Campaign> previews, List<String> caps,
		CertificationStatus incumbent, Map<String, PilotResult> pilots, Map<String, DeliveredTest> delivered,
		PluginCheck plugin, List<String> controlRefusals, List<String> trialFiles, HookReport hooks, String goal)
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
		this.controlRefusals = controlRefusals;
		this.trialFiles = trialFiles;
		this.hooks = hooks;
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
		trialPlugins();
		guardPilot();
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
	 * Lists the prompt loading that the contract author session observed, the loading that was derived, the files
	 * that every trial receives and the hooks whose output differs in a trial's workspace.
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
		{
			text.append("- ").append(pluginFile(source.path()).orElse(source.path())).append(": ").
				append(withPluginFiles(source.rule())).append('\n');
		}
		if (!trialFiles.isEmpty())
		{
			text.append("\nEvery trial receives these files from the incumbent, besides its prompt test's workspace " +
				"files:\n\n");
			for (String file : trialFiles)
				text.append("- `").append(file).append("`\n");
		}
		if (!hooks.differences().isEmpty())
		{
			text.append("\nThese hooks print different output in a trial's workspace than in your sessions, which a " +
				"time or Git state can explain:\n\n");
			for (HookReport.HookDifference difference : hooks.differences())
			{
				text.append("- ").append(Character.toUpperCase(difference.hook().charAt(0))).
					append(difference.hook().substring(1)).append(", from its first line that differs:\n").
					append("  - In trials: ").append(outputLines(difference.trial())).append('\n').
					append("  - In your sessions: ").append(outputLines(difference.session())).append('\n');
			}
		}
		text.append('\n').append(Proposal.DERIVED_LOADING_LIMIT).append('\n');
		if (hooks.checked() > 0)
			text.append('\n').append(Proposal.HOOK_CHECK_LIMIT).append('\n');
	}

	/**
	 * Formats lines of a hook's output as inline code.
	 *
	 * @param lines the lines
	 * @return each line as inline code, separated by spaces, or {@code no output} if there are none
	 */
	private static String outputLines(List<String> lines)
	{
		if (lines.isEmpty())
			return "no output";
		StringJoiner joined = new StringJoiner(" ");
		for (String line : lines)
		{
			// A fence longer than every run of backticks in the line keeps the line's own backticks literal
			int longest = 0;
			int run = 0;
			for (char character : line.toCharArray())
			{
				if (character == '`')
				{
					++run;
					longest = Math.max(longest, run);
				}
				else
					run = 0;
			}
			String fence = "`".repeat(longest + 1);
			joined.add(fence + " " + line + " " + fence);
		}
		return joined.toString();
	}

	/**
	 * Names a file inside a trial plugin's base copy by the plugin and the file's path inside the plugin, which is
	 * shorter than the absolute path and stays the same across campaigns.
	 *
	 * @param path a file's path as a derived source records it
	 * @return text such as {@code trial plugin `cat`: `rules/x.md`}, or empty if the path does not lie inside the
	 *   base copy of a trial plugin
	 */
	private Optional<String> pluginFile(String path)
	{
		for (Map.Entry<String, Path> entry : plugin.baseDirectories().entrySet())
		{
			String prefix = entry.getValue().toAbsolutePath().normalize() + File.separator;
			if (path.startsWith(prefix))
			{
				String relative = path.substring(prefix.length()).replace(File.separatorChar, '/');
				return Optional.of("trial plugin `" + entry.getKey() + "`: `" + relative + "`");
			}
		}
		return Optional.empty();
	}

	/**
	 * Names each file that a derived source's rule quotes and that lies inside a trial plugin's base copy by the
	 * plugin and the file's path inside the plugin.
	 *
	 * @param rule the rule that loads a derived source
	 * @return the rule with each such quoted path replaced
	 */
	private String withPluginFiles(String rule)
	{
		return QUOTED.matcher(rule).replaceAll(quoted ->
			Matcher.quoteReplacement(pluginFile(quoted.group(1)).orElse(quoted.group())));
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
		workspaceFiles(test);
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
	 * Shows how a test's files relate to the prompt tests that the project records: created by the contract author,
	 * recorded and unchanged, or recorded and edited, with the difference from the recorded files.
	 *
	 * @param test the prompt test
	 */
	private void scenario(PromptTest test)
	{
		Path directory = delivered.get(test.id()).location().directory();
		DraftChange change = derived.changes().get(test.id());
		if (change == null)
			throw new AssertionError("The proposal has no change for the prompt test " + test.id());
		switch (change.kind())
		{
			case CREATED -> text.append("- Files: created by the contract author; delivery writes `").
				append(directory).append("`\n");
			case RECORDED -> text.append("- Files: recorded at `").append(directory).append("`, unchanged\n");
			case EDITED ->
			{
				text.append("- Files: recorded at `").append(directory).append("` and edited; delivery rewrites them " +
					"with these changes:\n\n");
				String diff = change.diff();
				if (!diff.isEmpty())
				{
					String fence = "`".repeat(Math.max(3, longestBacktickRun(diff) + 1));
					text.append("    ").append(fence).append("diff\n");
					for (String line : diff.substring(0, diff.length() - 1).split("\n", -1))
						text.append("    ").append(line).append('\n');
					text.append("    ").append(fence).append("\n\n");
				}
				if (change.valuesChanged())
				{
					text.append("    `").append(ValuesFile.NAME).append("` changes; its values are the test cases " +
						"listed below.\n\n");
				}
			}
		}
	}

	/**
	 * Shows the files that a test writes into each trial's workspace: one that the contract author created with its
	 * content, and one of a recorded prompt test by the file that it is copied from, whose changes the test's
	 * difference from the recorded files shows.
	 *
	 * @param test the prompt test
	 */
	private void workspaceFiles(PromptTest test)
	{
		if (test.workspaceFiles().isEmpty())
		{
			text.append("- Workspace files: none\n");
			return;
		}
		DraftChange change = derived.changes().get(test.id());
		boolean created = change != null && change.kind() == DraftChange.Kind.CREATED;
		text.append("- Workspace files, written into each trial's workspace with each test case's values:\n");
		for (WorkspaceFile workspaceFile : test.workspaceFiles())
		{
			if (workspaceFile.link())
			{
				text.append("  - `").append(workspaceFile.path()).append("`, a link to `").append(workspaceFile.content()).
					append("`\n");
				continue;
			}
			if (!workspaceFile.source().isEmpty() && !created)
			{
				text.append("  - `").append(workspaceFile.path()).append("`, copied from `").append(workspaceFile.source()).
					append("`\n");
				continue;
			}
			String fence = "`".repeat(Math.max(3, longestBacktickRun(workspaceFile.content()) + 1));
			text.append("  - `").append(workspaceFile.path()).append("`:\n\n    ").append(fence).append('\n');
			String content = workspaceFile.content();
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
	 * @return the quoted text of a text value, or the call of a generated value with its parameters but without the
	 *   seed and version that only rebuild its bytes, such as {@code log_lines(count 420)}
	 */
	private static String value(TestValue value)
	{
		return switch (value)
		{
			case TestValue.Text text -> quote(text.text());
			case TestValue.Generated generated ->
			{
				StringJoiner call = new StringJoiner(", ", generated.generator() + "(", ")");
				for (Map.Entry<String, Long> parameter : new TreeMap<>(generated.parameters()).entrySet())
					call.add(parameter.getKey() + " " + parameter.getValue());
				yield call.toString();
			}
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
	 * removes, followed by each reason why it cannot be built.
	 */
	private void controlSource()
	{
		heading("Control source");
		controlDependents();
		if (controlRefusals.isEmpty())
			return;
		text.append("\nCAT cannot build the control source, so it ran no guard pilot, and confirmation is refused " +
			"until revision feedback corrects the dependents:\n\n");
		for (String refusal : controlRefusals)
			text.append("- ").append(refusal).append('\n');
	}

	/**
	 * Shows the target prompt file that the control source empties and each dependent that it removes.
	 */
	private void controlDependents()
	{
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
				case PLUGIN -> text.append("The file of the trial plugin `").append(dependent.plugin()).append("` `");
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
	 * Shows the trial plugins that each trial installs and where each comes from, each project directory or file that
	 * trials write over a project-built plugin, each mapped file whose content differs from the base's, each prompt
	 * file that no mapping covers, each file that loads in production but not in trials and each hook, plugin and MCP
	 * server of the user's harness configuration that trials do not run.
	 */
	private void trialPlugins()
	{
		heading("Trial plugins");
		if (plugin.plugins().isEmpty())
			text.append("Trials install no plugin.\n");
		for (TrialPlugin trialPlugin : plugin.plugins())
		{
			text.append("- `").append(trialPlugin.name()).append("`: ");
			switch (trialPlugin.source())
			{
				case TrialPlugin.Build build ->
				{
					text.append("built by `").append(String.join(" ", build.command())).append("` into `").
						append(PortablePaths.text(build.output())).append('`');
					if (trialPlugin.mappings().isEmpty())
						text.append(", with no project file written over it");
					else
						text.append(", with these staged project paths written over it:");
					text.append('\n');
					for (TrialPlugin.Mapping mapping : trialPlugin.mappings())
					{
						text.append("  - `").append(PortablePaths.text(mapping.source())).append("` → `").
							append(PortablePaths.text(mapping.target())).append("`\n");
					}
				}
				case TrialPlugin.Installed _ -> text.append("the user's installed copy\n");
			}
		}
		if (!plugin.differing().isEmpty())
		{
			text.append("\nWarning: the content of these mapped files differs from the base's, because the base " +
				"predates them or the build changes them. Trials load the project's content:\n\n");
			for (PluginMapping.Difference difference : plugin.differing())
			{
				text.append("- `").append(PortablePaths.text(difference.file())).append("` → `").
					append(difference.plugin()).append("` `").append(PortablePaths.text(difference.mapped())).
					append("`\n");
			}
		}
		if (!plugin.uncovered().isEmpty())
		{
			text.append("\nThese prompt files do not load from the project, and no trial plugin that the project " +
				"builds holds a copy of them, so a candidate's edit to them never reaches trials:\n\n");
			for (Path path : plugin.uncovered())
				text.append("- `").append(PortablePaths.text(path)).append("`\n");
		}
		if (!plugin.productionOnly().isEmpty())
		{
			text.append("\nThese files load in the user's sessions of the harness but not in trials:\n\n");
			for (String path : plugin.productionOnly())
				text.append("- `").append(path).append("`\n");
		}
		if (!plugin.homeOnly().isEmpty())
		{
			text.append("\nThese hooks, plugins and MCP servers of the user's harness configuration run in the user's " +
				"sessions but not in trials:\n\n");
			for (String entry : plugin.homeOnly())
			{
				text.append("- ").append(Character.toUpperCase(entry.charAt(0))).append(entry.substring(1)).
					append('\n');
			}
		}
	}

	/**
	 * Shows how often each assertion passed in the guard pilot's control trials.
	 */
	private void guardPilot()
	{
		heading("Guard pilot");
		text.append(PilotReport.render(contract, pilots, !controlRefusals.isEmpty()));
	}

	/**
	 * Lists every symbolic link.
	 */
	private void symbolicLinks()
	{
		heading("Symbolic links");
		if (links.entries().isEmpty())
		{
			text.append("No symbolic link was found in the prompt system.\n");
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
		text.append("Certification covers the weakest cell only unless you choose another scope: " +
			"`confirm <campaign>` confirms the weakest cell only, and `confirm <campaign> <choice>` confirms a choice. " +
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
		List<Cell> weakest = new ArrayList<>();
		for (PromptTest test : scoped.tests())
			weakest.add(CellTier.weakestCell(scoped, test.id()));
		text.append("Weakest cells: ").append(cellGroups(weakest)).append("\n\n");
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
	 * Names cells compactly by {@link #cellGroups(List) groups}, with the cell count when there is more than one
	 * cell.
	 *
	 * @param cells the cells
	 * @return the description
	 */
	private static String describeCells(List<Cell> cells)
	{
		String description = cellGroups(cells);
		if (cells.size() > 1)
			description += " (" + cells.size() + " cells)";
		return description;
	}

	/**
	 * Names cells exactly as groups of tests and profiles, each group holding every pairing of its tests with its
	 * profiles, such as {@code tests `a`, `b` × profiles `big/high`, `small/high`}. The tests whose cells have the
	 * same set of profiles form one group. The groups are in the order of their first cell, and the tests and
	 * profiles of a group are in the order of their first cell.
	 *
	 * @param cells the cells
	 * @return the groups, separated by semicolons
	 */
	private static String cellGroups(List<Cell> cells)
	{
		Map<String, Set<String>> profilesByTest = new LinkedHashMap<>();
		for (Cell cell : cells)
			profilesByTest.computeIfAbsent(cell.testId(), _ -> new LinkedHashSet<>()).add(cell.profile().label());
		// Set equality ignores order, so tests whose profiles appear in different orders still share a group
		Map<Set<String>, List<String>> testsByProfiles = new LinkedHashMap<>();
		for (Map.Entry<String, Set<String>> entry : profilesByTest.entrySet())
			testsByProfiles.computeIfAbsent(entry.getValue(), _ -> new ArrayList<>()).add(entry.getKey());
		StringJoiner groups = new StringJoiner("; ");
		for (Map.Entry<Set<String>, List<String>> entry : testsByProfiles.entrySet())
		{
			groups.add(quotedList("test", entry.getValue()) + " × " +
				quotedList("profile", List.copyOf(entry.getKey())));
		}
		return groups.toString();
	}

	/**
	 * Names values of one kind, each in backticks.
	 *
	 * @param kind the singular name of the kind
	 * @param values the values
	 * @return text such as {@code test `a`} or {@code tests `a`, `b`}
	 */
	private static String quotedList(String kind, List<String> values)
	{
		StringJoiner list = new StringJoiner("`, `", "`", "`");
		for (String value : values)
			list.add(value);
		if (values.size() == 1)
			return kind + " " + list;
		return kind + "s " + list;
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
