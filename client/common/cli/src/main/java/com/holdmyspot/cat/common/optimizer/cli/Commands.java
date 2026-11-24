package com.holdmyspot.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.Choice;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationRecord;
import com.holdmyspot.cat.common.optimizer.campaign.DeliveryEntry;
import com.holdmyspot.cat.common.optimizer.campaign.DeliveryLabel;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.campaign.HistoryEntry;
import com.holdmyspot.cat.common.optimizer.contract.CertificationScope;
import com.holdmyspot.cat.common.optimizer.proposal.ContractTools;
import com.holdmyspot.cat.common.optimizer.report.CertificationStatus;
import com.holdmyspot.cat.common.optimizer.report.ChoiceItem;
import com.holdmyspot.cat.common.optimizer.workspace.WorktreeRefusedException;
import com.holdmyspot.cat.common.runner.HarnessSandbox;
import com.holdmyspot.cat.common.runner.WorktreeRequest;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.secrets.CommandsSecrets;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Parses the arguments of the {@code prompt-optimizer} launcher and dispatches the command to the {@link Core}.
 * <p>
 * Every outcome is one JSON object on the output stream, apart from the report of a contract tool, which a contract
 * author session reads as text. The exit codes are 0 for a result, 1 for a refusal or a failure, and 2 for invalid
 * input.
 * <p>
 * On a platform where the harness that runs the campaign's trials cannot sandbox them, every command that starts or
 * continues a campaign is refused before it reaches the core, so it changes nothing; {@code status} and
 * {@code stop} still run.
 * <p>
 * A former command name, such as {@code run} for {@code continue}, is refused as invalid input with a message that
 * names the command that replaced it, before the core is called; it is never an alias.
 */
public final class Commands
{
	static
	{
		CommandsSecrets.setCommandsAccess((commands, result) ->
		{
			requireThat(commands, "commands").isNotNull();
			requireThat(result, "result").isNotNull();
			return commands.render(result);
		});
	}

	private static final String MAX_CONCURRENCY = "--max-concurrency";
	private static final String GOAL = "--goal";
	private static final String EXTRA_PERMISSIONS = ExtraPermissions.OPTION;
	private static final String HARNESS = "--harness";
	/**
	 * The arguments of {@code start}.
	 */
	private static final String START_FORM = "<target> [%s <text>] [%s <1..%d>] [%s <file>] [%s <harness>]...".
		formatted(GOAL, MAX_CONCURRENCY, Campaign.MAXIMUM_CEILING, EXTRA_PERMISSIONS, HARNESS);
	/**
	 * The arguments of {@code contract workspace}.
	 */
	private static final String WORKSPACE_FORM = "<context file> <test directory> <test case number> <output directory>";
	/**
	 * The command that replaced each former command name. An agent or skill that learned a former name is refused and
	 * told what to run instead.
	 */
	private static final Map<String, Replacement> FORMER_NAMES = Map.of(
		"run", new Replacement("continue", "<campaign>"),
		"confirm", new Replacement("approve", "<campaign> [<scope>]"),
		"revise", new Replacement("request-changes", "<campaign> <feedback>"),
		"choose", new Replacement("decide", "<campaign> <choice>"),
		"cancel", new Replacement("stop", "<campaign>"));

	/**
	 * The usage text.
	 */
	public static final String USAGE = """
		usage: prompt-optimizer <command> ...
		  start %s [--background]
		  continue <campaign> [--background]
		  status <campaign | target>
		  approve <campaign> [<%s>]
		  request-changes <campaign> <feedback> [--background]
		  decide <campaign> <choice> [--attempt N | --review-point N] [--background]
		  stop <campaign>
		  contract check <context file>
		  contract workspace %s

		start prompts/example.md uses the current harness for a new campaign and the recorded harnesses on resume.
		start prompts/example.md %s selects each named evaluation harness.
		On resume, an explicit selection must match the recorded harnesses.""".formatted(START_FORM, scopeIds(),
		WORKSPACE_FORM, evaluationHarnessOptions());
	private final Core core;
	private final JsonMapper json;
	private final ZoneId zone;
	private final HarnessSandbox trialSandbox;
	private final Platform platform;

