package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.CheckCriterion.Count;
import io.github.cowwoc.cat.common.optimizer.contract.CheckCriterion.ExactText;
import io.github.cowwoc.cat.common.optimizer.contract.CheckCriterion.ExitStatus;
import io.github.cowwoc.cat.common.optimizer.contract.CheckCriterion.FileContent;
import io.github.cowwoc.cat.common.optimizer.contract.CheckCriterion.FileExists;
import io.github.cowwoc.cat.common.optimizer.contract.CheckCriterion.ForbiddenText;
import io.github.cowwoc.cat.common.optimizer.contract.CheckCriterion.PatternMatch;
import io.github.cowwoc.cat.common.optimizer.contract.CheckCriterion.ToolUsed;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests each kind of deterministic check against its controls and a trial's evidence.
 */
public final class DeterministicCheckTest
{
	/**
	 * Creates the test fixture.
	 */
	public DeterministicCheckTest()
	{
	}

	/**
	 * Returns one check of each kind, with evidence that it must accept and evidence that it must reject.
	 *
	 * @return rows of the kind's name, its criterion, the known-good evidence and the known-bad evidence
	 */
	@DataProvider(name = "kinds")
	public static Object[][] kinds()
	{
		return new Object[][]
			{
				{"exact_text", new ExactText(1, "Done."), replies("  Done.\n"), replies("Done, mostly.")},
				{"forbidden_text", new ForbiddenText(null, "As an AI"), replies("Sure.", "Here it is."),
					replies("Sure.", "As an AI, I cannot.")},
				{"pattern", new PatternMatch(1, "(?m)^- \\w+$"), replies("Colors:\n- red"), replies("red, green")},
				{"count", new Count(1, "(?m)^- ", 3, 3), replies("- a\n- b\n- c"), replies("- a\n- b")},
				{"file_exists", new FileExists("build/report.txt", true),
					files(new EvidenceFile("build/report.txt", "ok")), files()},
				{"file_content", new FileContent("VERSION", "1.2.0"), files(new EvidenceFile("VERSION", "1.2.0\n")),
					files(new EvidenceFile("VERSION", "1.1.0\n"))},
				{"exit_status", new ExitStatus("mvn .*test", 0),
					calls(new ToolCall("Bash", "mvn -q test", 1), new ToolCall("Bash", "mvn -q test", 0)),
					calls(new ToolCall("Bash", "mvn -q test", 0), new ToolCall("Bash", "mvn test", 1))},
				{"tool_used", new ToolUsed("Bash", false), calls(new ToolCall("Read", null, null)),
					calls(new ToolCall("Read", null, null), new ToolCall("Bash", "rm -rf build", 0))}
			};
	}

	/**
	 * Returns control evidence that holds only replies.
	 *
	 * @param replies the replies
	 * @return the evidence
	 */
	private static ControlEvidence replies(String... replies)
	{
		return new ControlEvidence(List.of(replies), List.of(), List.of(), Map.of());
	}

	/**
	 * Returns control evidence that holds only files.
	 *
	 * @param files the files
	 * @return the evidence
	 */
	private static ControlEvidence files(EvidenceFile... files)
	{
		return new ControlEvidence(List.of("Done."), List.of(files), List.of(), Map.of());
	}

	/**
	 * Returns control evidence that holds only tool calls.
	 *
	 * @param calls the tool calls
	 * @return the evidence
	 */
	private static ControlEvidence calls(ToolCall... calls)
	{
		return new ControlEvidence(List.of("Done."), List.of(), List.of(calls), Map.of());
	}

	/**
	 * Returns a two-turn test that checks {@code r1} with one check.
	 *
	 * @param variations the test's variation rules
	 * @param check the check
	 * @return the test
	 */
	private static TestDefinition test(List<VariationRule> variations, DeterministicCheck check)
	{
		return new TestDefinition("t", List.of("r1", "r2"), "Always.", List.of("First turn.", "Second turn."),
			Duration.ofMinutes(1), "Complies.", List.of(), variations, List.of(check), List.of());
	}

