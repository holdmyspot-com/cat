package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.report.ChoiceItem;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses the arguments of the {@code prompt-optimizer} launcher and dispatches the command to the {@link Core}.
 * <p>
 * Every outcome is one JSON object on the output stream. The exit codes are 0 for a result, 1 for a refusal or a
 * failure, and 2 for invalid input.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe if {@code core} is.
 */
public final class Commands
{
	/**
	 * The usage text.
	 */
	public static final String USAGE = """
		usage: prompt-optimizer <command> ...
		  start <target>
		  run <campaign>
		  status <campaign>
		  confirm <campaign>
		  revise <campaign> <feedback>
		  choose <campaign> <choice> [--attempt N | --review-point N | --target <path>]
		  cancel <campaign>""";
	private static final Pattern CAMPAIGN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
	private final Core core;
	private final JsonMapper json;

	/**
	 * Creates the commands.
	 *
	 * @param core what the commands call
	 * @param json the mapper that renders the output
	 * @throws NullPointerException if any argument is null
	 */
	public Commands(Core core, JsonMapper json)
	{
		requireThat(core, "core").isNotNull();
		requireThat(json, "json").isNotNull();
		this.core = core;
		this.json = json;
	}

	/**
	 * Validates the arguments of a command. This is the only place that arguments are validated.
	 *
	 * @param args the arguments, starting with the command's name
	 * @return the command
	 * @throws NullPointerException if {@code args} is null
	 * @throws InvalidCommandException if the arguments are not a well-formed command
	 */
	public Command parse(List<String> args)
	{
		requireThat(args, "args").isNotNull();
		if (args.isEmpty())
			throw new InvalidCommandException("Missing command.");
		String name = args.getFirst();
		List<String> rest = args.subList(1, args.size());
		return switch (name)
		{
			case "start" ->
			{
				expect(name, rest, 1, "<target>");
				yield new Command.Start(target(rest.getFirst()));
			}
			case "run" ->
			{
				expect(name, rest, 1, "<campaign>");
				yield new Command.Run(campaign(rest.getFirst()));
			}
			case "status" ->
			{
				expect(name, rest, 1, "<campaign>");
				yield new Command.Status(campaign(rest.getFirst()));
			}
			case "confirm" ->
			{
				expect(name, rest, 1, "<campaign>");
				yield new Command.Confirm(campaign(rest.getFirst()));
			}
			case "cancel" ->
			{
				expect(name, rest, 1, "<campaign>");
				yield new Command.Cancel(campaign(rest.getFirst()));
			}
			case "revise" ->
			{
				expect(name, rest, 2, "<campaign> <feedback>");
				String feedback = rest.get(1);
				if (feedback.isBlank())
					throw new InvalidCommandException("revise: the feedback is blank.");
				yield new Command.Revise(campaign(rest.getFirst()), feedback);
			}
			case "choose" -> choose(rest);
			default -> throw new InvalidCommandException("Unknown command: " + name);
		};
	}

	/**
	 * Parses {@code choose}.
	 *
	 * @param rest the arguments after the command's name
	 * @return the command
	 * @throws InvalidCommandException if the arguments are not well-formed
	 */
	private static Command choose(List<String> rest)
	{
		if (rest.size() < 2)
		{
			throw new InvalidCommandException("choose: expected <campaign> <choice> " +
				"[--attempt N | --review-point N | --target <path>]");
		}
		String campaign = campaign(rest.getFirst());
		Choice choice = choice(rest.get(1));
		int attempt = 0;
		int reviewPoint = 0;
		Path target = null;
		List<String> options = rest.subList(2, rest.size());
		if (!options.isEmpty())
		{
			if (options.size() != 2)
				throw new InvalidCommandException("choose: expected one option with one value, got " + options);
			switch (options.get(0))
			{
				case "--attempt" -> attempt = positive(options.get(0), options.get(1));
				case "--review-point" -> reviewPoint = positive(options.get(0), options.get(1));
				case "--target" -> target = authorizedTarget(options.get(1));
				default -> throw new InvalidCommandException("choose: unknown option " + options.get(0));
			}
		}
		if (choice == Choice.KEEP_IMPROVEMENT && attempt == 0)
			throw new InvalidCommandException("choose keep-improvement requires --attempt N.");
		if (choice != Choice.KEEP_IMPROVEMENT && attempt != 0)
			throw new InvalidCommandException("--attempt is valid only for keep-improvement.");
		if (choice == Choice.MORE_EVIDENCE && reviewPoint == 0)
			throw new InvalidCommandException("choose more-evidence requires --review-point N.");
		if (choice != Choice.MORE_EVIDENCE && reviewPoint != 0)
			throw new InvalidCommandException("--review-point is valid only for more-evidence.");
		if (choice == Choice.AUTHORIZE_TARGET && target == null)
			throw new InvalidCommandException("choose authorize-target requires --target <path>.");
		if (choice != Choice.AUTHORIZE_TARGET && target != null)
			throw new InvalidCommandException("--target is valid only for authorize-target.");
		return new Command.Choose(campaign, choice, attempt, reviewPoint, target);
	}

