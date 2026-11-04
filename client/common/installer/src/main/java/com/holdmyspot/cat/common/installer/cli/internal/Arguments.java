package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Set;

/**
 * A validated installer operation and its caller-supplied inputs.
 *
 * @param operation  the requested complete operation
 * @param harness    the selected provider, or empty for status or uninstall of the installation's own harness
 * @param tag        the release tag that update selects, or null for the latest approved release or another operation
 * @param home       the harness home that install names, or null to use the home that the user's environment
 *                   selects, or for another operation
 * @param deleteData true if uninstall also deletes the harness's plugin data
 */
public record Arguments(Operation operation, String harness, String tag, Path home, boolean deleteData)
{
	/**
	 * The complete operations exposed by the installer.
	 */
	public enum Operation
	{
		INSTALL, UPDATE, STATUS, UNINSTALL
	}

	/**
	 * Parses the complete argument vector, refusing unknown, repeated, reordered or operation-inapplicable inputs.
	 *
	 * @param args      the caller's arguments
	 * @param harnesses identifiers of the installed activation providers
	 * @return the validated operation
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if the arguments do not match the installer grammar
	 */
	public static Arguments parse(String[] args, Set<String> harnesses)
	{
		requireThat(args, "args").isNotNull();
		requireThat(harnesses, "harnesses").isNotNull();
		if (args.length == 0)
			throw usage();
		Operation operation = switch (args[0])
		{
			case "install" -> Operation.INSTALL;
			case "update" -> Operation.UPDATE;
			case "status" -> Operation.STATUS;
			case "uninstall" -> Operation.UNINSTALL;
			default -> throw usage();
		};
		if (operation == Operation.STATUS && args.length == 1)
			return new Arguments(operation, "", null, null, false);
		if (operation == Operation.UNINSTALL && args.length <= 2)
		{
			if (args.length == 2 && !args[1].equals("--delete-data"))
				throw usage();
			return new Arguments(operation, "", null, null, args.length == 2);
		}
		boolean validLength = switch (operation)
		{
			case UPDATE, UNINSTALL -> args.length == 3 || args.length == 4;
			case STATUS -> args.length == 3;
			case INSTALL -> args.length == 3 || args.length == 5;
		};
		if (!validLength || !args[1].equals("--harness") || !harnesses.contains(args[2]))
			throw usage();
		String harness = args[2];
		return switch (operation)
		{
			case INSTALL -> parseInstall(args, harness);
			case UNINSTALL ->
			{
				if (args.length == 4 && !args[3].equals("--delete-data"))
					throw usage();
				yield new Arguments(operation, harness, null, null, args.length == 4);
			}
			case UPDATE -> new Arguments(operation, harness, parseTag(args), null, false);
			case STATUS -> new Arguments(operation, harness, null, null, false);
		};
	}

	/**
	 * Parses install's optional harness home.
	 *
	 * @param args    the caller's arguments, of length 3 or 5
	 * @param harness the validated harness identifier
	 * @return the install operation
	 * @throws IllegalArgumentException if the option is not {@code --home <dir>}
	 */
	private static Arguments parseInstall(String[] args, String harness)
	{
		if (args.length == 3)
			return new Arguments(Operation.INSTALL, harness, null, null, false);
		if (!args[3].equals("--home"))
			throw usage();
		try
		{
			return new Arguments(Operation.INSTALL, harness, null, Path.of(args[4]), false);
		}
		catch (InvalidPathException _)
		{
			throw usage();
		}
	}

	/**
	 * Parses the optional release argument of update.
	 *
	 * @param args the caller's arguments, of length 3 or 4
	 * @return the canonical release tag, or null for the latest approved release
	 * @throws IllegalArgumentException if the argument is neither {@code latest} nor a canonical {@code v}-prefixed
	 *                                  release version
	 */
	private static String parseTag(String[] args)
	{
		if (args.length == 3 || args[3].equals("latest"))
			return null;
		if (!args[3].startsWith("v"))
			throw usage();
		try
		{
			return ReleaseVersion.parse(args[3].substring(1)).tag();
		}
		catch (TrustException _)
		{
			throw usage();
		}
	}

	/**
	 * Creates the validated argument value.
	 *
	 * @param operation  the requested complete operation
	 * @param harness    the selected provider, or empty for status or uninstall of the installation's own harness
	 * @param tag        the release tag that update selects, or null
	 * @param home       the harness home that install names, or null
	 * @param deleteData true if uninstall also deletes the harness's plugin data
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
		return new IllegalArgumentException("Usage: installer install --harness <id> [--home <absolute dir>]; " +
			"update --harness <id> [vX.Y[.Z]|latest]; status [--harness <id>]; " +
			"uninstall [--harness <id>] [--delete-data]");
	}
}