	/**
	 * Creates the commands.
	 *
	 * @param core     what the commands call
	 * @param json     the mapper that renders the output
	 * @param zone         the user's time zone, in which the human summary shows times
	 * @param trialSandbox decides whether the harness of the campaign's trials sandboxes them on {@code platform}
	 * @param platform     the platform that runs the commands
	 * @throws NullPointerException if any argument is null
	 */
	public Commands(Core core, JsonMapper json, ZoneId zone, HarnessSandbox trialSandbox, Platform platform)
	{
		requireThat(core, "core").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(zone, "zone").isNotNull();
		requireThat(trialSandbox, "trialSandbox").isNotNull();
		requireThat(platform, "platform").isNotNull();
		this.core = core;
		this.json = json;
		this.zone = zone;
		this.trialSandbox = trialSandbox;
		this.platform = platform;
	}

	/**
	 * Validates the arguments of a command. This is the only place that arguments are validated.
	 *
	 * @param args the arguments, starting with the command's name
	 * @return the command
	 * @throws NullPointerException if {@code args} is null
	 * @throws InvalidCommandException if the arguments are not a well-formed command, or name a former command
	 */
	public Command parse(List<String> args)
	{
		return parseArguments(args);
	}

	/**
	 * Validates the arguments of a command. A former command name is refused before its arguments are read.
	 *
	 * @param args the arguments, starting with the command's name
	 * @return the command
	 * @throws NullPointerException    if {@code args} is null
	 * @throws InvalidCommandException if the arguments are not a well-formed command, or name a former command
	 */
	private static Command parseArguments(List<String> args)
	{
		requireThat(args, "args").isNotNull();
		if (args.isEmpty())
			throw new InvalidCommandException("Missing command.");
		String name = args.getFirst();
		Replacement replacement = FORMER_NAMES.get(name);
		if (replacement != null)
		{
			throw new InvalidCommandException(name + " is now " + replacement.command() + ": prompt-optimizer " +
				replacement.command() + " " + replacement.arguments());
		}
		if (args.size() > 1 && args.getLast().equals("--background"))
		{
			if (!List.of("start", "continue", "request-changes", "decide").contains(name) ||
				args.stream().filter("--background"::equals).count() != 1)
			{
				throw new InvalidCommandException(
					"--background is accepted once by start, continue, request-changes and decide.");
			}
			List<String> foreground = new ArrayList<>(args);
			foreground.remove("--background");
			return new Command.Background(parseArguments(foreground));
		}
		List<String> rest = args.subList(1, args.size());
		return switch (name)
		{
			case "start" -> start(rest);
			case "continue" ->
			{
				expect(name, rest, 1, "<campaign>");
				yield new Command.Continue(campaign(rest.getFirst()));
			}
			case "status" ->
			{
				expect(name, rest, 1, "<campaign | target>");
				yield new Command.Status(subject(rest.getFirst()));
			}
			case "approve" -> approve(rest);
			case "stop" ->
			{
				expect(name, rest, 1, "<campaign>");
				yield new Command.Stop(campaign(rest.getFirst()));
			}
			case "request-changes" ->
			{
				expect(name, rest, 2, "<campaign> <feedback>");
				String feedback = rest.get(1);
				if (feedback.isBlank())
					throw new InvalidCommandException("request-changes: the feedback is blank.");
				yield new Command.RequestChanges(campaign(rest.getFirst()), feedback);
			}
			case "decide" -> decide(rest);
			case "contract" -> contract(rest);
			default -> throw new InvalidCommandException("Unknown command: " + name);
		};
	}