	/**
	 * Validates the external target that an {@code authorize-target} choice names.
	 *
	 * @param value the argument
	 * @return the target
	 * @throws InvalidCommandException if the argument is not a normalized absolute path
	 */
	private static Path authorizedTarget(String value)
	{
		Path target;
		try
		{
			target = Path.of(value);
		}
		catch (InvalidPathException e)
		{
			throw new InvalidCommandException("--target: not a path: " + e.getMessage());
		}
		if (!target.isAbsolute() || !target.equals(target.normalize()))
		{
			throw new InvalidCommandException("--target: expected the absolute, normalized path of the warned " +
				"target, got '" + value + "'");
		}
		return target;
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
		if (!CAMPAIGN_ID.matcher(value).matches())
			throw new InvalidCommandException("Invalid campaign identifier: '" + value + "'");
		return value;
	}

	/**
	 * Validates a target.
	 *
	 * @param value the argument
	 * @return the target
	 * @throws InvalidCommandException if the argument is not a path
	 */
	private static Path target(String value)
	{
		if (value.isBlank())
			throw new InvalidCommandException("start: the target is blank.");
		try
		{
			return Path.of(value);
		}
		catch (InvalidPathException e)
		{
			throw new InvalidCommandException("start: the target is not a path: " + e.getMessage());
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
			if (new ChoiceItem(choice, 0, false, 0).id().equals(value))
				return choice;
		throw new InvalidCommandException("Unknown choice: '" + value + "'");
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
		Command command;
		try
		{
			command = parse(args);
		}
		catch (InvalidCommandException e)
		{
			out.println(failure("invalid-input", e.getMessage() + "\n" + USAGE));
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
			out.println(failure("refused", e.getMessage()));
			return 1;
		}
		catch (IOException e)
		{
			out.println(failure("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
			return 1;
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			out.println(failure("error", "Interrupted."));
			return 1;
		}
	}

	/**
	 * Calls the core.
	 *
	 * @param command the command
	 * @return the core's result
	 * @throws CommandRefusedException if the core refuses the command
	 * @throws IOException if the core fails
	 * @throws InterruptedException if the thread is interrupted
	 */
	private CommandResult dispatch(Command command) throws CommandRefusedException, IOException, InterruptedException
	{
		return switch (command)
		{
			case Command.Start start -> core.start(start.target());
			case Command.Run run -> core.run(run.campaign());
			case Command.Status status -> core.status(status.campaign());
			case Command.Confirm confirm -> core.confirm(confirm.campaign());
			case Command.Revise revise -> core.revise(revise.campaign(), revise.feedback());
			case Command.Choose choose ->
				core.choose(choose.campaign(), choose.choice(), choose.attempt(), choose.reviewPoint(), choose.target());
			case Command.Cancel cancel -> core.cancel(cancel.campaign());
		};
	}

	/**
	 * Renders a result.
	 *
	 * @param result the result
	 * @return the JSON text
	 */
	private String render(CommandResult result)
	{
		ObjectNode root = result.status().toJson(json);
		root.put("harness", result.harness().name().toLowerCase(java.util.Locale.ROOT));
		root.put("summary", result.status().summary());
		if (result.proposal() == null)
			root.putNull("proposal");
		else
			root.put("proposal", result.proposal());
		if (result.delivery() == null)
			root.putNull("deliveryResult");
		else
			root.set("deliveryResult", DeliveryJson.of(result.delivery(), json));
		if (result.message() == null)
			root.putNull("message");
		else
			root.put("message", result.message());
		return json.writeValueAsString(root);
	}

	/**
	 * Renders a failure.
	 *
	 * @param status {@code "refused"}, {@code "invalid-input"} or {@code "error"}
	 * @param message the explanation
	 * @return the JSON text
	 */
	private String failure(String status, String message)
	{
		ObjectNode root = json.createObjectNode();
		root.put("status", status);
		root.put("message", message);
		return json.writeValueAsString(root);
	}
}