	/**
	 * Verifies that each kind keeps a check whose controls hold, accepts evidence like its known-good control and
	 * rejects evidence like its known-bad control with the value it observed.
	 *
	 * @param kind the kind's name
	 * @param criterion the check's criterion
	 * @param good evidence that the check must accept
	 * @param bad evidence that the check must reject
	 * @throws IOException if the evidence cannot be read
	 * @throws UndecidableCheckException if the evidence lacks tool calls
	 */
	@Test(dataProvider = "kinds")
	public void eachKindPassesItsControls(String kind, CheckCriterion criterion,
		ControlEvidence good, ControlEvidence bad) throws IOException, UndecidableCheckException
	{
		// Requirement: R-C9
		DeterministicCheck check = new DeterministicCheck("r1", criterion, good, bad);
		TestDefinition validated = test(List.of(), check).withValidatedControls();

		requireThat(criterion.kind(), "kind").isEqualTo(kind);
		requireThat(validated.checks(), "checks").isEqualTo(List.of(check));
		requireThat(validated.discardedChecks(), "discardedChecks").isEmpty();
		requireThat(validated.semanticRequirementIds(), "semanticRequirementIds").isEqualTo(List.of("r2"));
		CheckResult accepted = validated.decide(List.of(), good).getFirst();
		requireThat(accepted.passed(), "accepted.passed()").isTrue();
		CheckResult rejected = validated.decide(List.of(), bad).getFirst();
		requireThat(rejected.passed(), "rejected.passed()").isFalse();
		requireThat(rejected.describe(), "rejected.describe()").contains("`r1`").contains(kind).
			contains(rejected.observed());
	}

	/**
	 * Verifies that each kind's check is discarded when it rejects its known-good control, so its requirement
	 * becomes semantic, and the reason names the control.
	 *
	 * @param kind the kind's name
	 * @param criterion the check's criterion
	 * @param good evidence that the check accepts
	 * @param bad evidence that the check rejects
	 */
	@Test(dataProvider = "kinds")
	public void rejectingKnownGoodDiscardsTheCheck(String kind, CheckCriterion criterion,
		ControlEvidence good, ControlEvidence bad)
	{
		// Requirement: R-C9
		DeterministicCheck check = new DeterministicCheck("r1", criterion, bad, bad);
		TestDefinition validated = test(List.of(), check).withValidatedControls();

		requireThat(validated.checks(), "checks").isEmpty();
		requireThat(validated.discardedChecks().size(), "discardedChecks.size()").isEqualTo(1);
		DiscardedCheck discarded = validated.discardedChecks().getFirst();
		requireThat(discarded.check(), "discarded.check()").isEqualTo(check);
		requireThat(discarded.reason(), "reason").contains("rejected its known-good control");
		requireThat(validated.semanticRequirementIds(), "semanticRequirementIds").isEqualTo(List.of("r1", "r2"));
	}

	/**
	 * Verifies that each kind's check is discarded when it accepts its known-bad control, so its requirement becomes
	 * semantic, and the reason names the control.
	 *
	 * @param kind the kind's name
	 * @param criterion the check's criterion
	 * @param good evidence that the check accepts
	 * @param bad evidence that the check rejects
	 */
	@Test(dataProvider = "kinds")
	public void acceptingKnownBadDiscardsTheCheck(String kind, CheckCriterion criterion,
		ControlEvidence good, ControlEvidence bad)
	{
		// Requirement: R-C9
		DeterministicCheck check = new DeterministicCheck("r1", criterion, good, good);
		TestDefinition validated = test(List.of(), check).withValidatedControls();

		requireThat(validated.checks(), "checks").isEmpty();
		requireThat(validated.discardedChecks().size(), "discardedChecks.size()").isEqualTo(1);
		requireThat(validated.discardedChecks().getFirst().reason(), "reason").
			contains("accepted its known-bad control");
		requireThat(validated.semanticRequirementIds(), "semanticRequirementIds").isEqualTo(List.of("r1", "r2"));
	}

	/**
	 * Verifies that a parameter that names a variation dimension takes the drawn value's text, in the controls and
	 * on a trial's evidence, and that a regular expression matches the inserted text literally.
	 *
	 * @throws IOException if the evidence cannot be read
	 * @throws UndecidableCheckException if the evidence lacks tool calls
	 */
	@Test
	public void parameterNamesAVariationValue() throws IOException, UndecidableCheckException
	{
		// Requirement: R-C9
		List<VariationRule> variations = List.of(new VariationRule("version", List.of(
			new VariationValue("old", "1.2", 0.5), new VariationValue("new", "2.0", 0.5))));
		DeterministicCheck check = new DeterministicCheck("r1", new PatternMatch(1, "^Version {{version}}$"),
			new ControlEvidence(List.of("Version 1.2"), List.of(), List.of(), Map.of("version", "old")),
			new ControlEvidence(List.of("Version 1x2"), List.of(), List.of(), Map.of("version", "old")));
		TestDefinition validated = test(variations, check).withValidatedControls();
		requireThat(validated.checks(), "checks").isEqualTo(List.of(check));

		List<VariationChoice> drewNew = List.of(new VariationChoice("version", "new", 0.5));
		requireThat(validated.decide(drewNew, trial("Version 2.0")).getFirst().passed(), "newMatches").isTrue();
		requireThat(validated.decide(drewNew, trial("Version 1.2")).getFirst().passed(), "oldUnderNew").isFalse();
		requireThat(validated.decide(drewNew, trial("Version 2x0")).getFirst().passed(), "literalDot").isFalse();
	}

