package com.holdmyspot.cat.common.updater.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Set;

/**
 * A validated updater operation and its caller-supplied inputs.
 *
 * @param operation the requested complete operation
 * @param harness   the selected provider, or empty for status of every installed harness
 * @param tag       the requested release tag, or null for the latest approved release
 * @param home      the existing absolute harness home for setup, or null for another operation
 */
public record Arguments(Operation operation, String harness, String tag, Path home)
{
	/**
	 * The complete operations exposed by the updater.
	 */
	public enum Operation
	{
		UPDATE, STATUS, ROLLBACK, SETUP
	}

	/**
	 * Parses the complete argument vector, refusing unknown, repeated or operation-inapplicable inputs.
	 *
	 * @param args      the caller's arguments
	 * @param harnesses identifiers of the installed activation providers
	 * @return the validated operation
	 * @throws IllegalArgumentException if the arguments do not match the updater grammar
	 */
	public static Arguments parse(String[] args, Set<String> harnesses)
	{
		requireThat(args, "args").isNotNull();
		requireThat(harnesses, "harnesses").isNotNull();
		if (args.length == 0)
			throw usage();
		Operation operation = switch (args[0])
		{
			case "update" -> Operation.UPDATE;
			case "status" -> Operation.STATUS;
			case "rollback" -> Operation.ROLLBACK;
			case "setup" -> Operation.SETUP;
			default -> throw usage();
		};
		if (operation == Operation.STATUS && args.length == 1)
			return new Arguments(operation, "", null, null);
		boolean validLength = switch (operation)
		{
			case UPDATE -> args.length == 3 || args.length == 4;
			case STATUS, ROLLBACK -> args.length == 3;
			case SETUP -> args.length == 5;
		};
		if (!validLength || !args[1].equals("--harness") || !harnesses.contains(args[2]))
			throw usage();
		String harness = args[2];
		if (operation == Operation.SETUP)
		{
			if (!args[3].equals("--home"))
				throw usage();
			try
			{
				return new Arguments(operation, harness, null, Path.of(args[4]));
			}
			catch (InvalidPathException _)
			{
				throw usage();
			}
		}
		String tag = null;
		if (operation == Operation.UPDATE && args.length == 4 && !args[3].equals("latest"))
		{
			try
			{
				if (!args[3].startsWith("v"))
					throw usage();
				tag = ReleaseVersion.parse(args[3].substring(1)).tag();
			}
			catch (TrustException _)
			{
				throw usage();
			}
		}
		return new Arguments(operation, harness, tag, null);
	}

	/**
	 * Creates the validated argument value.
	 *
	 * @param operation the requested complete operation
	 * @param harness   the selected provider, or empty for status of every installed harness
	 * @param tag       the requested release tag, or null for the latest release
	 * @param home      the harness home for setup, or null for another operation
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
		return new IllegalArgumentException("Usage: cat-updater update --harness <id> [vX.Y[.Z]|latest]; " +
			"status [--harness <id>]; rollback --harness <id>; setup --harness <id> --home <absolute dir>");
	}
}