	/**
	 * Parses {@code start}, whose options may appear in any order. Each evaluation harness may be named once. All
	 * other options may appear at most once. The ceiling defaults to 8, and the goal and extra permissions to none.
	 *
	 * @param rest the arguments after the command's name
	 * @return the command
	 * @throws InvalidCommandException if the arguments are not well-formed
	 */
	private static Command start(List<String> rest)
	{
		if (rest.isEmpty())
			throw new InvalidCommandException("start: expected " + START_FORM);
		Path target = target("start", rest.getFirst());
		Map<String, String> options = new HashMap<>();
		Set<Harness> evaluationHarnesses = EnumSet.noneOf(Harness.class);
		for (int i = 1; i < rest.size(); i += 2)
		{
			String option = rest.get(i);
			if (!option.equals(GOAL) && !option.equals(MAX_CONCURRENCY) && !option.equals(EXTRA_PERMISSIONS) &&
				!option.equals(HARNESS))
			{
				throw new InvalidCommandException("start: expected " + START_FORM + "; unknown option '" + option + "'.");
			}
			if (i + 1 == rest.size())
				throw new InvalidCommandException("start: " + option + " needs a value; expected " + START_FORM);

			String value = rest.get(i + 1);
			if (option.equals(HARNESS))
			{
				Harness harness = Harness.fromCommandName(value).orElseThrow(() ->
					new InvalidCommandException("Unknown evaluation harness '" + value + "'."));
				if (!evaluationHarnesses.add(harness))
					throw new InvalidCommandException("Repeated evaluation harness '" + value + "'.");
			}
			else if (options.putIfAbsent(option, value) != null)
			{
				throw new InvalidCommandException("start: expected " + START_FORM + "; " + option +
					" may appear only once.");
			}
		}

		int ceiling = Campaign.DEFAULT_CEILING;
		String ceilingText = options.get(MAX_CONCURRENCY);
		if (ceilingText != null)
		{
			try
			{
				ceiling = Integer.parseInt(ceilingText);
				requireThat(ceiling, "ceiling").isBetween(1, true, Campaign.MAXIMUM_CEILING, true);
			}
			catch (IllegalArgumentException _)
			{
				throw new InvalidCommandException(MAX_CONCURRENCY + " must be an integer in 1.." +
					Campaign.MAXIMUM_CEILING + ", got '" + ceilingText + "'");
			}
		}
		String goal = options.get(GOAL);
		if (goal != null && goal.isBlank())
			throw new InvalidCommandException("start: the " + GOAL + " text is blank.");
		Path extraPermissions = null;
		String extraPermissionsText = options.get(EXTRA_PERMISSIONS);
		if (extraPermissionsText != null)
			extraPermissions = path("start", "the " + EXTRA_PERMISSIONS + " file", extraPermissionsText);
		return new Command.Start(target, ceiling, goal, extraPermissions, evaluationHarnesses);
	}

	/**
	 * Returns the evaluation options for selecting every supported harness in the usage example.
	 *
	 * @return one option and its canonical name per harness, separated by spaces
	 */
	private static String evaluationHarnessOptions()
	{
		StringJoiner options = new StringJoiner(" ");
		for (Harness harness : Harness.values())
			options.add(HARNESS + " " + harness.commandName());
		return options.toString();
	}

	/**
	 * Parses {@code approve}, whose certification scope is the weakest cell only unless the user chose another.
	 *
	 * @param rest the arguments after the command's name
	 * @return the command
	 * @throws InvalidCommandException if the arguments are not well-formed or name an unknown certification scope
	 */
	private static Command approve(List<String> rest)
	{
		if (rest.size() == 1)
			return new Command.Approve(campaign(rest.getFirst()), CertificationScope.WEAKEST_CELL);
		expect("approve", rest, 2, "<campaign> [<scope>]");
		String campaign = campaign(rest.getFirst());
		CertificationScope scope = CertificationScope.fromId(rest.get(1)).orElseThrow(() ->
			new InvalidCommandException("approve: unknown certification scope '" + rest.get(1) + "'; choose " +
				CertificationScope.choices() + "."));
		return new Command.Approve(campaign, scope);
	}

