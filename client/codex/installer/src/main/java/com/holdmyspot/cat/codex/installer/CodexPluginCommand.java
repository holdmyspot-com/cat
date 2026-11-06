package com.holdmyspot.cat.codex.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A {@code codex plugin} command that the installer asks Codex to run, with how Codex's answer to it is read.
 * {@link CodexIntegration} runs the commands that the factory methods return, so that the choice of command and the
 * reading of Codex's answer can be checked without running Codex.
 *
 * @param arguments      the arguments after the recorded {@code codex} program
 * @param acceptsAbsence true if Codex's answer that CAT's plugin or marketplace is not installed, configured or found
 *                       counts as success although the exit status is not 0; false if only exit status 0 is success
 */
public record CodexPluginCommand(List<String> arguments, boolean acceptsAbsence)
{
	/**
	 * The plugin as Codex identifies it: the plugin name, then the marketplace name.
	 */
	private static final String PLUGIN_ID = "cat@cat";
	/**
	 * The marketplace name in CAT's marketplace metadata.
	 */
	private static final String MARKETPLACE_NAME = "cat";
	/**
	 * Matches Codex's answers that CAT's plugin or marketplace is not registered.
	 */
	private static final Pattern NOT_REGISTERED = Pattern.compile("(?i)\\bnot (installed|configured|found)\\b");

	/**
	 * Creates a command.
	 *
	 * @param arguments      the arguments after the recorded {@code codex} program
	 * @param acceptsAbsence true if Codex's answer that CAT is not registered counts as success
	 * @throws NullPointerException     if {@code arguments} is null
	 * @throws IllegalArgumentException if {@code arguments} is empty
	 */
	public CodexPluginCommand
	{
		requireThat(arguments, "arguments").isNotEmpty();
		arguments = List.copyOf(arguments);
	}

	/**
	 * Returns the commands that register CAT, in the order to run them: add CAT's marketplace, then add the plugin.
	 * Their only success is exit status 0.
	 *
	 * @param marketplaceRoot the absolute directory of CAT's marketplace
	 * @return the commands
	 * @throws NullPointerException if {@code marketplaceRoot} is null
	 */
	public static List<CodexPluginCommand> registration(Path marketplaceRoot)
	{
		requireThat(marketplaceRoot, "marketplaceRoot").isNotNull();
		return List.of(
			new CodexPluginCommand(List.of("plugin", "marketplace", "add", marketplaceRoot.toString()), false),
			new CodexPluginCommand(List.of("plugin", "add", PLUGIN_ID), false));
	}

	/**
	 * Returns the commands that unregister CAT, in the order to run them: remove the plugin, then remove its
	 * marketplace. Codex's answer that either is not installed, configured or found counts as success. Codex's
	 * {@code plugin remove} keeps the plugin's data directory and has no option to delete it, so the commands are the
	 * same whether or not the data is kept.
	 *
	 * @return the commands
	 */
	public static List<CodexPluginCommand> unregistration()
	{
		return List.of(new CodexPluginCommand(List.of("plugin", "remove", PLUGIN_ID), true),
			new CodexPluginCommand(List.of("plugin", "marketplace", "remove", MARKETPLACE_NAME), true));
	}

	/**
	 * Fails unless Codex's answer to this command is a success.
	 *
	 * @param result the exit status and output that Codex gave
	 * @throws NullPointerException if {@code result} is null
	 * @throws IOException          if the exit status is not 0 and either {@link #acceptsAbsence()} is false or the
	 *                              output does not say that CAT's plugin or marketplace is not installed, configured
	 *                              or found; the message names the command, the exit status and the output
	 */
	public void requireAccepted(CommandResult result) throws IOException
	{
		requireThat(result, "result").isNotNull();
		if (result.exitCode() == 0)
			return;
		if (acceptsAbsence && NOT_REGISTERED.matcher(result.output()).find())
			return;
		throw new IOException("\"codex " + String.join(" ", arguments) + "\" failed with status " +
			result.exitCode() + ": " + result.output().strip());
	}

	/**
	 * Runs this command through the recorded {@code codex} program and requires it to succeed.
	 *
	 * @param cli runs the recorded {@code codex} program
	 * @throws NullPointerException if {@code cli} is null
	 * @throws IOException          if the program cannot be run, or {@link #requireAccepted} refuses its answer
	 */
	public void run(HarnessCli cli) throws IOException
	{
		requireThat(cli, "cli").isNotNull();
		requireAccepted(cli.run(arguments));
	}
}