	/**
	 * Verifies that a check whose kind observes tool calls cannot be decided from evidence that records none, rather
	 * than passing or failing.
	 *
	 * @throws IOException if the evidence cannot be read
	 */
	@Test
	public void toolKindsNeedRecordedToolCalls() throws IOException
	{
		// Requirement: R-C9
		List<CheckCriterion> criteria = List.of(new ExitStatus("mvn", 0), new ToolUsed("Bash", true));
		for (CheckCriterion criterion : criteria)
		{
			DeterministicCheck check = new DeterministicCheck("r1", criterion, calls(), calls());
			boolean undecidable = false;
			try
			{
				test(List.of(), check).decide(List.of(), trial("Done."));
			}
			catch (UndecidableCheckException e)
			{
				undecidable = e.getMessage().contains("records no tool calls");
			}
			requireThat(undecidable, criterion.kind() + "Undecidable").isTrue();
		}
	}

	/**
	 * Verifies that a criterion rejects parameters that its kind cannot decide from.
	 */
	@Test
	public void criteriaRejectInvalidParameters()
	{
		// Requirement: R-C9
		List<Runnable> invalid = List.of(
			() -> new ExactText(0, "Done."),
			() -> new ForbiddenText(null, " "),
			() -> new PatternMatch(null, "(unclosed"),
			() -> new Count(null, "x", null, null),
			() -> new Count(null, "x", 3, 2),
			() -> new Count(null, "x", -1, null),
			() -> new FileExists("/etc/passwd", true),
			() -> new FileContent("../outside", "text"),
			() -> new FileExists("{{dir}}/../x", false),
			() -> new ExitStatus("[", 0),
			() -> new ToolUsed("", true));
		for (Runnable construction : invalid)
			requireThat(rejects(construction), "rejected").isTrue();
	}

	/**
	 * Verifies that a test rejects a check that it cannot hold: one for a requirement that it does not check, a second
	 * check for the same requirement, one that examines a turn that it does not have, one that names an undefined
	 * dimension, and one whose control does not choose a value for a dimension that the check names.
	 */
	@Test
	public void testRejectsChecksItCannotHold()
	{
		// Requirement: R-C9
		List<VariationRule> variations = List.of(new VariationRule("tone", List.of(
			new VariationValue("formal", "Dear Sir", 1.0))));
		ControlEvidence formal = new ControlEvidence(List.of("Dear Sir"), List.of(), List.of(),
			Map.of("tone", "formal"));
		ControlEvidence hi = new ControlEvidence(List.of("Hi"), List.of(), List.of(), Map.of("tone", "formal"));
		DeterministicCheck valid = new DeterministicCheck("r1", new ExactText(1, "{{tone}}"), formal, hi);
		List<Runnable> invalid = List.of(
			() -> test(variations, new DeterministicCheck("r9", valid.criterion(), formal, hi)),
			() -> new TestDefinition("t", List.of("r1"), "Always.", List.of("Turn."), Duration.ofMinutes(1),
				"Complies.", List.of(), variations, List.of(valid),
				List.of(new DiscardedCheck(valid, "It rejected its known-good control."))),
			() -> test(variations, new DeterministicCheck("r1", new ExactText(3, "{{tone}}"), formal, hi)),
			() -> test(variations, new DeterministicCheck("r1", new ExactText(1, "{{mood}}"), formal, hi)),
			() -> test(variations, new DeterministicCheck("r1", valid.criterion(), formal, replies("Hi"))),
			() -> test(variations, new DeterministicCheck("r1", valid.criterion(), formal,
				new ControlEvidence(List.of("Hi"), List.of(), List.of(), Map.of("tone", "casual")))));
		requireThat(rejects(() -> test(variations, valid)), "validRejected").isFalse();
		for (Runnable construction : invalid)
			requireThat(rejects(construction), "rejected").isTrue();
	}

	/**
	 * Indicates whether a construction throws {@link IllegalArgumentException}.
	 *
	 * @param construction the construction
	 * @return {@code true} if it was rejected
	 */
	private static boolean rejects(Runnable construction)
	{
		try
		{
			construction.run();
			return false;
		}
		catch (IllegalArgumentException _)
		{
			return true;
		}
	}

	/**
	 * Returns a trial's evidence that holds replies and records no tool calls, as a harness without tool records
	 * produces.
	 *
	 * @param replies the replies
	 * @return the evidence
	 */
	private static CheckEvidence trial(String... replies)
	{
		List<String> list = List.of(replies);
		return new CheckEvidence()
		{
			@Override
			public List<String> replies()
			{
				return list;
			}

			@Override
			public boolean exists(String path)
			{
				return false;
			}

			@Override
			public Optional<String> content(String path)
			{
				return Optional.empty();
			}

			@Override
			public Optional<List<ToolCall>> toolCalls()
			{
				return Optional.empty();
			}
		};
	}
}
