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
 * @param operation     the requested complete operation
 * @param harness       the selected provider, or empty for status or uninstall of the installation's own harness
 * @param tag           the release tag that install selects, or null for the latest approved release or another
 *                      operation
 * @param home          the harness home that install names, or null to use the home that the user's environment
 *                      selects, or for another operation
 * @param deleteData    true if uninstall also deletes the harness's plugin data
 * @param createProfile true if the user allows install to create a permission profile for CAT, when the harness
 *                      configuration needs one
 */
public record Arguments(Operation operation, String harness, String tag, Path home, boolean deleteData,
	boolean createProfile)
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
			case "status" -> Operation.STATUS;
			case "uninstall" -> Operation.UNINSTALL;
			default -> throw usage();
		};
		if (operation == Operation.STATUS && args.length == 1)
			return new Arguments(operation, "", null, null, false, false);
		if (operation == Operation.UNINSTALL && args.length <= 2)
		{
			if (args.length == 2 && !args[1].equals("--delete-data"))
				throw usage();
			return new Arguments(operation, "", null, null, args.length == 2, false);
		}
		boolean validLength = switch (operation)
		{
			case UNINSTALL -> args.length == 3 || args.length == 4;
			case STATUS -> args.length == 3;
			case INSTALL -> args.length >= 3 && args.length <= 7;
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
				yield new Arguments(operation, harness, null, null, args.length == 4, false);
			}
			case STATUS -> new Arguments(operation, harness, null, null, false, false);
		};
	}

	/**
	 * Parses install's optional harness home, optional release and optional permission to create a permission
	 * profile, in that order.
	 *
	 * @param args    the caller's arguments, of length 3 to 7
	 * @param harness the validated harness identifier
	 * @return the install operation
	 * @throws IllegalArgumentException if the options are not
	 *                                  {@code [--home <dir>] [vX.Y[.Z]|latest] [--create-profile]}
	 */
	private static Arguments parseInstall(String[] args, String harness)
	{
		int next = 3;
		Path home = null;
		if (args.length > next && args[next].equals("--home"))
		{
			if (args.length < next + 2)
				throw usage();
			try
			{
				home = Path.of(args[next + 1]);
			}
			catch (InvalidPathException _)
			{
				throw usage();
			}
			next += 2;
		}
		String tag = null;
		if (args.length > next && !args[next].equals("--create-profile"))
		{
			tag = parseTag(args[next]);
			next += 1;
		}
		boolean createProfile = args.length > next && args[next].equals("--create-profile");
		if (createProfile)
			next += 1;
		if (args.length != next)
			throw usage();
		return new Arguments(Operation.INSTALL, harness, tag, home, false, createProfile);
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
		return new IllegalArgumentException("Usage: installer install --harness <id> [--home <absolute dir>] " +
			"[vX.Y[.Z]|latest] [--create-profile]; status [--harness <id>]; uninstall [--harness <id>] [--delete-data]");
	}
}
