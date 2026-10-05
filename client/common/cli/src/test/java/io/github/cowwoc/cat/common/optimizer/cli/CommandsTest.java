package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.campaign.Applicability;
import io.github.cowwoc.cat.common.optimizer.campaign.Builder;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationParameters;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationRecord;
import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.Throttle;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult;
import io.github.cowwoc.cat.common.optimizer.execution.DeliveryReport;
import io.github.cowwoc.cat.common.optimizer.execution.SliceSettings;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.report.CertificationStatus;
import io.github.cowwoc.cat.common.optimizer.report.ReportEvidence;
import io.github.cowwoc.cat.common.optimizer.report.StatusReport;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tests {@link Commands} with a fake {@link Core}.
 */
public final class CommandsTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public CommandsTest()
	{
	}

	/**
	 * A core that records its calls.
	 */
	private static final class FakeCore implements Core
	{
		private final List<String> calls = new ArrayList<>();
		private final StatusReport status = StatusReport.of(new Builder().confirmed().build(),
			ReportEvidence.NONE);
		private CommandResult result = CommandResult.of(status, Harness.CLAUDE);
		private Exception failure;
		private int ceiling;

		/**
		 * Returns the result, or throws the scripted failure.
		 *
		 * @param call the call, for the record
		 * @return the result
		 * @throws CommandRefusedException if the failure is a refusal
		 * @throws IOException if the failure is an I/O error
		 */
		private CommandResult answer(String call) throws CommandRefusedException, IOException
		{
			calls.add(call);
			if (failure instanceof CommandRefusedException refused)
				throw refused;
			if (failure instanceof IOException io)
				throw io;
			return result;
		}

		@Override
		public CommandResult start(Path target, int ceiling) throws CommandRefusedException, IOException
		{
			this.ceiling = ceiling;
			return answer("start " + target);
		}

		@Override
		public CommandResult run(String campaign) throws CommandRefusedException, IOException
		{
			return answer("run " + campaign);
		}

		@Override
		public CommandResult status(String campaign) throws CommandRefusedException, IOException
		{
			return answer("status " + campaign);
		}

		@Override
		public CommandResult confirm(String campaign, CertificationScope scope) throws CommandRefusedException,
			IOException
		{
			return answer("confirm " + campaign + " " + scope.id());
		}

		@Override
		public CommandResult revise(String campaign, String feedback) throws CommandRefusedException, IOException
		{
			return answer("revise " + campaign + " " + feedback);
		}

		@Override
		public CommandResult choose(String campaign, Choice choice, int attempt, int reviewPoint, Path target)
			throws CommandRefusedException, IOException
		{
			String call = "choose " + campaign + " " + choice + " " + attempt + " " + reviewPoint;
			if (target != null)
				call += " " + target;
			return answer(call);
		}

		@Override
		public CommandResult resolveConflict(String campaign, int resolution)
			throws CommandRefusedException, IOException
		{
			return answer("resolve " + campaign + " " + resolution);
		}

		@Override
		public CommandResult cancel(String campaign) throws CommandRefusedException, IOException
		{
			return answer("cancel " + campaign);
		}
	}

	/**
	 * Runs a command.
	 *
	 * @param core the core
	 * @param args the arguments
	 * @param output receives the printed JSON
	 * @return the exit code
	 */
	private static int run(FakeCore core, List<String> args, StringBuilder output)
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		int exit;
		try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
		{
			exit = new Commands(core, JSON, ZoneOffset.UTC).execute(args, out);
		}
		output.append(bytes.toString(StandardCharsets.UTF_8));
		return exit;
	}

	/**
	 * Verifies that each command reaches the core with its validated arguments.
	 */
	@Test
	public void dispatchesEveryCommand()
	{
		// Requirement: R-I2
		FakeCore core = new FakeCore();
		StringBuilder output = new StringBuilder();
		List<List<String>> commands = List.of(
			List.of("start", "prompts/a.md"),
			List.of("run", "c1"),
			List.of("status", "c1"),
			List.of("status", "prompts/a.md"),
			List.of("confirm", "c1", "weakest-cell"),
			List.of("confirm", "c1", "latest-versions"),
			List.of("confirm", "c1", "described"),
			List.of("revise", "c1", "tighten the contract"),
			List.of("choose", "c1", "keep-improvement", "--attempt", "2"),
			List.of("choose", "c1", "more-evidence", "--review-point", "3"),
			List.of("choose", "c1", "stop"),
			List.of("cancel", "c1"));
		for (List<String> command : commands)
			requireThat(run(core, command, output), "exit").isEqualTo(0);
		requireThat(core.calls, "calls").isEqualTo(List.of(
			"start prompts/a.md",
			"run c1",
			"status c1",
			"status prompts/a.md",
			"confirm c1 weakest-cell",
			"confirm c1 latest-versions",
			"confirm c1 described",
			"revise c1 tighten the contract",
			"choose c1 KEEP_IMPROVEMENT 2 0",
			"choose c1 MORE_EVIDENCE 0 3",
			"choose c1 STOP 0 0",
			"cancel c1"));
	}

	/**
	 * Verifies that {@code confirm} without a certification scope, or with an unknown one, is refused with every
	 * choice named.
	 */
	@Test
	public void confirmNamesEveryScopeChoice()
	{
		// Requirement: R-C15
		for (List<String> args : List.of(List.of("confirm", "c1"), List.of("confirm", "c1", "all-models")))
		{
			FakeCore core = new FakeCore();
			StringBuilder output = new StringBuilder();
			requireThat(run(core, args, output), "exit").isEqualTo(2);
			requireThat(core.calls, "calls").isEmpty();
			String message = JSON.readTree(output.toString()).get("message").asString();
			requireThat(message, "message").contains("weakest-cell").contains("latest-versions").
				contains("described");
		}
	}

	/**
	 * Verifies that the campaign ceiling accepts both endpoints of its range.
	 */
	@Test
	public void acceptsCampaignCeiling()
	{
		// Requirement: R-E4
		for (String ceiling : List.of("1", "16"))
		{
			FakeCore core = new FakeCore();
			StringBuilder output = new StringBuilder();
			requireThat(run(core, List.of("start", "prompts/a.md", "--max-concurrency", ceiling), output),
				"exit").isEqualTo(0);
			requireThat(core.calls, "calls").isEqualTo(List.of("start prompts/a.md"));
			requireThat(core.ceiling, "ceiling").isEqualTo(Integer.parseInt(ceiling));
		}
	}

	/**
	 * Verifies that malformed ceilings are refused with the range before calling the core.
	 */
	@Test
	public void rejectsInvalidCampaignCeiling()
	{
		// Requirement: R-E4
		List<List<String>> options = List.of(
			List.of("--max-concurrency", "0"),
			List.of("--max-concurrency", "17"),
			List.of("--max-concurrency", "x"),
			List.of("--max-concurrency", "1", "--max-concurrency", "16"),
			List.of("--max-concurrency"));
		for (List<String> option : options)
		{
			FakeCore core = new FakeCore();
			StringBuilder output = new StringBuilder();
			List<String> args = new ArrayList<>(List.of("start", "prompts/a.md"));
			args.addAll(option);
			requireThat(run(core, args, output), "exit").isEqualTo(2);
			requireThat(core.calls, "calls").isEmpty();
			String message = JSON.readTree(output.toString()).get("message").asString();
			requireThat(message.lines().findFirst().orElseThrow(), "message").contains("1..16");
		}
	}

	/**
	 * Verifies that command usage lists the ceiling option and its range.
	 */
	@Test
	public void usageListsCampaignCeiling()
	{
		// Requirement: R-E4
		requireThat(Commands.USAGE, "usage").contains("start <target> [--max-concurrency <1..16>]");
	}

	/**
	 * Verifies that the authority-warning choices reach the core, the authorize-target choice with its target.
	 */
	@Test
	public void dispatchesAuthorityWarningChoices()
	{
		// Requirement: R-W4
		FakeCore core = new FakeCore();
		StringBuilder output = new StringBuilder();
		List<List<String>> commands = List.of(
			List.of("choose", "c1", "authorize-target", "--target", "/external/shared.md"),
			List.of("choose", "c1", "use-authorized-source"));
		for (List<String> command : commands)
			requireThat(run(core, command, output), "exit").isEqualTo(0);
		requireThat(core.calls, "calls").isEqualTo(List.of(
			"choose c1 AUTHORIZE_TARGET 0 0 /external/shared.md",
			"choose c1 USE_AUTHORIZED_SOURCE 0 0"));
	}

	/**
	 * Verifies that a numbered conflict resolution is accepted by the command parser.
	 */
	@Test
	public void parsesConflictResolution()
	{
		// Requirement: R-D13
		FakeCore core = new FakeCore();
		StringBuilder output = new StringBuilder();
		requireThat(run(core, List.of("choose", "c1", "resolution-2"), output), "exit").isEqualTo(0);
		requireThat(core.calls, "calls").isEqualTo(List.of("resolve c1 2"));
	}

	/**
	 * Verifies the output schema: the status object plus harness, summary, proposal, deliveryResult and message.
	 */
	@Test
	public void printsStatusObjectWithExtensions()
	{
		// Requirement: R-I2
		FakeCore core = new FakeCore();
		core.result = new CommandResult(core.status, Harness.CODEX, "the proposal", null, "a notice");
		StringBuilder output = new StringBuilder();
		int exit = run(core, List.of("status", "c1"), output);
		requireThat(exit, "exit").isEqualTo(0);
		JsonNode json = JSON.readTree(output.toString());
		requireThat(json.get("status").asString(), "status").isEqualTo(core.status.status().label());
		requireThat(json.get("campaign").asString(), "campaign").isEqualTo(core.status.campaignId());
		requireThat(json.has("choices"), "hasChoices").isTrue();
		requireThat(json.get("harness").asString(), "harness").isEqualTo("codex");
		requireThat(json.get("summary").asString(), "summary").isEqualTo(core.status.summary(ZoneOffset.UTC));
		requireThat(json.get("proposal").asString(), "proposal").isEqualTo("the proposal");
		requireThat(json.get("deliveryResult").isNull(), "deliveryResultIsNull").isTrue();
		requireThat(json.get("message").asString(), "message").isEqualTo("a notice");
		requireThat(output.toString().strip().lines().count(), "lines").isEqualTo(1L);
	}

	/**
	 * Verifies that the printed summary shows times in the zone that the commands were created with, while the status
	 * object keeps the UTC instant.
	 */
	@Test
	public void printsSummaryTimesInUserTimeZone()
	{
		FakeCore core = new FakeCore();
		StatusReport held = StatusReport.of(new Builder().confirmed().
			throttle(new Throttle(Instant.parse("2026-10-09T21:13:08Z"), 2, 1)).build(), ReportEvidence.NONE);
		core.result = CommandResult.of(held, Harness.CLAUDE);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		int exit;
		try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
		{
			exit = new Commands(core, JSON, ZoneId.of("America/Toronto")).execute(List.of("status", "c1"), out);
		}

		requireThat(exit, "exit").isEqualTo(0);
		JsonNode json = JSON.readTree(bytes.toString(StandardCharsets.UTF_8));
		requireThat(json.path("concurrency").path("holdUntil").asString(), "holdUntil").
			isEqualTo("2026-10-09T21:13:08Z");
		requireThat(json.get("summary").asString(), "summary").contains("hold until 2026-10-09T17:13:08-04:00");
	}

	/**
	 * Verifies that a delivery appears with its diff, external change record, base-only changes and applicability.
	 */
	@Test
	public void printsDelivery()
	{
		// Requirement: R-I2
		Path external = Path.of("/etc/external.md");
		DeliveryResult.Delivered delivered = new DeliveryResult.Delivered(Outcome.CERTIFIED,
			new Applicability(Applicability.Status.HISTORICAL, true, List.of(MeasurementCondition.MODEL), List.of(),
				List.of(MeasurementCondition.HOST_TOOLS)),
			List.of(Path.of("prompts/a.md")), List.of(Path.of("notes.md")),
			List.of(new DeliveryChange.Create(4, external, new FileContent("x".getBytes(StandardCharsets.UTF_8)),
				Set.of())),
			"diff --git a/prompts/a.md b/prompts/a.md");
		FakeCore core = new FakeCore();
		core.result = new CommandResult(core.status, Harness.CLAUDE, null,
			new DeliveryReport(delivered, delivered.applicability()), null);
		StringBuilder output = new StringBuilder();
		run(core, List.of("run", "c1"), output);
		JsonNode delivery = JSON.readTree(output.toString()).get("deliveryResult");
		requireThat(delivery.get("result").asString(), "result").isEqualTo("delivered");
		requireThat(delivery.get("label").asString(), "label").isEqualTo("certified");
		requireThat(delivery.get("diff").asString(), "diff").isEqualTo("diff --git a/prompts/a.md b/prompts/a.md");
		requireThat(delivery.get("baseOnlyChanges").get(0).asString(), "baseOnly").isEqualTo("notes.md");
		requireThat(delivery.get("changedPromptSources").get(0).asString(), "sources").isEqualTo("prompts/a.md");
		JsonNode applicability = delivery.get("applicability");
		requireThat(applicability.get("status").asString(), "applicability").isEqualTo("historical");
		requireThat(applicability.get("promptContentChanged").asBoolean(), "promptContentChanged").isTrue();
		requireThat(applicability.get("changed").get(0).asString(), "changed").isEqualTo("MODEL");
		requireThat(applicability.get("unchecked").get(0).asString(), "unchecked").isEqualTo("HOST_TOOLS");
		JsonNode change = delivery.get("externalChanges").get(0);
		requireThat(change.get("target").asString(), "target").isEqualTo("/etc/external.md");
		requireThat(change.get("sequence").asInt(), "sequence").isEqualTo(4);
		requireThat(change.get("kind").asString(), "kind").isEqualTo("create");
	}

	/**
	 * Verifies that the status of a target that only a certification record describes is one object with the
	 * record's index, scope and applicability, and a summary that names them, and no campaign, harness, proposal or
	 * delivery.
	 */
	@Test
	public void printsCertificationRecord()
	{
		// Requirement: R-M6
		CertificationRecord record = new CertificationRecord(Path.of("CLAUDE.md"), 2,
			CertificationScope.LATEST_VERSIONS, CertificationParameters.current(2), List.of(), Map.of(),
			Builder.OBSERVED);
		Applicability applicability = new Applicability(Applicability.Status.HISTORICAL, true,
			List.of(MeasurementCondition.JUDGE), List.of(), List.of(MeasurementCondition.MODEL));
		FakeCore core = new FakeCore();
		core.result = CommandResult.of(new CertificationStatus(record, applicability));
		StringBuilder output = new StringBuilder();

		requireThat(run(core, List.of("status", "CLAUDE.md"), output), "exit").isEqualTo(0);

		requireThat(core.calls, "calls").isEqualTo(List.of("status CLAUDE.md"));
		JsonNode json = JSON.readTree(output.toString());
		requireThat(json.get("status").asString(), "status").isEqualTo("certified");
		requireThat(json.get("campaign").isNull(), "campaignIsNull").isTrue();
		requireThat(json.get("target").asString(), "target").isEqualTo("CLAUDE.md");
		requireThat(json.get("attempt").asInt(), "attempt").isEqualTo(2);
		requireThat(json.get("scope").asString(), "scope").isEqualTo("latest-versions");
		JsonNode applicabilityNode = json.get("applicability");
		requireThat(applicabilityNode.get("status").asString(), "applicability").isEqualTo("historical");
		requireThat(applicabilityNode.get("promptContentChanged").asBoolean(), "promptContentChanged").isTrue();
		requireThat(applicabilityNode.get("changed").get(0).asString(), "changed").isEqualTo("JUDGE");
		requireThat(applicabilityNode.get("unchecked").get(0).asString(), "unchecked").isEqualTo("MODEL");
		requireThat(json.get("harness").isNull(), "harnessIsNull").isTrue();
		requireThat(json.get("summary").asString(), "summary").isEqualTo("""
			Certification record of CLAUDE.md, scope latest-versions (every cell of each model family's latest \
			version)
			Attempt 2: certified (historical; changed: PROMPT_CONTENT, JUDGE)
			  Not checked: trial model.""");
		requireThat(json.get("proposal").isNull(), "proposalIsNull").isTrue();
		requireThat(json.get("deliveryResult").isNull(), "deliveryResultIsNull").isTrue();
		requireThat(json.get("message").isNull(), "messageIsNull").isTrue();
	}

	/**
	 * Verifies that a command's notices follow the status summary, one per line.
	 */
	@Test
	public void appendsNoticesToSummary()
	{
		// Requirement: R-W9
		FakeCore core = new FakeCore();
		core.result = CommandResult.of(core.status, Harness.CLAUDE).withNotices(List.of("First notice.",
			"Second notice."));
		StringBuilder output = new StringBuilder();

		requireThat(run(core, List.of("run", "c1"), output), "exit").isEqualTo(0);

		requireThat(JSON.readTree(output.toString()).get("summary").asString(), "summary").
			isEqualTo(core.status.summary(ZoneOffset.UTC) + "\nFirst notice.\nSecond notice.");
	}

	/**
	 * Verifies that a failed delivery names why nothing was delivered.
	 */
	@Test
	public void printsFailedDelivery()
	{
		// Requirement: R-I2
		FakeCore core = new FakeCore();
		core.result = new CommandResult(core.status, Harness.CLAUDE, null,
			new DeliveryReport(new DeliveryResult.VerificationFailed(List.of(Path.of("a.md"))), null), null);
		StringBuilder output = new StringBuilder();
		run(core, List.of("run", "c1"), output);
		JsonNode delivery = JSON.readTree(output.toString()).get("deliveryResult");
		requireThat(delivery.get("result").asString(), "result").isEqualTo("verification-failed");
		requireThat(delivery.get("mismatched").get(0).asString(), "mismatched").isEqualTo("a.md");
	}

	/**
	 * Returns malformed command lines.
	 *
	 * @return the arguments of each command line
	 */
	@DataProvider
	public Object[][] invalidInput()
	{
		return new Object[][]
			{
				{List.of()},
				{List.of("frobnicate")},
				{List.of("start")},
				{List.of("start", " ")},
				{List.of("run")},
				{List.of("run", "c1", "extra")},
				{List.of("run", "../escape")},
				{List.of("status")},
				{List.of("status", " ")},
				{List.of("status", "c1", "extra")},
				{List.of("confirm", "")},
				{List.of("confirm", "c1")},
				{List.of("confirm", "c1", "all-models")},
				{List.of("confirm", "c1", "latest-versions", "extra")},
				{List.of("cancel", "-rf")},
				{List.of("revise", "c1")},
				{List.of("revise", "c1", "  ")},
				{List.of("choose", "c1")},
				{List.of("choose", "c1", "no-such-choice")},
				{List.of("choose", "c1", "KEEP_IMPROVEMENT", "--attempt", "1")},
				{List.of("choose", "c1", "keep-improvement")},
				{List.of("choose", "c1", "keep-improvement", "--attempt", "0")},
				{List.of("choose", "c1", "keep-improvement", "--attempt", "x")},
				{List.of("choose", "c1", "stop", "--attempt", "1")},
				{List.of("choose", "c1", "more-evidence")},
				{List.of("choose", "c1", "stop", "--review-point", "1")},
				{List.of("choose", "c1", "stop", "--bogus", "1")},
				{List.of("choose", "c1", "stop", "--attempt")},
				{List.of("choose", "c1", "authorize-target")},
				{List.of("choose", "c1", "authorize-target", "--target")},
				{List.of("choose", "c1", "authorize-target", "--target", "shared.md")},
				{List.of("choose", "c1", "authorize-target", "--target", "/external/../shared.md")},
				{List.of("choose", "c1", "authorize-target", "--target", " ")},
				{List.of("choose", "c1", "authorize-target", "--attempt", "1")},
				{List.of("choose", "c1", "use-authorized-source", "--target", "/external/shared.md")},
				{List.of("choose", "c1", "stop", "--target", "/external/shared.md")}
			};
	}

	/**
	 * Verifies that malformed input is rejected at ingress, before the core is called.
	 *
	 * @param args the arguments
	 */
	@Test(dataProvider = "invalidInput")
	public void rejectsInvalidInput(List<String> args)
	{
		// Requirement: R-I2
		FakeCore core = new FakeCore();
		StringBuilder output = new StringBuilder();
		int exit = run(core, args, output);
		requireThat(exit, "exit").isEqualTo(2);
		requireThat(core.calls, "calls").isEmpty();
		JsonNode json = JSON.readTree(output.toString());
		requireThat(json.get("status").asString(), "status").isEqualTo("invalid-input");
		requireThat(json.get("message").asString(), "message").contains("usage:");
	}

	/**
	 * Returns requests for the usage text.
	 *
	 * @return the arguments of each request
	 */
	@DataProvider
	public Object[][] helpRequests()
	{
		return new Object[][]
			{
				{List.of("--help")},
				{List.of("-h")},
				{List.of("help")},
				{List.of("start", "--help")},
				{List.of("choose", "c1", "-h")}
			};
	}

	/**
	 * Verifies that a request for help prints the usage with a zero exit code, before the core is called.
	 *
	 * @param args the arguments
	 */
	@Test(dataProvider = "helpRequests")
	public void printsUsageOnRequest(List<String> args)
	{
		FakeCore core = new FakeCore();
		StringBuilder output = new StringBuilder();
		int exit = run(core, args, output);
		requireThat(exit, "exit").isEqualTo(0);
		requireThat(core.calls, "calls").isEmpty();
		JsonNode json = JSON.readTree(output.toString());
		requireThat(json.get("status").asString(), "status").isEqualTo("help");
		requireThat(json.get("message").asString(), "message").isEqualTo(Commands.USAGE);
	}

	/**
	 * Verifies that a refusal is printed as JSON with a nonzero exit code.
	 */
	@Test
	public void printsRefusal()
	{
		// Requirement: R-L1
		FakeCore core = new FakeCore();
		core.failure = new CommandRefusedException("Campaign c1 was started by codex.");
		StringBuilder output = new StringBuilder();
		int exit = run(core, List.of("run", "c1"), output);
		requireThat(exit, "exit").isEqualTo(1);
		JsonNode json = JSON.readTree(output.toString());
		requireThat(json.get("status").asString(), "status").isEqualTo("refused");
		requireThat(json.get("message").asString(), "message").contains("codex");
	}

	/**
	 * Verifies that an I/O failure is printed as JSON with a nonzero exit code.
	 */
	@Test
	public void printsFailure()
	{
		// Requirement: R-I2
		FakeCore core = new FakeCore();
		core.failure = new IOException("disk full");
		StringBuilder output = new StringBuilder();
		int exit = run(core, List.of("run", "c1"), output);
		requireThat(exit, "exit").isEqualTo(1);
		JsonNode json = JSON.readTree(output.toString());
		requireThat(json.get("status").asString(), "status").isEqualTo("error");
		requireThat(json.get("message").asString(), "message").contains("disk full");
	}

	/**
	 * Verifies that the default slice leaves room under the skill's 10-minute tool timeout.
	 */
	@Test
	public void defaultSliceFitsToolTimeout()
	{
		// Requirement: R-I2
		SliceSettings settings = SliceSettings.DEFAULT;
		requireThat(settings.budget(), "budget").isEqualTo(Duration.ofMinutes(8));
		requireThat(settings.budget().plus(settings.grace()), "budgetAndGrace").
			isLessThan(Duration.ofMinutes(10));
	}
}