	/**
	 * Parses {@code decide}.
	 *
	 * @param rest the arguments after the command's name
	 * @return the command
	 * @throws InvalidCommandException if the arguments are not well-formed
	 */
	private static Command decide(List<String> rest)
	{
		if (rest.size() < 2)
		{
			throw new InvalidCommandException("decide: expected <campaign> <choice> " +
				"[--attempt N | --review-point N]");
		}
		String campaign = campaign(rest.getFirst());
		String answer = rest.get(1);
		if (answer.startsWith("resolution_"))
		{
			expect("decide", rest, 2, "<campaign> resolution_<n>");
			return new Command.ResolveConflict(campaign, positive("resolution", answer.substring(11)));
		}
		Choice choice = choice(rest.get(1));
		int attempt = 0;
		int reviewPoint = 0;
		List<String> options = rest.subList(2, rest.size());
		if (!options.isEmpty())
		{
			if (options.size() != 2)
				throw new InvalidCommandException("decide: expected one option with one value, got " + options);
			switch (options.get(0))
			{
				case "--attempt" -> attempt = positive(options.get(0), options.get(1));
				case "--review-point" -> reviewPoint = positive(options.get(0), options.get(1));
				default -> throw new InvalidCommandException("decide: unknown option " + options.get(0));
			}
		}
		if (choice == Choice.KEEP_IMPROVEMENT && attempt == 0)
			throw new InvalidCommandException("decide " + id(Choice.KEEP_IMPROVEMENT) + " requires --attempt N.");
		if (choice != Choice.KEEP_IMPROVEMENT && attempt != 0)
			throw new InvalidCommandException("--attempt is valid only for " + id(Choice.KEEP_IMPROVEMENT) + ".");
		if (choice == Choice.MORE_EVIDENCE && reviewPoint == 0)
			throw new InvalidCommandException("decide " + id(Choice.MORE_EVIDENCE) + " requires --review-point N.");
		if (choice != Choice.MORE_EVIDENCE && reviewPoint != 0)
			throw new InvalidCommandException("--review-point is valid only for " + id(Choice.MORE_EVIDENCE) + ".");
		return new Command.Decide(campaign, choice, attempt, reviewPoint);
	}

	/**
	 * Parses a contract tool: {@code contract check} or {@code contract workspace}.
	 *
	 * @param rest the arguments after the command's name
	 * @return the command
	 * @throws InvalidCommandException if the arguments are not well-formed
	 */
	private static Command contract(List<String> rest)
	{
		String form = "check <context file> | workspace " + WORKSPACE_FORM;
		if (rest.isEmpty())
			throw new InvalidCommandException("contract: expected " + form);
		List<String> arguments = rest.subList(1, rest.size());
		return switch (rest.getFirst())
		{
			case "check" ->
			{
				expect("contract check", arguments, 1, "<context file>");
				yield new Command.ContractCheck(path("contract check", "the context file", arguments.getFirst()));
			}
			case "workspace" ->
			{
				String command = "contract workspace";
				expect(command, arguments, 4, WORKSPACE_FORM);
				yield new Command.ContractWorkspace(path(command, "the context file", arguments.get(0)),
					path(command, "the test directory", arguments.get(1)),
					positive("The test case number", arguments.get(2)),
					path(command, "the output directory", arguments.get(3)));
			}
			default -> throw new InvalidCommandException("contract: expected " + form + "; unknown tool '" +
				rest.getFirst() + "'.");
		};
	}

	/**
	 * Checks the number of arguments.
	 *
	 * @param name the command's name
	 * @param rest the arguments after the command's name
	 * @param count the required number of arguments
	 * @param form the form of the arguments, for the message
	 * @throws InvalidCommandException if the number differs
	 */
	private static void expect(String name, List<String> rest, int count, String form)
	{
		if (rest.size() != count)
			throw new InvalidCommandException(name + ": expected " + form + ", got " + rest.size() + " argument(s).");
	}

	/**
	 * Validates a campaign identifier.
	 *
	 * @param value the argument
	 * @return the identifier
	 * @throws InvalidCommandException if the argument cannot be an identifier
	 */
	private static String campaign(String value)
	{
		if (!isCampaignId(value))
			throw new InvalidCommandException("Invalid campaign identifier: '" + value + "'");
		return value;
	}

