package com.holdmyspot.cat.common.optimizer.cli.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessSandbox;
import com.holdmyspot.cat.common.optimizer.cli.OwnerSandbox;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.campaign.Applicability;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationParameters;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationRecord;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationRecord.CellResult;
import com.holdmyspot.cat.common.optimizer.campaign.Choice;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementIdentity;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.campaign.Throttle;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.cli.Command;
import com.holdmyspot.cat.common.optimizer.cli.CommandRefusedException;
import com.holdmyspot.cat.common.optimizer.cli.CommandResult;
import com.holdmyspot.cat.common.optimizer.cli.Commands;
import com.holdmyspot.cat.common.optimizer.cli.Core;
import com.holdmyspot.cat.common.optimizer.cli.InvalidCommandException;
import com.holdmyspot.cat.common.optimizer.cli.OptimizerCore;
import com.holdmyspot.cat.common.optimizer.contract.CertificationScope;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.PromptConfiguration;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveryResult;
import com.holdmyspot.cat.common.optimizer.execution.DeliveryReport;
import com.holdmyspot.cat.common.optimizer.execution.SliceSettings;
import com.holdmyspot.cat.common.optimizer.inventory.DeliveryChange;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.report.CertificationStatus;
import com.holdmyspot.cat.common.optimizer.report.CertifiedEnvironment;
import com.holdmyspot.cat.common.optimizer.report.ReportEvidence;
import com.holdmyspot.cat.common.optimizer.report.StatusReport;
import com.holdmyspot.cat.common.optimizer.port.test.FakeSessionPort;
import com.holdmyspot.cat.common.secrets.CommandsSecrets;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;

/**
 * Tests {@link Commands} over the real {@link OptimizerCore} of a project without campaigns.
 */
