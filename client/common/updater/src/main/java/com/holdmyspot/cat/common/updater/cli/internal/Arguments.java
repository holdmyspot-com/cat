package com.holdmyspot.cat.common.updater.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.updater.engine.UpdateEngine;
import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Set;

/**
 * A validated updater operation and its caller-supplied inputs.
 *
 * @param operation  the requested complete operation
 * @param harness    the selected provider, or empty for status or uninstall of the installation's own harness
 * @param tag        the requested release tag; for update null selects the latest approved release, and for install
 *                   {@link UpdateEngine#LATEST} selects it and null selects the release that the installation runs
 * @param home       the harness home for setup, or null for another operation
 * @param plugin     the local plugin tree that setup installs instead of the signed release, or null
 * @param deleteData true if uninstall also deletes the harness's plugin data
 */
public record Arguments(Operation operation, String harness, String tag, Path home, Path plugin, boolean deleteData)
{
	/**
	 * The complete operations exposed by the updater.
	 */
	public enum Operation
	{
		SETUP, INSTALL, UPDATE, STATUS, UNINSTALL
	}

	/**
	 * Parses the complete argument vector, refusing unknown, repeated, reordered or operation-inapplicable inputs.
	 *
	 * @param args      the caller's arguments
	 * @param harnesses identifiers of the installed activation providers
	 * @return the validated operation
	 * @throws NullPointerException     if any of the arguments are null
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
			case "setup" -> Operation.SETUP;
			case "install" -> Operation.INSTALL;
			case "update" -> Operation.UPDATE;
			case "status" -> Operation.STATUS;
			case "uninstall" -> Operation.UNINSTALL;
			default -> throw usage();
		};
		if (operation == Operation.STATUS && args.length == 1)
			return new Arguments(operation, "", null, null, null, false);
		if (operation == Operation.UNINSTALL && args.length <= 2)
		{
			if (args.length == 2 && !args[1].equals("--delete-data"))
				throw usage();
			return new Arguments(operation, "", null, null, null, args.length == 2);
		}
		boolean validLength = switch (operation)
		{
			case INSTALL, UPDATE, UNINSTALL -> args.length == 3 || args.length == 4;
			case STATUS -> args.length == 3;
			case SETUP -> args.length == 5 || args.length == 7;
		};
		if (!validLength || !args[1].equals("--harness") || !harnesses.contains(args[2]))
			throw usage();
		String harness = args[2];
		return switch (operation)
		{
			case SETUP -> parseSetup(args, harness);
			case UNINSTALL ->
			{
				if (args.length == 4 && !args[3].equals("--delete-data"))
					throw usage();
				yield new Arguments(operation, harness, null, null, null, args.length == 4);
			}
			case INSTALL, UPDATE -> new Arguments(operation, harness, parseTag(args, operation), null, null, false);
			case STATUS -> new Arguments(operation, harness, null, null, null, false);
		};
	}

	/**
	 * Parses setup's harness home and optional local plugin tree.
	 *
	 * @param args    the caller's arguments, of length 5 or 7
	 * @param harness the validated harness identifier
	 * @return the setup operation
	 * @throws IllegalArgumentException if the options are not {@code --home <dir> [--plugin <dir>]}, in that order
	 */
	private static Arguments parseSetup(String[] args, String harness)
	{
		if (!args[3].equals("--home") || (args.length == 7 && !args[5].equals("--plugin")))
			throw usage();
		try
		{
			Path plugin = null;
			if (args.length == 7)
				plugin = Path.of(args[6]);
			return new Arguments(Operation.SETUP, harness, null, Path.of(args[4]), plugin, false);
		}
		catch (InvalidPathException _)
		{
			throw usage();
		}
	}

	/**
	 * Parses the optional release argument of install and update.
	 *
	 * @param args      the caller's arguments, of length 3 or 4
	 * @param operation install or update
	 * @return the canonical release tag; {@link UpdateEngine#LATEST} for install's latest approved release; null for
	 *         update's latest approved release, or for install without a release argument
	 * @throws IllegalArgumentException if the argument is neither {@code latest} nor a canonical {@code v}-prefixed
	 *                                  release version
	 */
	private static String parseTag(String[] args, Operation operation)
	{
		if (args.length == 3)
			return null;
		if (args[3].equals(UpdateEngine.LATEST))
		{
			if (operation == Operation.INSTALL)
				return UpdateEngine.LATEST;
			return null;
		}
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
	 * @param tag        the requested release tag, or null for the latest release
	 * @param home       the harness home for setup, or null for another operation
	 * @param plugin     the local plugin tree that setup installs instead of the signed release, or null
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
		return new IllegalArgumentException("Usage: cat-updater setup --harness <id> --home <absolute dir> " +
			"[--plugin <absolute dir>]; install --harness <id> [vX.Y[.Z]|latest]; " +
			"update --harness <id> [vX.Y[.Z]|latest]; status [--harness <id>]; " +
			"uninstall [--harness <id>] [--delete-data]");
	}
}