	/**
	 * Indicates whether a value is a well-formed campaign identifier.
	 *
	 * @param value the value
	 * @return {@code true} if the value has the form of a campaign identifier
	 * @throws NullPointerException if {@code value} is null
	 */
	static boolean isCampaignId(String value)
	{
		return WorktreeRequest.isCampaignId(value);
	}

	/**
	 * Validates the subject of {@code status}: a campaign identifier or a target.
	 *
	 * @param value the argument
	 * @return the argument
	 * @throws InvalidCommandException if the argument is neither a campaign identifier nor a path
	 */
	private static String subject(String value)
	{
		if (!isCampaignId(value))
			target("status", value);
		return value;
	}

	/**
	 * Validates a target.
	 *
	 * @param command the command's name, which prefixes the message of a rejected argument
	 * @param value the argument
	 * @return the target
	 * @throws InvalidCommandException if the argument is not a path
	 */
	private static Path target(String command, String value)
	{
		return path(command, "the target", value);
	}

	/**
	 * Validates a path argument.
	 *
	 * @param command the command's name, which prefixes the message of a rejected argument
	 * @param name    what the argument names, such as {@code the target}
	 * @param value   the argument
	 * @return the path
	 * @throws InvalidCommandException if the argument is not a path
	 */
	private static Path path(String command, String name, String value)
	{
		if (value.isBlank())
			throw new InvalidCommandException(command + ": " + name + " is blank.");
		try
		{
			return Path.of(value);
		}
		catch (InvalidPathException e)
		{
			throw new InvalidCommandException(command + ": " + name + " is not a path: " + e.getMessage());
		}
	}

	/**
	 * Parses a choice.
	 *
	 * @param value the argument
	 * @return the choice
	 * @throws InvalidCommandException if no choice has this identifier
	 */
	private static Choice choice(String value)
	{
		for (Choice choice : Choice.values())
			if (choice != Choice.RESOLUTION && id(choice).equals(value))
				return choice;
		throw new InvalidCommandException("Unknown choice: '" + value + "'");
	}

	/**
	 * Returns the identifier that {@code decide} accepts for a choice.
	 *
	 * @param choice the choice
	 * @return the identifier, such as {@code keep_improvement}
	 */
	private static String id(Choice choice)
	{
		return new ChoiceItem(choice, 0, false, 0).id();
	}

	/**
	 * Lists the certification scopes that {@code approve} accepts, for the usage text.
	 *
	 * @return the scope identifiers separated by {@code " | "}, such as {@code weakest_cell | latest_versions}
	 */
	private static String scopeIds()
	{
		StringJoiner ids = new StringJoiner(" | ");
		for (CertificationScope scope : CertificationScope.values())
			ids.add(scope.id());
		return ids.toString();
	}

	/**
	 * Parses a positive integer.
	 *
	 * @param option the option's name
	 * @param value the argument
	 * @return the number
	 * @throws InvalidCommandException if the argument is not a positive integer
	 */
	private static int positive(String option, String value)
	{
		try
		{
			int number = Integer.parseInt(value);
			if (number > 0)
				return number;
		}
		catch (NumberFormatException _)
		{
			// Reported below
		}
		throw new InvalidCommandException(option + " must be a positive integer, got '" + value + "'");
	}

	/**
	 * Indicates whether arguments name a command that may start sessions.
	 *
	 * @param args the arguments, starting with the command's name
	 * @return {@code false} if the arguments are invalid, or name a command that starts no session
	 * @throws NullPointerException if {@code args} is null
	 */
	public boolean startsSessions(List<String> args)
	{
		return mayStartSessions(args);
	}

	/**
	 * Indicates whether arguments name a command that may start sessions, without a scope.
	 *
	 * @param args the arguments, starting with the command's name
	 * @return {@code false} if the arguments are invalid, or name a command that starts no session
	 * @throws NullPointerException if {@code args} is null
	 */
	static boolean mayStartSessions(List<String> args)
	{
		return sessionStartingCommand(args).isPresent();
	}

