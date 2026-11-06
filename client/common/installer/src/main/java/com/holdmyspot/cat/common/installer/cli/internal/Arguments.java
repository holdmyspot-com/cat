package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * A validated installer operation and its caller-supplied inputs.
 *
 * @param operation     the requested complete operation
 * @param harness       the selected provider, or empty for status or uninstall of the installation's own harness
 * @param tag           the release tag that install selects, or null for the latest approved release or another
 *                      operation
 * @param home          the harness home that install names, or null to use the home that the user's environment
 *                      selects, or for another operation
 * @param deleteData    true if uninstall also deletes the harness's plugin data
 * @param createProfile true if the user allows install to create a permission profile for CAT, when the harness
 *                      configuration needs one
 * @param json          true if the user asks for the machine-readable JSON result even where the installer would
 *                      describe the result to a person
 */
public record Arguments(Operation operation, String harness, String tag, Path home, boolean deleteData,
	boolean createProfile, boolean json)
{
	/**
	 * The complete operations exposed by the installer.
	 */
	public enum Operation
	{
		INSTALL, STATUS, UNINSTALL
	}

	/**
	 * Parses the complete argument vector, refusing unknown, repeated, reordered or operation-inapplicable inputs.
	 * <p>
	 * {@code --json} comes before the operation as well and applies to every operation: it asks for the
	 * machine-readable result where the installer would describe the result to a person.
	 * <p>
	 * The options that give the user's permission or redirect the installation, {@code --home} and
	 * {@code --create-profile}, come before the operation. Claude Code and Codex allow an agent to run the commands that
	 * start with {@code install --harness <id>} without asking the user, so an install that carries one of these
	 * options does not start that way, and the harness asks the user before it runs.
	 *
	 * @param args      the caller's arguments
	 * @param harnesses identifiers of the installed activation providers
	 * @return the validated operation
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if the arguments do not match the installer grammar; when install gives
	 *                                  {@code --home} or {@code --create-profile} after the operation, the message
	 *                                  names the command with them before it
	 */
	public static Arguments parse(String[] args, Set<String> harnesses)
	{
		requireThat(args, "args").isNotNull();
		requireThat(harnesses, "harnesses").isNotNull();
		Path home = null;
		boolean createProfile = false;
		boolean json = false;
		int next = 0;
		while (next < args.length && args[next].startsWith("--"))
		{
			switch (args[next])
			{
				case "--home" ->
				{
					if (home != null || next + 1 >= args.length)
						throw usage();
					home = parseHome(args[next + 1]);
					next += 2;
				}
				case "--create-profile" ->
				{
					if (createProfile)
						throw usage();
					createProfile = true;
					next += 1;
				}
				case "--json" ->
				{
					if (json)
						throw usage();
					json = true;
					next += 1;
				}
				default -> throw usage();
			}
		}
		String[] command = Arrays.copyOfRange(args, next, args.length);
		Arguments parsed = parseOperation(command, harnesses, home, createProfile);
		if ((home != null || createProfile) && parsed.operation() != Operation.INSTALL)
			throw usage();
		return new Arguments(parsed.operation(), parsed.harness(), parsed.tag(), parsed.home(), parsed.deleteData(),
			parsed.createProfile(), json);
	}

	/**
	 * Parses the operation and the arguments that follow it.
	 *
	 * @param args          the arguments from the operation on
	 * @param harnesses     identifiers of the installed activation providers
	 * @param home          the harness home that the options before the operation name, or null
	 * @param createProfile true if the options before the operation give the permission to create a profile
	 * @return the validated operation
	 * @throws IllegalArgumentException if the arguments do not match the installer grammar
	 */
	private static Arguments parseOperation(String[] args, Set<String> harnesses, Path home, boolean createProfile)
	{
		if (args.length == 0)
			throw usage();
		Operation operation = switch (args[0])
		{
			case "install" -> Operation.INSTALL;
			case "status" -> Operation.STATUS;
			case "uninstall" -> Operation.UNINSTALL;
			default -> throw usage();
		};
		if (operation == Operation.STATUS && args.length == 1)
			return new Arguments(operation, "", null, null, false, false, false);
		if (operation == Operation.UNINSTALL && args.length <= 2)
		{
			if (args.length == 2 && !args[1].equals("--delete-data"))
				throw usage();
			return new Arguments(operation, "", null, null, args.length == 2, false, false);
		}
		if (args.length < 3 || !args[1].equals("--harness") || !harnesses.contains(args[2]))
			throw usage();
		String harness = args[2];
		return switch (operation)
		{
			case INSTALL -> parseInstall(args, harness, home, createProfile, harnesses);
			case UNINSTALL ->
			{
				if (args.length > 4 || (args.length == 4 && !args[3].equals("--delete-data")))
					throw usage();
				yield new Arguments(operation, harness, null, null, args.length == 4, false, false);
			}
			case STATUS ->
			{
				if (args.length != 3)
					throw usage();
				yield new Arguments(operation, harness, null, null, false, false, false);
			}
		};
	}

	/**
	 * Parses install's optional release.
	 *
	 * @param args          the arguments from the operation on, of length 3 or more
	 * @param harness       the validated harness identifier
	 * @param home          the harness home that the options before the operation name, or null
	 * @param createProfile true if the options before the operation give the permission to create a profile
	 * @param harnesses     identifiers of the installed activation providers
	 * @return the install operation
	 * @throws IllegalArgumentException if the arguments after the harness are not {@code [vX.Y[.Z]|latest]}; when
	 *                                  they hold {@code --home} or {@code --create-profile}, the message names the
	 *                                  command with them before the operation
	 */
	private static Arguments parseInstall(String[] args, String harness, Path home, boolean createProfile,
		Set<String> harnesses)
	{
		List<String> options = Arrays.asList(args).subList(3, args.length);
		if (options.contains("--home") || options.contains("--create-profile"))
			throw lateOptions(args, home, createProfile, harnesses);
		String tag = null;
		if (args.length == 4)
			tag = parseTag(args[3]);
		else if (args.length != 3)
			throw usage();
		return new Arguments(Operation.INSTALL, harness, tag, home, false, createProfile, false);
	}

	/**
	 * Returns the refusal of an install that gives {@code --home} or {@code --create-profile} after the operation.
	 *
	 * @param args          the arguments from the operation on
	 * @param home          the harness home that the options before the operation name, or null
	 * @param createProfile true if the options before the operation give the permission to create a profile
	 * @param harnesses     identifiers of the installed activation providers
	 * @return the refusal, which names the same command with those options before the operation when the installer
	 * 	accepts that command, and otherwise only the usage
	 */
	private static IllegalArgumentException lateOptions(String[] args, Path home, boolean createProfile,
		Set<String> harnesses)
	{
		List<String> moved = new ArrayList<>();
		if (home != null)
			moved.addAll(List.of("--home", home.toString()));
		if (createProfile)
			moved.add("--create-profile");
		List<String> names = new ArrayList<>();
		List<String> rest = new ArrayList<>();
		for (int i = 0; i < args.length; ++i)
		{
			if (i >= 3 && args[i].equals("--home") && i + 1 < args.length)
			{
				moved.addAll(List.of(args[i], args[i + 1]));
				names.add(args[i]);
				++i;
			}
			else if (i >= 3 && args[i].equals("--create-profile"))
			{
				moved.add(args[i]);
				names.add(args[i]);
			}
			else
				rest.add(args[i]);
		}
		if (rest.contains("--home") || rest.contains("--create-profile"))
			return usage();
		List<String> accepted = new ArrayList<>(moved);
		accepted.addAll(rest);
		try
		{
			parse(accepted.toArray(String[]::new), harnesses);
		}
		catch (IllegalArgumentException _)
		{
			return usage();
		}
		return new IllegalArgumentException("Usage: give " + String.join(" and ", names) + " before the " +
			"operation: installer " + String.join(" ", accepted));
	}

	/**
	 * Parses the harness home that {@code --home} names.
	 *
	 * @param argument the argument
	 * @return the path
	 * @throws IllegalArgumentException if the argument is not a path
	 */
	private static Path parseHome(String argument)
	{
		try
		{
			return Path.of(argument);
		}
		catch (InvalidPathException _)
		{
			throw usage();
		}
	}

	/**
	 * Parses the optional release argument of install.
	 *
	 * @param argument the argument
	 * @return the canonical release tag, or null for the latest approved release
	 * @throws IllegalArgumentException if the argument is neither {@code latest} nor a canonical {@code v}-prefixed
	 *                                  release version
	 */
	private static String parseTag(String argument)
	{
		if (argument.equals("latest"))
			return null;
		if (!argument.startsWith("v"))
			throw usage();
		try
		{
			return ReleaseVersion.parse(argument.substring(1)).tag();
		}
		catch (TrustException _)
		{
			throw usage();
		}
	}

	/**
	 * Creates the validated argument value.
	 *
	 * @param operation     the requested complete operation
	 * @param harness       the selected provider, or empty for status or uninstall of the installation's own harness
	 * @param tag           the release tag that install selects, or null
	 * @param home          the harness home that install names, or null
	 * @param deleteData    true if uninstall also deletes the harness's plugin data
	 * @param createProfile true if the user allows install to create a permission profile for CAT
	 * @param json          true if the user asks for the JSON result
	 * @throws NullPointerException if {@code operation} or {@code harness} are null
	 */
	public Arguments
	{
		requireThat(operation, "operation").isNotNull();
		requireThat(harness, "harness").isNotNull();
	}

	/**
	 * Returns the one usage diagnostic for every rejected argument vector.
	 *
	 * @return the usage error
	 */
	private static IllegalArgumentException usage()
	{
		return new IllegalArgumentException("Usage: installer [--home <absolute dir>] [--create-profile] [--json] " +
			"install --harness <id> [vX.Y[.Z]|latest]; installer status [--harness <id>]; installer uninstall " +
			"[--harness <id>] [--delete-data]");
	}
}