public final class CommandsTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/**
	 * The reason that {@link #WINDOWS_REFUSAL} gives.
	 */
	private static final String WINDOWS_REFUSAL_REASON = "harness-a has no sandbox on native Windows";
	/**
	 * The reason that a session without a sandbox gives.
	 */
	private static final String NO_SANDBOX_REASON = "harness-a runs this session without a sandbox";
	/**
	 * A trial harness that sandboxes its trials everywhere except on native Windows.
	 */
	private static final HarnessSandbox WINDOWS_REFUSAL = platform ->
	{
		if (platform.isWindows())
			return Optional.of(WINDOWS_REFUSAL_REASON);
		return Optional.empty();
	};

	/**
	 * Creates the test.
	 */
	public CommandsTest()
	{
	}

	/**
	 * A Git work tree with the prompt file {@code CLAUDE.md} and neither a campaign nor a prompt test configuration,
	 * whose commands run on the real core of a new command scope. Each call that a command makes to the core is
	 * recorded with its arguments. Without a campaign or a configuration, the core refuses every command that reaches
	 * it, so no command starts a session.
	 */
	private static final class Project
	{
		private final Path root;
		private final List<String> calls = new ArrayList<>();
		private int ceiling;
		private String goal;
		private Path extraPermissions;

		/**
		 * Creates a project.
		 *
		 * @param root the project directory
		 */
		private Project(Path root)
		{
			this.root = root;
		}

		/**
		 * Creates a project in a new temporary directory.
		 *
		 * @param cleanup removes the directory
		 * @return the project
		 * @throws IOException if the project cannot be written
		 */
		static Project create(TemporaryDirectories cleanup) throws IOException
		{
			Path root = cleanup.add(Files.createTempDirectory("commands")).toRealPath();
			Files.createDirectory(root.resolve(".git"));
			Files.writeString(root.resolve("CLAUDE.md"), "Be brief.\n");
			return new Project(root);
		}

		/**
		 * Runs a command on Linux, where the trials are sandboxed.
		 *
		 * @param args   the arguments
		 * @param output receives the printed JSON
		 * @return the exit code
		 */
		int run(List<String> args, StringBuilder output)
		{
			return run(_ -> Optional.empty(), Platform.LINUX_X64, args, output);
		}

		/**
		 * Runs a command on a platform.
		 *
		 * @param trialSandbox decides whether the harness of the campaign's trials sandboxes them on {@code platform}
		 * @param platform     the platform that runs the command
		 * @param args         the arguments
		 * @param output       receives the printed JSON
		 * @return the exit code
		 */
		int run(HarnessSandbox trialSandbox, Platform platform, List<String> args, StringBuilder output)
		{
			return run(trialSandbox, Optional::empty, platform, args, output);
		}

		/**
		 * Runs a command on a platform from a session.
		 *
		 * @param trialSandbox decides whether the harness of the campaign's trials sandboxes them on {@code platform}
		 * @param ownerSandbox says why the session that runs the command has no sandbox in effect
		 * @param platform     the platform that runs the command
		 * @param args         the arguments
		 * @param output       receives the printed JSON
		 * @return the exit code
		 */
		int run(HarnessSandbox trialSandbox, OwnerSandbox ownerSandbox, Platform platform, List<String> args,
			StringBuilder output)
		{
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			int exit;
			try (TestOptimizerCommandScope<FakeSessionPort> scope = TestOptimizerCommandScope.of(Harness.CLAUDE, root);
				PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
			{
				exit = new Commands(new RecordingCore(new OptimizerCore(scope)), JSON, ZoneOffset.UTC, trialSandbox,
					ownerSandbox, platform).execute(args, out);
			}
			output.append(bytes.toString(StandardCharsets.UTF_8));
			return exit;
		}

		/**
		 * Validates the arguments of a command.
		 *
		 * @param args the arguments
		 * @return the command
		 * @throws InvalidCommandException if the arguments are not a well-formed command
		 */
		Command parse(List<String> args)
		{
			try (TestOptimizerCommandScope<FakeSessionPort> scope = TestOptimizerCommandScope.of(Harness.CLAUDE, root))
			{
				return new Commands(new RecordingCore(new OptimizerCore(scope)), JSON, ZoneOffset.UTC,
					_ -> Optional.empty(), Optional::empty, Platform.LINUX_X64).parse(args);
			}
		}

		/**
		 * Renders a result as a command prints it.
		 *
		 * @param zone   the user's time zone
		 * @param result the result
		 * @return the JSON text
		 */
		String render(ZoneId zone, CommandResult result)
		{
			try (TestOptimizerCommandScope<FakeSessionPort> scope = TestOptimizerCommandScope.of(Harness.CLAUDE, root))
			{
				Commands commands = new Commands(new OptimizerCore(scope), JSON, zone, _ -> Optional.empty(),
					Optional::empty, Platform.LINUX_X64);
				return CommandsSecrets.getCommandsAccess().render(commands, result);
			}
		}

		/**
		 * Records each call of the commands and passes it on to the real core.
		 */
		private final class RecordingCore implements Core
		{
			private final Core core;

			/**
			 * Creates a recorder.
			 *
			 * @param core the core that answers each call
			 */
			RecordingCore(Core core)
			{
				this.core = core;
			}

			@Override
			public CommandResult start(Path target, int ceiling, String goal, Path extraPermissions,
				Set<Harness> evaluationHarnesses)
				throws CommandRefusedException, IOException, InterruptedException
			{
				calls.add("start " + target);
				Project.this.ceiling = ceiling;
				Project.this.goal = goal;
				Project.this.extraPermissions = extraPermissions;
				return core.start(target, ceiling, goal, extraPermissions, evaluationHarnesses);
			}

			@Override
			public CommandResult continueCampaign(String campaign)
				throws CommandRefusedException, IOException, InterruptedException
			{
				calls.add("continue " + campaign);
				return core.continueCampaign(campaign);
			}

			@Override
			public CommandResult status(String campaign) throws CommandRefusedException, IOException
			{
				calls.add("status " + campaign);
				return core.status(campaign);
			}

			@Override
			public CommandResult approve(String campaign, CertificationScope scope) throws CommandRefusedException,
				IOException
			{
				calls.add("approve " + campaign + " " + scope.id());
				return core.approve(campaign, scope);
			}

			@Override
			public CommandResult requestChanges(String campaign, String feedback)
				throws CommandRefusedException, IOException, InterruptedException
			{
				calls.add("request-changes " + campaign + " " + feedback);
				return core.requestChanges(campaign, feedback);
			}

			@Override
			public CommandResult decide(String campaign, Choice choice, int attempt, int reviewPoint)
				throws CommandRefusedException, IOException, InterruptedException
			{
				calls.add("decide " + campaign + " " + choice + " " + attempt + " " + reviewPoint);
				return core.decide(campaign, choice, attempt, reviewPoint);
			}

			@Override
			public CommandResult resolveConflict(String campaign, int resolution)
				throws CommandRefusedException, IOException, InterruptedException
			{
				calls.add("resolve " + campaign + " " + resolution);
				return core.resolveConflict(campaign, resolution);
			}

			@Override
			public CommandResult stop(String campaign) throws CommandRefusedException, IOException
			{
				calls.add("stop " + campaign);
				return core.stop(campaign);
			}
		}
	}

	/**
	 * Verifies that each command reaches the core with its validated arguments.
	 */
	@Test
	public void dispatchesEveryCommand() throws IOException
	{
		// Requirement: R-I2
		// Requirement: R-I17
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			StringBuilder output = new StringBuilder();
			List<List<String>> commands = List.of(
				List.of("start", "prompts/a.md"),
				List.of("continue", "c1"),
				List.of("status", "c1"),
				List.of("status", "prompts/a.md"),
				List.of("approve", "c1"),
				List.of("approve", "c1", "weakest_cell"),
				List.of("approve", "c1", "latest_versions"),
				List.of("approve", "c1", "described"),
				List.of("request-changes", "c1", "tighten the contract"),
				List.of("decide", "c1", "keep_improvement", "--attempt", "2"),
				List.of("decide", "c1", "more_evidence", "--review-point", "3"),
				List.of("decide", "c1", "stop"),
				List.of("stop", "c1"));
			for (List<String> command : commands)
				requireThat(project.run(command, output), "exit").withContext(command, "command").isEqualTo(1);
			requireThat(project.calls, "calls").isEqualTo(List.of(
				"start " + Path.of("prompts/a.md"),
				"continue c1",
				"status c1",
				"status prompts/a.md",
				"approve c1 weakest_cell",
				"approve c1 weakest_cell",
				"approve c1 latest_versions",
				"approve c1 described",
				"request-changes c1 tighten the contract",
				"decide c1 KEEP_IMPROVEMENT 2 0",
				"decide c1 MORE_EVIDENCE 0 3",
				"decide c1 STOP 0 0",
				"stop c1"));
		}
	}

	/**
	 * Returns each former command name with the name that replaced it, alone, with the arguments that it took and with
	 * {@code --background}.
	 *
	 * @return the arguments of each command line and the name that its refusal must give
	 */
	@DataProvider
	public Object[][] formerCommandNames()
	{
		return new Object[][]
			{
				{List.of("run"), "continue"},
				{List.of("run", "c1"), "continue"},
				{List.of("run", "c1", "--background"), "continue"},
				{List.of("confirm"), "approve"},
				{List.of("confirm", "c1"), "approve"},
				{List.of("confirm", "c1", "latest_versions"), "approve"},
				{List.of("confirm", "c1", "--background"), "approve"},
				{List.of("revise"), "request-changes"},
				{List.of("revise", "c1", "tighten the contract"), "request-changes"},
				{List.of("revise", "c1", "tighten the contract", "--background"), "request-changes"},
				{List.of("choose"), "decide"},
				{List.of("choose", "c1", "stop"), "decide"},
				{List.of("choose", "c1", "keep_improvement", "--attempt", "2"), "decide"},
				{List.of("choose", "c1", "resolution_1"), "decide"},
				{List.of("choose", "c1", "retry", "--background"), "decide"},
				{List.of("cancel"), "stop"},
				{List.of("cancel", "c1"), "stop"},
				{List.of("cancel", "c1", "--background"), "stop"}
			};
	}

	/**
	 * Verifies that a former command name is refused as invalid input, with a message that names the command that
	 * replaced it and how to run it, before the core is called, so that it is never an alias.
	 *
	 * @param args        the arguments
	 * @param replacement the name that replaced the former name
	 */
	@Test(dataProvider = "formerCommandNames")
	public void refusesFormerCommandName(List<String> args, String replacement) throws IOException
	{
		// Requirement: R-I17
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			StringBuilder output = new StringBuilder();
			int exit = project.run(args, output);

			requireThat(exit, "exit").isEqualTo(2);
			requireThat(project.calls, "calls").isEmpty();
			JsonNode json = JSON.readTree(output.toString());
			requireThat(json.get("status").asString(), "status").isEqualTo("invalid_input");
			String former = args.getFirst();
			requireThat(json.get("message").asString().lines().findFirst().orElseThrow(), "message").
				startsWith(former + " is now " + replacement + ": prompt-optimizer " + replacement + " <campaign>");
		}
	}

	/**
	 * Verifies that {@code --background} on a command that always runs in the foreground is refused, naming the four
	 * commands that accept it, before the core is called.
	 */
	@Test
	public void refusesBackgroundOnForegroundCommand() throws IOException
	{
		// Requirement: R-I17
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			for (List<String> args : List.of(List.of("status", "c1", "--background"),
				List.of("approve", "c1", "--background"), List.of("stop", "c1", "--background")))
			{
				Project project = Project.create(cleanup);
				StringBuilder output = new StringBuilder();
				requireThat(project.run(args, output), "exit").withContext(args, "args").isEqualTo(2);
				requireThat(project.calls, "calls").withContext(args, "args").isEmpty();
				requireThat(JSON.readTree(output.toString()).get("message").asString().lines().findFirst().orElseThrow(),
					"message").withContext(args, "args").
					isEqualTo("--background is accepted once by start, continue, request-changes and decide.");
			}
		}
	}

	/**
	 * Verifies that the usage text lists every command by its current name and no former name.
	 */
	@Test
	public void usageListsCurrentCommandNames()
	{
		// Requirement: R-I17
		List<String> listed = new ArrayList<>();
		for (String line : Commands.USAGE.lines().toList())
		{
			if (line.startsWith("  "))
				listed.add(line.strip().split(" ")[0]);
		}
		requireThat(listed, "listed").isEqualTo(List.of("start", "continue", "status", "approve", "request-changes",
			"decide", "stop", "contract", "contract"));
		for (String former : List.of("run", "confirm", "revise", "choose", "cancel"))
			requireThat(Commands.USAGE, "usage").doesNotContain(" " + former + " ");
	}

	/**
	 * Returns each command that starts or continues a campaign, and so can start a trial.
	 *
	 * @return the arguments of each command
	 */
	@DataProvider
	public Object[][] campaignCommands()
	{
		return new Object[][]
			{
				{List.of("start", "prompts/a.md")},
				{List.of("continue", "c1")},
				{List.of("approve", "c1")},
				{List.of("request-changes", "c1", "tighten the contract")},
				{List.of("decide", "c1", "stop")},
				{List.of("decide", "c1", "resolution_1")}
			};
	}

	/**
	 * Verifies that where the harness that runs a campaign's trials cannot sandbox them, a command that starts or
	 * continues a campaign is refused with that harness's reason, before it reaches the core.
	 *
	 * @param args the arguments
	 */
	@Test(dataProvider = "campaignCommands")
	public void refusesCampaignWithoutTrialSandbox(List<String> args) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			StringBuilder output = new StringBuilder();
			requireThat(project.run(WINDOWS_REFUSAL, Platform.WINDOWS_X64, args, output), "exit").isEqualTo(1);
			requireThat(project.calls, "calls").isEmpty();
			JsonNode result = JSON.readTree(output.toString());
			requireThat(result.get("status").asString(), "status").isEqualTo("refused");
			requireThat(result.get("message").asString(), "message").isEqualTo(WINDOWS_REFUSAL_REASON);
		}
	}

	/**
	 * Verifies that where the harness that runs a campaign's trials sandboxes them, native Windows included, a
	 * command that starts or continues a campaign reaches the core.
	 *
	 * @param args the arguments
	 */
	@Test(dataProvider = "campaignCommands")
	public void runsCampaignCommandWithTrialSandbox(List<String> args) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			for (Platform platform : List.of(Platform.WINDOWS_X64, Platform.LINUX_X64, Platform.MACOS_ARM64))
			{
				Project project = Project.create(cleanup);
				StringBuilder output = new StringBuilder();
				requireThat(project.run(_ -> Optional.empty(), platform, args, output), "exit").
					withContext(platform, "platform").isEqualTo(1);
				requireThat(project.calls, "calls").withContext(platform, "platform").size().isEqualTo(1);
			}
		}
	}

	/**
	 * Verifies that a command that starts or continues a campaign from a session without a sandbox in effect, on every
	 * platform, is refused with the reason before it reaches the core, so that nothing changes.
	 *
	 * @param args the arguments
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "campaignCommands")
	public void refusesCampaignFromSessionWithoutSandbox(List<String> args) throws IOException
	{
		// Requirement: R-I46
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			for (Platform platform : List.of(Platform.WINDOWS_X64, Platform.LINUX_X64, Platform.MACOS_ARM64))
			{
				Project project = Project.create(cleanup);
				StringBuilder output = new StringBuilder();
				requireThat(project.run(_ -> Optional.empty(), () -> Optional.of(NO_SANDBOX_REASON), platform, args,
					output), "exit").withContext(platform, "platform").isEqualTo(1);
				requireThat(project.calls, "calls").withContext(platform, "platform").isEmpty();
				JsonNode result = JSON.readTree(output.toString());
				requireThat(result.get("status").asString(), "status").isEqualTo("refused");
				requireThat(result.get("message").asString(), "message").isEqualTo(NO_SANDBOX_REASON);
			}
		}
	}

	/**
	 * Verifies that {@code status} and {@code stop}, which start no trial, reach the core from a session without a
	 * sandbox.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void runsStatusAndStopFromSessionWithoutSandbox() throws IOException
	{
		// Requirement: R-I46
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			StringBuilder output = new StringBuilder();
			OwnerSandbox none = () -> Optional.of(NO_SANDBOX_REASON);
			project.run(_ -> Optional.empty(), none, Platform.LINUX_X64, List.of("status", "c1"), output);
			project.run(_ -> Optional.empty(), none, Platform.LINUX_X64, List.of("stop", "c1"), output);
			requireThat(project.calls, "calls").isEqualTo(List.of("status c1", "stop c1"));
		}
	}

	/**
	 * Verifies that {@code status} and {@code stop}, which start no trial, reach the core even where the harness
	 * that runs the campaign's trials cannot sandbox them.
	 */
	@Test
	public void runsStatusAndStopWithoutTrialSandbox() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			StringBuilder output = new StringBuilder();
			requireThat(project.run(WINDOWS_REFUSAL, Platform.WINDOWS_ARM64, List.of("status", "c1"), output),
				"statusExit").isEqualTo(1);
			requireThat(project.run(WINDOWS_REFUSAL, Platform.WINDOWS_ARM64, List.of("stop", "c1"), output),
				"stopExit").isEqualTo(1);
			requireThat(project.calls, "calls").isEqualTo(List.of("status c1", "stop c1"));
		}
	}

	/**
	 * Verifies that {@code approve} with an unknown certification scope is refused with every choice named.
	 */
	@Test
	public void approveNamesEveryScopeChoice() throws IOException
	{
		// Requirement: R-C15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			StringBuilder output = new StringBuilder();
			requireThat(project.run(List.of("approve", "c1", "all_models"), output), "exit").isEqualTo(2);
			requireThat(project.calls, "calls").isEmpty();
			String message = JSON.readTree(output.toString()).get("message").asString();
			requireThat(message, "message").contains("weakest_cell").contains("latest_versions").contains("described");
		}
	}

	/**
	 * Verifies that the campaign ceiling accepts both endpoints of its range.
	 */
	@Test
	public void acceptsCampaignCeiling() throws IOException
	{
		// Requirement: R-E4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			for (String ceiling : List.of("1", "16"))
			{
				Project project = Project.create(cleanup);
				StringBuilder output = new StringBuilder();
				requireThat(project.run(List.of("start", "prompts/a.md", "--max-concurrency", ceiling), output),
					"exit").isEqualTo(1);
				requireThat(project.calls, "calls").isEqualTo(List.of("start " + Path.of("prompts/a.md")));
				requireThat(project.ceiling, "ceiling").isEqualTo(Integer.parseInt(ceiling));
			}
		}
	}

	/**
	 * Verifies that {@code start} passes the user's goal to the core in either option order, and no goal when the
	 * option is absent.
	 * <p>
	 * Each of the four invocations creates a physical project and a fresh command scope, then runs argument parsing
	 * through the real core's refusal path. This retains the boundary that records the goal actually received by
	 * the core; the missing campaign and configuration prevent session dispatch.
	 */
	@Test
	public void passesTheGoal() throws IOException
	{
		// Requirement: R-C24
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			String goal = "Read files whole without slowing searches.";
			List<List<String>> commands = List.of(
				List.of("start", "prompts/a.md", "--goal", goal),
				List.of("start", "prompts/a.md", "--goal", goal, "--max-concurrency", "3"),
				List.of("start", "prompts/a.md", "--max-concurrency", "3", "--goal", goal));
			for (List<String> command : commands)
			{
				Project project = Project.create(cleanup);
				StringBuilder output = new StringBuilder();
				requireThat(project.run(command, output), "exit").isEqualTo(1);
				requireThat(project.calls, "calls").isEqualTo(List.of("start " + Path.of("prompts/a.md")));
				requireThat(project.goal, "goal").isEqualTo(goal);
			}
			Project project = Project.create(cleanup);
			requireThat(project.run(List.of("start", "prompts/a.md"), new StringBuilder()), "exit").isEqualTo(1);
			requireThat(project.goal, "absentGoal").isNull();
		}
	}

	/**
	 * Verifies that {@code start} passes the file of extra permissions to the core in any option order, and none when
	 * the option is absent.
	 */
	@Test
	public void passesTheExtraPermissionsFile() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			List<List<String>> commands = List.of(
				List.of("start", "prompts/a.md", "--extra-permissions", "needs/registry.json"),
				List.of("start", "prompts/a.md", "--extra-permissions", "needs/registry.json", "--goal", "Be brief."),
				List.of("start", "prompts/a.md", "--max-concurrency", "3", "--extra-permissions", "needs/registry.json",
					"--goal", "Be brief."));
			for (List<String> command : commands)
			{
				Project project = Project.create(cleanup);
				requireThat(project.run(command, new StringBuilder()), "exit").isEqualTo(1);
				requireThat(project.extraPermissions, "extraPermissions").isEqualTo(Path.of("needs/registry.json"));
			}
			Project project = Project.create(cleanup);
			requireThat(project.run(List.of("start", "prompts/a.md"), new StringBuilder()), "exit").isEqualTo(1);
			requireThat(project.extraPermissions, "absentExtraPermissions").isNull();
		}
	}

	/**
	 * Verifies that {@code start} refuses a missing, blank or repeated file of extra permissions before calling the
	 * core.
	 */
	@Test
	public void rejectsInvalidExtraPermissionsOption() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			List<List<String>> options = List.of(
				List.of("--extra-permissions"),
				List.of("--extra-permissions", " "),
				List.of("--extra-permissions", "a.json", "--extra-permissions", "b.json"));
			for (List<String> option : options)
			{
				Project project = Project.create(cleanup);
				StringBuilder output = new StringBuilder();
				List<String> args = new ArrayList<>(List.of("start", "prompts/a.md"));
				args.addAll(option);
				requireThat(project.run(args, output), "exit").isEqualTo(2);
				requireThat(project.calls, "calls").isEmpty();
				requireThat(JSON.readTree(output.toString()).get("message").asString(), "message").
					contains("--extra-permissions");
			}
		}
	}

	/**
	 * Verifies that malformed ceilings are refused with the range before calling the core.
	 */
	@Test
	public void rejectsInvalidCampaignCeiling() throws IOException
	{
		// Requirement: R-E4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			List<List<String>> options = List.of(
				List.of("--max-concurrency", "0"),
				List.of("--max-concurrency", "17"),
				List.of("--max-concurrency", "x"),
				List.of("--max-concurrency", "1", "--max-concurrency", "16"),
				List.of("--max-concurrency"));
			for (List<String> option : options)
			{
				Project project = Project.create(cleanup);
				StringBuilder output = new StringBuilder();
				List<String> args = new ArrayList<>(List.of("start", "prompts/a.md"));
				args.addAll(option);
				requireThat(project.run(args, output), "exit").isEqualTo(2);
				requireThat(project.calls, "calls").isEmpty();
				String message = JSON.readTree(output.toString()).get("message").asString();
				requireThat(message.lines().findFirst().orElseThrow(), "message").contains("1..16");
			}
		}
	}

	/**
	 * Verifies that {@code start} accepts distinct evaluation harnesses in either order and alongside other options.
	 */
	@Test
	public void parsesEvaluationHarnesses() throws IOException
	{
		// Requirement: R-L6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			Harness first = Harness.values()[0];
			Harness second = Harness.values()[1];
			Command.Start start = (Command.Start) project.parse(List.of("start", "prompts/a.md", "--harness",
				first.commandName(),
				"--goal", "Be brief.", "--harness", second.commandName()));
			Command reversed = project.parse(List.of("start", "prompts/a.md", "--harness", second.commandName(),
				"--harness", first.commandName(), "--goal", "Be brief."));

			requireThat(start.evaluationHarnesses(), "evaluationHarnesses").isEqualTo(Set.of(first, second));
			requireThat(start, "start").isEqualTo(reversed);
		}
	}

	/**
	 * Verifies that {@code start} distinguishes an omitted selection from an explicit single harness.
	 */
	@Test
	public void preservesOmittedEvaluationHarnesses() throws IOException
	{
		// Requirement: R-L6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			Harness harness = Harness.values()[0];
			Command.Start omitted = (Command.Start) project.parse(List.of("start", "prompts/a.md"));
			Command.Start explicit = (Command.Start) project.parse(List.of("start", "prompts/a.md", "--harness",
				harness.commandName()));

			requireThat(omitted.evaluationHarnesses(), "omitted").isEmpty();
			requireThat(explicit.evaluationHarnesses(), "explicit").isEqualTo(Set.of(harness));
		}
	}

	/**
	 * Verifies that {@code start} refuses absent, unknown or repeated harness names before calling the core.
	 */
	@Test
	public void refusesInvalidEvaluationHarnesses() throws IOException
	{
		// Requirement: R-L6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			String name = Harness.values()[0].commandName();
			Map<List<String>, String> invalidOptions = Map.of(
				List.of("--harness"), "--harness needs a value",
				List.of("--harness", "unknown-harness"), "Unknown evaluation harness 'unknown-harness'.",
				List.of("--harness", name, "--goal", "Be brief.", "--harness", name),
				"Repeated evaluation harness '" + name + "'.");
			for (Map.Entry<List<String>, String> invalid : invalidOptions.entrySet())
			{
				List<String> args = new ArrayList<>(List.of("start", "prompts/a.md"));
				args.addAll(invalid.getKey());
				StringBuilder output = new StringBuilder();
				requireThat(project.run(args, output), "exit").isEqualTo(2);
				requireThat(JSON.readTree(output.toString()).get("message").asString(), "message").
					contains(invalid.getValue());
				requireThat(project.calls, "calls").isEmpty();
			}
		}
	}

	/**
	 * Verifies that command usage lists the ceiling option and its range.
	 */
	@Test
	public void usageListsCampaignCeiling()
	{
		// Requirement: R-E4
		requireThat(Commands.USAGE, "usage").contains(
			"start <target> [--goal <text>] [--max-concurrency <1..16>] [--extra-permissions <file>]");
	}

	/**
	 * Verifies that the usage text lists exactly the certification scopes that {@code approve} accepts, so that each
	 * spelling it shows reaches the core.
	 */
	@Test
	public void usageListsAcceptedScopes() throws IOException
	{
		// Requirement: R-C15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			String line = Commands.USAGE.lines().map(String::strip).filter(text -> text.startsWith("approve ")).
				findFirst().orElseThrow();
			String alternatives = line.substring(line.lastIndexOf('<') + 1, line.lastIndexOf('>'));
			List<String> shown = new ArrayList<>();
			for (String alternative : alternatives.split("\\|"))
				shown.add(alternative.strip());
			List<String> accepted = new ArrayList<>();
			for (CertificationScope scope : CertificationScope.values())
				accepted.add(scope.id());
			requireThat(shown, "shown").isEqualTo(accepted);
			for (String scope : shown)
			{
				Project project = Project.create(cleanup);
				StringBuilder output = new StringBuilder();
				requireThat(project.run(List.of("approve", "c1", scope), output), "exit").isEqualTo(1);
				requireThat(project.calls, "calls").isEqualTo(List.of("approve c1 " + scope));
			}
		}
	}

	/**
	 * Returns each {@code decide} whose option and choice disagree, with the choice that the refusal must name as it
	 * is typed.
	 *
	 * @return the arguments and the choice that the refusal names
	 */
	@DataProvider
	public Object[][] mismatchedChoiceOptions()
	{
		return new Object[][]
			{
				{List.of("decide", "c1", "keep_improvement"), "keep_improvement"},
				{List.of("decide", "c1", "stop", "--attempt", "1"), "keep_improvement"},
				{List.of("decide", "c1", "more_evidence"), "more_evidence"},
				{List.of("decide", "c1", "stop", "--review-point", "1"), "more_evidence"}
			};
	}

	/**
	 * Verifies that a refused {@code decide} names the choice that its option belongs to as the user types it.
	 *
	 * @param args   the arguments
	 * @param choice the choice that the refusal names
	 */
	@Test(dataProvider = "mismatchedChoiceOptions")
	public void optionRefusalNamesAcceptedChoice(List<String> args, String choice) throws IOException
	{
		// Requirement: R-I2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			StringBuilder output = new StringBuilder();
			requireThat(project.run(args, output), "exit").isEqualTo(2);
			String message = JSON.readTree(output.toString()).get("message").asString();
			requireThat(message.lines().findFirst().orElseThrow(), "message").contains(choice);
		}
	}

	/**
	 * Verifies that the authority-warning choice that continues within the confirmed authority reaches the core.
	 */
	@Test
	public void dispatchesAuthorityWarningChoices() throws IOException
	{
		// Requirement: R-W4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			StringBuilder output = new StringBuilder();
			requireThat(project.run(List.of("decide", "c1", "use_authorized_source"), output), "exit").isEqualTo(1);
			requireThat(project.calls, "calls").isEqualTo(List.of("decide c1 USE_AUTHORIZED_SOURCE 0 0"));
		}
	}

	/**
	 * Verifies that no choice makes an external file editable: {@code authorize_target} is an unknown choice and
	 * {@code --target} an unknown option, each rejected before the core is called.
	 */
	@Test
	public void rejectsExternalTargetAuthorization() throws IOException
	{
		// Requirement: R-W4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			StringBuilder choiceOutput = new StringBuilder();
			int choiceExit = project.run(List.of("decide", "c1", "authorize_target", "--target", "/external/shared.md"),
				choiceOutput);
			StringBuilder optionOutput = new StringBuilder();
			int optionExit = project.run(List.of("decide", "c1", "use_authorized_source", "--target",
				"/external/shared.md"), optionOutput);

			requireThat(choiceExit, "choiceExit").isEqualTo(2);
			requireThat(JSON.readTree(choiceOutput.toString()).get("message").asString(), "choiceMessage").
				contains("Unknown choice: 'authorize_target'");
			requireThat(optionExit, "optionExit").isEqualTo(2);
			requireThat(JSON.readTree(optionOutput.toString()).get("message").asString(), "optionMessage").
				contains("unknown option --target");
			requireThat(project.calls, "calls").isEmpty();
			requireThat(Commands.USAGE, "usage").doesNotContain("--target");
		}
	}

	/**
	 * Verifies that a numbered conflict resolution is accepted by the command parser.
	 */
	@Test
	public void parsesConflictResolution() throws IOException
	{
		// Requirement: R-D13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			StringBuilder output = new StringBuilder();
			requireThat(project.run(List.of("decide", "c1", "resolution_2"), output), "exit").isEqualTo(1);
			requireThat(project.calls, "calls").isEqualTo(List.of("resolve c1 2"));
		}
	}

	/**
	 * Verifies the output schema: the status object plus harness, summary, proposal_file, deliveryResult and message,
	 * with the proposal's path and none of its text.
	 */
	@Test
	public void printsStatusObjectWithExtensions() throws IOException
	{
		// Requirement: R-I2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			StatusReport status = status();
			Project project = Project.create(cleanup);
			Path proposalFile = project.root.resolve("campaign").resolve("proposal.md");
			String text = project.render(ZoneOffset.UTC,
				new CommandResult(status, Harness.CODEX, proposalFile, null, "a notice"));
			JsonNode json = JSON.readTree(text);
			requireThat(json.get("status").asString(), "status").isEqualTo(status.status().label());
			requireThat(json.get("campaign").asString(), "campaign").isEqualTo(status.campaignId());
			requireThat(json.has("choices"), "hasChoices").isTrue();
			requireThat(json.get("harness").asString(), "harness").isEqualTo("codex");
			requireThat(json.get("summary").asString(), "summary").isEqualTo(status.summary(ZoneOffset.UTC));
			requireThat(json.get("proposal_file").asString(), "proposalFile").isEqualTo(proposalFile.toString());
			requireThat(json.has("proposal"), "hasProposalText").isFalse();
			requireThat(json.get("delivery_result").isNull(), "deliveryResultIsNull").isTrue();
			requireThat(json.get("message").asString(), "message").isEqualTo("a notice");
			requireThat(text.lines().count(), "lines").isEqualTo(1L);
		}
	}

	/**
	 * Returns the status of a confirmed campaign without evidence.
	 *
	 * @return the status
	 */
	private static StatusReport status()
	{
		return StatusReport.of(new Builder().confirmed().build(), ReportEvidence.NONE);
	}

	/**
	 * Verifies that the printed summary shows times in the zone that the commands were created with, while the status
	 * object keeps the UTC instant.
	 */
	@Test
	public void printsSummaryTimesInUserTimeZone() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			StatusReport held = StatusReport.of(new Builder().confirmed().
				throttle(new Throttle(Instant.parse("2026-10-09T21:13:08Z"), 2, 1, 2)).build(), ReportEvidence.NONE);
			JsonNode json = JSON.readTree(Project.create(cleanup).render(ZoneId.of("America/Toronto"),
				CommandResult.of(held, Harness.CLAUDE)));

			requireThat(json.path("concurrency").path("hold_until").asString(), "holdUntil").
				isEqualTo("2026-10-09T21:13:08Z");
			requireThat(json.get("summary").asString(), "summary").contains("hold until 2026-10-09T17:13:08-04:00");
		}
	}

	/**
	 * Verifies that a delivery appears with its diff, the paths it wrote, base-only changes and applicability, and with
	 * no other field.
	 * <p>
	 * Rendering uses a physical project and the real command scope so the supplied delivery result passes through
	 * the command's JSON renderer with its normal collaborators. The result is supplied directly; no delivery or
	 * trial runs.
	 */
	@Test
	public void printsDelivery() throws IOException
	{
		// Requirement: R-I2
		DeliveryResult.Delivered delivered = new DeliveryResult.Delivered(Outcome.CERTIFIED,
			new Applicability(Applicability.Status.HISTORICAL, true, List.of(MeasurementCondition.MODEL), List.of(),
				List.of(MeasurementCondition.PROMPT_SOURCES)),
			List.of(Path.of("prompts/a.md")), List.of(Path.of("notes.md")),
			List.of(new DeliveryChange.Create(4, Path.of("prompts/a.md"),
				new FileContent("x".getBytes(StandardCharsets.UTF_8)), Set.of())),
			"diff --git a/prompts/a.md b/prompts/a.md");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			String text = Project.create(cleanup).render(ZoneOffset.UTC, new CommandResult(status(), Harness.CLAUDE,
				null, new DeliveryReport(delivered, delivered.applicability()), null));
			JsonNode delivery = JSON.readTree(text).get("delivery_result");
			requireThat(delivery.get("result").asString(), "result").isEqualTo("delivered");
			requireThat(delivery.get("label").asString(), "label").isEqualTo("certified");
			requireThat(delivery.get("diff").asString(), "diff").isEqualTo("diff --git a/prompts/a.md b/prompts/a.md");
			requireThat(delivery.get("base_only_changes").get(0).asString(), "baseOnly").isEqualTo("notes.md");
			requireThat(delivery.get("base_only_prompt_source_changes").get(0).asString(), "sources").
				isEqualTo(Path.of("prompts/a.md").toString());
			JsonNode written = delivery.get("delivered_changes").get(0);
			requireThat(written.get("path").asString(), "writtenPath").isEqualTo(Path.of("prompts/a.md").toString());
			requireThat(written.get("kind").asString(), "writtenKind").isEqualTo("created");
			JsonNode applicability = delivery.get("applicability");
			requireThat(applicability.get("status").asString(), "applicability").isEqualTo("historical");
			requireThat(applicability.get("prompt_content_changed").asBoolean(), "promptContentChanged").isTrue();
			requireThat(applicability.get("changed").get(0).asString(), "changed").isEqualTo("model");
			requireThat(applicability.get("unchecked").get(0).asString(), "unchecked").isEqualTo("prompt_sources");
			requireThat(new HashSet<>(delivery.propertyNames()), "fields").isEqualTo(Set.of("result", "label",
				"applicability", "delivered_changes", "base_only_prompt_source_changes", "base_only_changes", "diff"));
		}
	}

	/**
	 * Verifies that a certified delivery shows the environment that the certification was measured in.
	 * <p>
	 * A physical project and fresh command scope provide the real command renderer. The certification record and
	 * delivery result are supplied directly, preserving the environment-to-output boundary without a campaign run.
	 */
	@Test
	public void printsCertifiedEnvironment() throws IOException
	{
		// Requirement: R-M7
		Applicability applicability = new Applicability(Applicability.Status.CURRENT, false, List.of(), List.of(),
			List.of(MeasurementCondition.MODEL));
		DeliveryResult.Delivered delivered = new DeliveryResult.Delivered(Outcome.CERTIFIED, applicability,
			List.of(Path.of("CLAUDE.md")), List.of(), List.of(), "diff --git a/CLAUDE.md b/CLAUDE.md");
		CertifiedEnvironment environment = CertifiedEnvironment.of(certificationRecord(1),
			applicability.unchecked());
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			String text = Project.create(cleanup).render(ZoneOffset.UTC, new CommandResult(status(), Harness.CLAUDE,
				null, new DeliveryReport(delivered, applicability, environment), null));

			JsonNode delivery = JSON.readTree(text).get("delivery_result");
			requireThat(delivery.get("environment").asString(), "environment").
				isEqualTo(String.join("\n", environment.lines()));
		}
	}

	/**
	 * Verifies that the status of a target that only a certification history describes is one object with the
	 * latest record of each harness, with its index, scope and applicability, the earlier records, and a summary that
	 * names them, and no campaign, harness, proposal file or delivery.
	 */
	@Test
	public void printsCertificationRecord() throws IOException
	{
		// Requirement: R-M6
		CertificationRecord first = certificationRecord(1);
		CertificationRecord record = certificationRecord(2);
		Applicability applicability = new Applicability(Applicability.Status.HISTORICAL, true,
			List.of(MeasurementCondition.JUDGE), List.of(), List.of(MeasurementCondition.MODEL));
		JsonNode json;
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			json = JSON.readTree(Project.create(cleanup).render(ZoneOffset.UTC, CommandResult.of(new CertificationStatus(
				List.of(new CertificationStatus.Latest(record, applicability)), List.of(first)))));
		}

		requireThat(json.get("status").asString(), "status").isEqualTo("certified");
		requireThat(json.get("campaign").isNull(), "campaignIsNull").isTrue();
		requireThat(json.get("target").asString(), "target").isEqualTo("CLAUDE.md");
		requireThat(json.get("latest").size(), "latestSize").isEqualTo(1);
		JsonNode latest = json.get("latest").get(0);
		requireThat(latest.get("harness").asString(), "harness").isEqualTo("provider-1");
		requireThat(latest.has("evaluation_harnesses"), "hasEvaluationHarnesses").isFalse();
		requireThat(latest.get("attempt").asInt(), "attempt").isEqualTo(2);
		requireThat(latest.get("scope").asString(), "scope").isEqualTo("latest_versions");
		requireThat(json.get("earlier").size(), "earlierSize").isEqualTo(1);
		requireThat(json.get("earlier").get(0).get("attempt").asInt(), "earlierAttempt").isEqualTo(1);
		requireThat(json.get("earlier").get(0).has("applicability"), "earlierHasApplicability").isFalse();
		JsonNode applicabilityNode = latest.get("applicability");
		requireThat(applicabilityNode.get("status").asString(), "applicability").isEqualTo("historical");
		requireThat(applicabilityNode.get("prompt_content_changed").asBoolean(), "promptContentChanged").isTrue();
		requireThat(applicabilityNode.get("changed").get(0).asString(), "changed").isEqualTo("judge");
		requireThat(applicabilityNode.get("unchecked").get(0).asString(), "unchecked").isEqualTo("model");
		requireThat(json.get("harness").isNull(), "harnessIsNull").isTrue();
		requireThat(json.get("summary").asString(), "summary").isEqualTo("""
			Certification history of CLAUDE.md
			Latest certification in provider-1: scope latest_versions (every cell of each model family's latest \
			version)
			Attempt 2: certified (historical; changed: prompt_content, judge)
			  Not checked: trial model.

			Certified for the following environment:

			provider-1 harness_version-1
			Plugins: trial_plugins-1 (version not recorded)
			Model: model-a, reasoning reasoning-1 (not checked)
			Prompt files loaded: none
			Harness settings: the project's loader_files-1, with your connection settings and tool restrictions \
			carried over
			Trial setup: as introduced in CAT session_environment_release-1

			Trials ran with this machine's programs and outside sources, such as MCP servers and websites, which CAT \
			neither records nor controls. To limit changes to them, run the campaign, and the sessions that later use \
			the prompt, in the same container image.

			Earlier deliveries:
			  Attempt 1 in provider-1: certified, scope latest_versions""");
		requireThat(json.get("proposal_file").isNull(), "proposalFileIsNull").isTrue();
		requireThat(json.get("delivery_result").isNull(), "deliveryResultIsNull").isTrue();
		requireThat(json.get("message").isNull(), "messageIsNull").isTrue();
	}

	/**
	 * A joint certification reports one attempt with all measured evaluators and the frozen snapshot.
	 *
	 * @throws IOException if the project fixture cannot be created
	 */
	@Test
	public void printsJointCertificationRecord() throws IOException
	{
		// Requirement: R-L9
		MeasurementIdentity identity = new MeasurementIdentity(Map.of(), Map.of(
			"codex:test", Map.of(MeasurementCondition.PROVIDER, "codex"),
			"claude:test", Map.of(MeasurementCondition.PROVIDER, "claude")));
		List<CellResult> cells = List.of(new CellResult("test", "codex:test", 100, 0, Map.of(), Map.of()),
			new CellResult("test", "claude:test", 100, 0, Map.of(), Map.of()));
		Sha256 snapshot = new Sha256("1".repeat(64));
		CertificationRecord record = new CertificationRecord(Path.of("CLAUDE.md"), 1,
			CertificationScope.LATEST_VERSIONS, CertificationParameters.current(1, Contract.DEFAULT_MINIMUM_PASS_RATE),
			cells, Map.of(), identity, Optional.of(snapshot));
		Applicability applicability = new Applicability(Applicability.Status.CURRENT, false, List.of(), List.of());
		CertificationStatus status = new CertificationStatus(
			List.of(new CertificationStatus.Latest(record, applicability)), List.of());
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			JsonNode json = JSON.readTree(Project.create(cleanup).render(ZoneOffset.UTC, CommandResult.of(status)));
			requireThat(json.get("latest").size(), "latestSize").isEqualTo(1);
			JsonNode latest = json.get("latest").get(0);
			requireThat(latest.get("evaluation_harnesses").toString(), "evaluationHarnesses").
				isEqualTo("[\"claude\",\"codex\"]");
			requireThat(latest.get("snapshot").asString(), "snapshot").isEqualTo(snapshot.hex());
			requireThat(json.get("summary").asString(), "summary").contains("Latest certification in claude and codex");
		}
	}

	/**
	 * Returns a certification of {@code CLAUDE.md} under the latest-versions scope, measured by the default
	 * conditions.
	 *
	 * @param index the certification index
	 * @return the record
	 */
	private static CertificationRecord certificationRecord(int index)
	{
		return new CertificationRecord(Path.of("CLAUDE.md"), index, CertificationScope.LATEST_VERSIONS,
			CertificationParameters.current(index, Contract.DEFAULT_MINIMUM_PASS_RATE), List.of(), Map.of(),
			Builder.OBSERVED);
	}

	/**
	 * Verifies that a command's notices follow the status summary, one per line.
	 */
	@Test
	public void appendsNoticesToSummary() throws IOException
	{
		// Requirement: R-W9
		StatusReport status = status();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			String text = Project.create(cleanup).render(ZoneOffset.UTC, CommandResult.of(status, Harness.CLAUDE).
				withNotices(List.of("First notice.", "Second notice.")));

			requireThat(JSON.readTree(text).get("summary").asString(), "summary").
				isEqualTo(status.summary(ZoneOffset.UTC) + "\nFirst notice.\nSecond notice.");
		}
	}

	/**
	 * Verifies that a failed delivery names why nothing was delivered.
	 * <p>
	 * A physical project and fresh command scope provide the real command renderer. The supplied verification
	 * failure exercises its JSON output without attempting delivery or starting a session.
	 */
	@Test
	public void printsFailedDelivery() throws IOException
	{
		// Requirement: R-I2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			String text = Project.create(cleanup).render(ZoneOffset.UTC, new CommandResult(status(), Harness.CLAUDE,
				null, new DeliveryReport(new DeliveryResult.VerificationFailed(List.of(Path.of("a.md"))), null), null));
			JsonNode delivery = JSON.readTree(text).get("delivery_result");
			requireThat(delivery.get("result").asString(), "result").isEqualTo("verification_failed");
			requireThat(delivery.get("mismatched").get(0).asString(), "mismatched").isEqualTo("a.md");
		}
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
				{List.of("start", "prompts/a.md", "--goal")},
				{List.of("start", "prompts/a.md", "--goal", " ")},
				{List.of("start", "prompts/a.md", "--goal", "one", "--goal", "two")},
				{List.of("continue")},
				{List.of("continue", "c1", "extra")},
				{List.of("continue", "../escape")},
				{List.of("status")},
				{List.of("status", " ")},
				{List.of("status", "c1", "extra")},
				{List.of("approve", "")},
				{List.of("approve", "c1", "all_models")},
				{List.of("approve", "c1", "latest_versions", "extra")},
				{List.of("stop", "-rf")},
				{List.of("stop", "c1", "extra")},
				{List.of("status", "c1", "--background")},
				{List.of("approve", "c1", "--background")},
				{List.of("continue", "c1", "--background", "--background")},
				{List.of("request-changes", "c1")},
				{List.of("request-changes", "c1", "  ")},
				{List.of("decide", "c1")},
				{List.of("decide", "c1", "no-such-choice")},
				{List.of("decide", "c1", "KEEP_IMPROVEMENT", "--attempt", "1")},
				{List.of("decide", "c1", "keep_improvement")},
				{List.of("decide", "c1", "keep_improvement", "--attempt", "0")},
				{List.of("decide", "c1", "keep_improvement", "--attempt", "x")},
				{List.of("decide", "c1", "stop", "--attempt", "1")},
				{List.of("decide", "c1", "more_evidence")},
				{List.of("decide", "c1", "stop", "--review-point", "1")},
				{List.of("decide", "c1", "stop", "--bogus", "1")},
				{List.of("decide", "c1", "stop", "--attempt")},
				{List.of("decide", "c1", "authorize_target")},
				{List.of("decide", "c1", "stop", "--target", "/external/shared.md")}
			};
	}

	/**
	 * Verifies that malformed input is rejected at ingress, before the core is called.
	 *
	 * @param args the arguments
	 */
	@Test(dataProvider = "invalidInput")
	public void rejectsInvalidInput(List<String> args) throws IOException
	{
		// Requirement: R-I2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			StringBuilder output = new StringBuilder();
			int exit = project.run(args, output);
			requireThat(exit, "exit").isEqualTo(2);
			requireThat(project.calls, "calls").isEmpty();
			JsonNode json = JSON.readTree(output.toString());
			requireThat(json.get("status").asString(), "status").isEqualTo("invalid_input");
			requireThat(json.get("message").asString(), "message").contains("usage:");
		}
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
				{List.of("decide", "c1", "-h")}
			};
	}

	/**
	 * Verifies that a request for help prints the usage with a zero exit code, before the core is called.
	 *
	 * @param args the arguments
	 */
	@Test(dataProvider = "helpRequests")
	public void printsUsageOnRequest(List<String> args) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			StringBuilder output = new StringBuilder();
			int exit = project.run(args, output);
			requireThat(exit, "exit").isEqualTo(0);
			requireThat(project.calls, "calls").isEmpty();
			JsonNode json = JSON.readTree(output.toString());
			requireThat(json.get("status").asString(), "status").isEqualTo("help");
			requireThat(json.get("message").asString(), "message").isEqualTo(Commands.USAGE);
		}
	}

	/**
	 * Verifies that a refusal is printed as JSON with a nonzero exit code.
	 */
	@Test
	public void printsRefusal() throws IOException
	{
		// Requirement: R-L1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			StringBuilder output = new StringBuilder();
			int exit = project.run(List.of("continue", "c1"), output);
			requireThat(exit, "exit").isEqualTo(1);
			JsonNode json = JSON.readTree(output.toString());
			requireThat(json.get("status").asString(), "status").isEqualTo("refused");
			requireThat(json.get("message").asString(), "message").startsWith("No campaign has the identifier c1");
		}
	}

	/**
	 * Verifies that {@code start} prints the core's refusal of a target without a prompt test configuration, naming
	 * the file to write, with a nonzero exit code.
	 */
	@Test
	public void printsStartRefusalNamingConfiguration() throws IOException
	{
		// Requirement: R-W12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			Path file = PromptConfiguration.location(Path.of("CLAUDE.md"));
			StringBuilder output = new StringBuilder();

			int exit = project.run(List.of("start", "CLAUDE.md"), output);

			requireThat(exit, "exit").isEqualTo(1);
			requireThat(project.calls, "calls").isEqualTo(List.of("start CLAUDE.md"));
			JsonNode json = JSON.readTree(output.toString());
			requireThat(json.get("status").asString(), "status").isEqualTo("refused");
			requireThat(json.get("message").asString(), "message").contains(PortablePaths.text(file));
		}
	}

	/**
	 * Verifies that an I/O failure, here a file where the directory that holds the project's CAT session directories
	 * belongs, is printed as JSON with a nonzero exit code.
	 */
	@Test
	public void printsFailure() throws IOException
	{
		// Requirement: R-I2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			Files.createDirectory(project.root.resolve(".cat"));
			Path blocked = Files.writeString(project.root.resolve(".cat/sessions"), "");
			StringBuilder output = new StringBuilder();
			int exit = project.run(List.of("status", "CLAUDE.md"), output);
			requireThat(exit, "exit").isEqualTo(1);
			JsonNode json = JSON.readTree(output.toString());
			requireThat(json.get("status").asString(), "status").isEqualTo("error");
			requireThat(json.get("message").asString(), "message").contains(blocked.toString());
		}
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

	/**
	 * Verifies that the usage text lists both contract tools with their arguments.
	 */
	@Test
	public void usageListsContractTools()
	{
		// Requirement: R-C27
		requireThat(Commands.USAGE, "usage").contains("contract check <context file>").
			contains("contract workspace <context file> <test directory> <test case number> <output directory>");
	}

	/**
	 * Verifies that both contract tools are parsed with their arguments, and that a malformed form is refused.
	 */
	@Test
	public void parsesContractTools() throws IOException
	{
		// Requirement: R-C27
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			requireThat(project.parse(List.of("contract", "check", "cat-contract.json")), "check").
				isEqualTo(new Command.ContractCheck(Path.of("cat-contract.json")));
			requireThat(project.parse(List.of("contract", "workspace", "cat-contract.json", ".cat/tests/CLAUDE/notes",
				"2", "../sized")), "workspace").isEqualTo(new Command.ContractWorkspace(Path.of("cat-contract.json"),
				Path.of(".cat/tests/CLAUDE/notes"), 2, Path.of("../sized")));
			for (List<String> malformed : List.of(List.of("contract"), List.of("contract", "verify", "cat-contract.json"),
				List.of("contract", "check"), List.of("contract", "check", "a.json", "b.json"),
				List.of("contract", "workspace", "cat-contract.json", "notes", "0", "out"),
				List.of("contract", "workspace", "cat-contract.json", "notes", "first", "out"),
				List.of("contract", "workspace", "cat-contract.json", "notes", "1")))
			{
				try
				{
					project.parse(malformed);
					throw new AssertionError("Accepted " + malformed);
				}
				catch (InvalidCommandException _)
				{
					// Expected
				}
			}
		}
	}

	/**
	 * Verifies that a contract tool does not reach the core and that its failure to read the context file exits with
	 * a nonzero status.
	 */
	@Test
	public void contractToolBypassesCore() throws IOException
	{
		// Requirement: R-C27
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			StringBuilder output = new StringBuilder();
			int exit = project.run(List.of("contract", "check", "missing-cat-contract.json"), output);
			requireThat(exit, "exit").isEqualTo(1);
			requireThat(project.calls, "calls").isEmpty();
			requireThat(output.toString(), "output").contains("missing-cat-contract.json");
		}
	}
	/**
	 * Each campaign step accepts background mode while retaining validation of every other argument.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void parsesBackgroundCampaignSteps() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Project project = Project.create(cleanup);
			for (List<String> arguments : List.of(List.of("start", "CLAUDE.md"), List.of("continue", "c1"),
				List.of("request-changes", "c1", "feedback"), List.of("decide", "c1", "retry")))
			{
				List<String> background = new ArrayList<>(arguments);
				background.add("--background");
				Command parsed = project.parse(background);
				requireThat(parsed instanceof Command.Background, "background").isTrue();
				requireThat(((Command.Background) parsed).command(), "command").isEqualTo(project.parse(arguments));
			}
		}
	}
}