	/**
	 * Parses the session-starting command for the launch preflight.
	 *
	 * @param args the arguments, starting with the command's name
	 * @return the parsed command, or empty for invalid input and commands that cannot start sessions
	 * @throws NullPointerException if {@code args} is null
	 */
	static Optional<Command> sessionStartingCommand(List<String> args)
	{
		requireThat(args, "args").isNotNull();
		if (isHelpRequest(args) || isContractTool(args))
			return Optional.empty();
		try
		{
			Command command = parseArguments(args);
			if (canStartTrial(command))
				return Optional.of(command);
		}
		catch (InvalidCommandException _)
		{
			// The command runner reports invalid arguments.
		}
		return Optional.empty();
	}

	/**
	 * Runs a command and prints its JSON result.
	 *
	 * @param args the arguments, starting with the command's name
	 * @param out where the JSON goes
	 * @return the exit code
	 * @throws NullPointerException if any argument is null
	 */
	public int execute(List<String> args, PrintStream out)
	{
		requireThat(args, "args").isNotNull();
		requireThat(out, "out").isNotNull();
		if (isHelpRequest(args))
		{
			out.println(statusMessage(json, "help", USAGE));
			return 0;
		}
		if (isContractTool(args))
			return executeContractTool(json, args, out);
		Command command;
		try
		{
			command = parse(args);
		}
		catch (InvalidCommandException e)
		{
			out.println(statusMessage(json, "invalid_input", e.getMessage() + "\n" + USAGE));
			return 2;
		}
		try
		{
			CommandResult result = dispatch(command);
			out.println(render(result));
			return 0;
		}
		catch (CommandRefusedException e)
		{
			ObjectNode refusal = statusNode(json, "refused", e.getMessage());
			if (e.reason() != null)
				refusal.put("reason", e.reason().name().toLowerCase(Locale.ROOT));
			out.println(json.writeValueAsString(refusal));
			return 1;
		}
		catch (WorktreeRefusedException e)
		{
			// Nothing serves the optimization worktree's registration, as nothing serves a refused launch
			out.println(statusMessage(json, "refused", e.getMessage()));
			return 2;
		}
		catch (IOException e)
		{
			out.println(statusMessage(json, "error", e.getClass().getSimpleName() + ": " + e.getMessage()));
			return 1;
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			out.println(statusMessage(json, "error", "Interrupted."));
			return 1;
		}
	}

	/**
	 * Indicates whether arguments run a contract tool, which needs no campaign and no harness.
	 *
	 * @param args the arguments, starting with the command's name
	 * @return {@code true} if the command is {@code contract}
	 * @throws NullPointerException if {@code args} is null
	 */
	public static boolean isContractTool(List<String> args)
	{
		requireThat(args, "args").isNotNull();
		return !args.isEmpty() && args.getFirst().equals("contract");
	}

	/**
	 * Runs a contract tool and prints its report as text for the contract author session that runs it.
	 *
	 * @param json the JSON mapper that reads the session's files
	 * @param args the arguments, starting with {@code contract}
	 * @param out  receives the report
	 * @return 0 if the tool found no problem, 1 if it found one or failed, or 2 for invalid input
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if the arguments do not start with {@code contract}
	 */
	public static int executeContractTool(JsonMapper json, List<String> args, PrintStream out)
	{
		requireThat(json, "json").isNotNull();
		requireThat(out, "out").isNotNull();
		requireThat(isContractTool(args), "isContractTool(args)").isTrue();
		Command.ContractTool tool;
		try
		{
			tool = (Command.ContractTool) parseArguments(args);
		}
		catch (InvalidCommandException e)
		{
			out.println(e.getMessage() + "\n" + USAGE);
			return 2;
		}
		ContractTools tools = new ContractTools(json);
		try
		{
			return switch (tool)
			{
				case Command.ContractCheck check -> tools.check(check.context(), out);
				case Command.ContractWorkspace workspace -> tools.workspace(workspace.context(),
					workspace.testDirectory(), workspace.testCase(), workspace.output(), out);
			};
		}
		catch (IOException e)
		{
			out.println("The contract tool failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
			return 1;
		}
	}

	/**
	 * Calls the core.
	 *
	 * @param command the command
	 * @return the core's result
	 * @throws CommandRefusedException if the command can start a trial and the harness that runs the campaign's trials
	 *                                 cannot sandbox them on the platform, or if the core refuses the command
	 * @throws IOException             if the core fails
	 * @throws InterruptedException    if the thread is interrupted
	 */
	private CommandResult dispatch(Command command) throws CommandRefusedException, IOException, InterruptedException
	{
		core.validate(command);
		if (canStartTrial(command))
		{
			Optional<String> refusal = trialSandbox.refusal(platform);
			if (refusal.isPresent())
				throw new CommandRefusedException(refusal.get());
		}
		return switch (command)
		{
			case Command.Background background -> core.background(() -> dispatch(background.command()));
			case Command.Start start -> core.start(start.target(), start.ceiling(), start.goal(), start.extraPermissions(),
				start.evaluationHarnesses());
			case Command.Continue continued -> core.continueCampaign(continued.campaign());
			case Command.Status status -> core.status(status.subject());
			case Command.Approve approve -> core.approve(approve.campaign(), approve.scope());
			case Command.RequestChanges request -> core.requestChanges(request.campaign(), request.feedback());
			case Command.Decide decide ->
				core.decide(decide.campaign(), decide.choice(), decide.attempt(), decide.reviewPoint());
			case Command.ResolveConflict resolve -> core.resolveConflict(resolve.campaign(), resolve.resolution());
			case Command.Stop stop -> core.stop(stop.campaign());
			case Command.ContractTool _ -> throw new AssertionError("A contract tool does not reach the core");
		};
	}

	/**
	 * Indicates whether a command starts or continues a campaign, which can start a trial.
	 *
	 * @param command the command
	 * @return {@code false} for {@code status} and {@code stop}, which start no trial; otherwise {@code true}
	 */
	private static boolean canStartTrial(Command command)
	{
		return switch (command)
		{
			case Command.Background background -> canStartTrial(background.command());
			case Command.Status _, Command.Stop _ -> false;
			case Command.Start _, Command.Continue _, Command.Approve _, Command.RequestChanges _, Command.Decide _,
				Command.ResolveConflict _ -> true;
			case Command.ContractTool _ -> throw new AssertionError("A contract tool does not reach the core");
		};
	}

	/**
	 * Renders a result.
	 *
	 * @param result the result
	 * @return the JSON text
	 */
	String render(CommandResult result)
	{
		if (result.backgroundResult() != null)
		{
			ObjectNode saved = result.backgroundResult().deepCopy();
			saved.put("summary", result.summary(zone));
			if (!result.notices().isEmpty() && !saved.path("proposal_file").isNull())
			{
				String message = saved.path("message").asString("");
				if (!message.isEmpty())
					message += "\n";
				saved.put("message", message + String.join("\n", result.notices()));
			}
			return json.writeValueAsString(saved);
		}
		ObjectNode root;
		if (result.status() == null)
		{
			root = certification(result.certification());
			root.putNull("harness");
		}
		else
		{
			root = result.status().toJson(json);
			root.put("harness", result.harness().commandName());
		}
		root.put("summary", result.summary(zone));
		if (result.proposalFile() == null)
			root.putNull("proposal_file");
		else
			root.put("proposal_file", result.proposalFile().toAbsolutePath().toString());
		if (result.delivery() == null)
			root.putNull("delivery_result");
		else
			root.set("delivery_result", DeliveryJson.of(result.delivery(), json));
		if (result.message() == null)
			root.putNull("message");
		else
			root.put("message", result.message());
		return json.writeValueAsString(root);
	}

	/**
	 * Renders the certification history of a target that has no campaign: the latest entry of each harness with its
	 * applicability, and the earlier entries. The {@code status} is the label that every latest entry shares, or
	 * {@code certification history} if their labels differ.
	 *
	 * @param certification the latest entry of each harness with its applicability, and the earlier entries
	 * @return the JSON object, without the fields that every result has
	 */
	private ObjectNode certification(CertificationStatus certification)
	{
		ObjectNode root = json.createObjectNode();
		Set<DeliveryLabel> labels = EnumSet.noneOf(DeliveryLabel.class);
		for (CertificationStatus.Latest entry : certification.latest())
			labels.add(entry.entry().label());
		if (labels.size() == 1)
			root.put("status", labels.iterator().next().label());
		else
			root.put("status", "certification history");
		root.putNull("campaign");
		root.put("target", certification.target().toString());
		ArrayNode latest = root.putArray("latest");
		for (CertificationStatus.Latest entry : certification.latest())
		{
			ObjectNode node = entryNode(latest.addObject(), entry.entry());
			DeliveryJson.applicability(node.putObject("applicability"), entry.applicability());
			if (entry.superseded().isPresent())
			{
				CertificationStatus.Superseded superseded = entry.superseded().get();
				ObjectNode certificationNode = entryNode(node.putObject("superseded_certification"),
					superseded.record());
				if (superseded.isHistorical())
					certificationNode.put("applicability", "historical");
				else
					certificationNode.put("applicability", "current");
				ArrayNode changed = certificationNode.putArray("changed_sources");
				for (Path path : superseded.changedSources())
					changed.add(PortablePaths.text(path));
			}
		}
		ArrayNode earlier = root.putArray("earlier");
		for (HistoryEntry entry : certification.earlier())
			entryNode(earlier.addObject(), entry);
		return root;
	}

	/**
	 * Describes an entry of the certification history.
	 *
	 * @param node the object to describe the entry in
	 * @param entry the entry
	 * @return {@code node}
	 */
	private static ObjectNode entryNode(ObjectNode node, HistoryEntry entry)
	{
		Optional<String> harness = entry.harness();
		if (harness.isPresent())
			node.put("harness", harness.get());
		else
			node.putNull("harness");
		node.put("label", entry.label().label());
		switch (entry)
		{
			case CertificationRecord record ->
			{
				node.put("attempt", record.certificationIndex());
				node.put("scope", record.scope().id());
				Set<String> evaluators = record.evaluatorCells().keySet();
				if (evaluators.size() > 1)
				{
					ArrayNode harnesses = node.putArray("evaluation_harnesses");
					evaluators.forEach(harnesses::add);
					record.snapshot().ifPresent(snapshot -> node.put("snapshot", snapshot.hex()));
				}
			}
			case DeliveryEntry delivery ->
			{
				node.put("attempt", delivery.attempt());
				ArrayNode provenBetter = node.putArray("proven_better");
				for (String cell : delivery.provenBetter())
					provenBetter.add(cell);
				node.put("incumbent", delivery.incumbent().hex());
			}
		}
		return node;
	}

	/**
	 * Indicates whether the arguments ask for the usage text.
	 *
	 * @param args the arguments, starting with the command's name
	 * @return {@code true} for {@code help} alone, or for {@code --help} or {@code -h} anywhere
	 */
	static boolean isHelpRequest(List<String> args)
	{
		if (args.equals(List.of("help")))
			return true;
		return args.contains("--help") || args.contains("-h");
	}

	/**
	 * Renders a failure or the usage text.
	 *
	 * @param json the JSON mapper
	 * @param status {@code "refused"}, {@code "invalid_input"}, {@code "error"} or {@code "help"}
	 * @param message the explanation
	 * @return the JSON text
	 */
	static String statusMessage(JsonMapper json, String status, String message)
	{
		return json.writeValueAsString(statusNode(json, status, message));
	}

	/**
	 * Creates the shared fields of a failure or usage result.
	 *
	 * @param json the JSON mapper
	 * @param status the result status
	 * @param message the explanation
	 * @return the JSON object
	 */
	private static ObjectNode statusNode(JsonMapper json, String status, String message)
	{
		ObjectNode root = json.createObjectNode();
		root.put("status", status);
		root.put("message", message);
		return root;
	}

	/**
	 * The command that replaced a former command name, which the refusal of the former name shows.
	 *
	 * @param command   the current command's name, such as {@code continue}
	 * @param arguments the current command's arguments, such as {@code <campaign>}
	 */
	private record Replacement(String command, String arguments)
	{
	}
}
