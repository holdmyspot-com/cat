package com.holdmyspot.cat.claude.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A {@code claude plugin} command that the installer asks Claude Code to run, with the answers that count as its
 * success. {@link Integration} runs the commands that the factory methods return, so that the choice of command and
 * the reading of Claude Code's answer can be checked without running Claude Code.
 *
 * @param arguments the arguments after the recorded {@code claude} program
 * @param absence   the text of Claude Code's output that says the command had nothing to do and so counts as success
 *                  even though the exit status is not 0, or empty if only exit status 0 is success
 */
public record PluginCommand(List<String> arguments, String absence)
{
	/**
	 * Claude Code 2.1.318's answer, with exit status 1, to {@code plugin uninstall cat@cat} when CAT is not installed.
	 */
	private static final String PLUGIN_NOT_FOUND = "Plugin \"cat@cat\" not found";
	/**
	 * Claude Code 2.1.318's answer, with exit status 1, to {@code plugin marketplace remove cat} when the marketplace
	 * is not added.
	 */
	private static final String MARKETPLACE_NOT_FOUND = "Marketplace 'cat' not found";

	/**
	 * Creates a command.
	 *
	 * @param arguments the arguments after the recorded {@code claude} program
	 * @param absence   the text of Claude Code's output that counts as success although the exit status is not 0, or
	 *                  empty if only exit status 0 is success
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code arguments} is empty
	 */
	public PluginCommand
	{
		requireThat(arguments, "arguments").isNotEmpty();
		requireThat(absence, "absence").isNotNull();
		arguments = List.copyOf(arguments);
	}

	/**
	 * Returns the command that adds CAT's marketplace, which Claude Code accepts when it is already added.
	 *
	 * @param root the absolute directory of CAT's marketplace
	 * @return the command, whose only success is exit status 0
	 * @throws NullPointerException if {@code root} is null
	 */
	public static PluginCommand addMarketplace(Path root)
	{
		requireThat(root, "root").isNotNull();
		return new PluginCommand(List.of("plugin", "marketplace", "add", root.toString()), "");
	}

	/**
	 * Returns the command that makes Claude Code load the plugin from the marketplace. Claude Code's install leaves an
	 * installed plugin as it is, so an installed plugin is updated instead.
	 *
	 * @param installed true if Claude Code records a CAT installation in the user scope
	 * @return {@code plugin update cat@cat} if {@code installed}, otherwise {@code plugin install cat@cat}; the
	 * 	command's only success is exit status 0
	 */
	public static PluginCommand registerPlugin(boolean installed)
	{
		String operation;
		if (installed)
			operation = "update";
		else
			operation = "install";
		return new PluginCommand(List.of("plugin", operation, "cat@cat"), "");
	}

	/**
	 * Returns the commands that unregister CAT, in the order to run them: uninstall the plugin, then remove its
	 * marketplace. Claude Code's answer that either is absent is success.
	 *
	 * @param keepData true to pass {@code --keep-data}, because Claude Code's uninstall deletes the plugin's data
	 *                 directory otherwise
	 * @return the commands
	 */
	public static List<PluginCommand> unregistration(boolean keepData)
	{
		List<String> uninstall;
		if (keepData)
			uninstall = List.of("plugin", "uninstall", "--keep-data", "cat@cat");
		else
			uninstall = List.of("plugin", "uninstall", "cat@cat");
		return List.of(new PluginCommand(uninstall, PLUGIN_NOT_FOUND),
			new PluginCommand(List.of("plugin", "marketplace", "remove", "cat"), MARKETPLACE_NOT_FOUND));
	}

	/**
	 * Fails unless Claude Code's answer to this command is a success.
	 *
	 * @param result the exit status and output that Claude Code gave
	 * @throws NullPointerException if {@code result} is null
	 * @throws IOException          if the exit status is not 0 and the output does not contain {@link #absence()}; the
	 *                              message names the command, the exit status and the output
	 */
	public void requireAccepted(CommandResult result) throws IOException
	{
		requireThat(result, "result").isNotNull();
		if (result.exitCode() == 0)
			return;
		if (!absence.isEmpty() && result.output().contains(absence))
			return;
		throw new IOException("claude " + String.join(" ", arguments) + " failed with exit status " +
			result.exitCode() + ": " + result.output().strip());
	}

	/**
	 * Runs this command through the recorded {@code claude} program and requires it to succeed.
	 *
	 * @param cli runs the recorded {@code claude} program
	 * @throws NullPointerException if {@code cli} is null
	 * @throws IOException          if the program cannot be run, or {@link #requireAccepted} refuses its answer
	 */
	public void run(HarnessCli cli) throws IOException
	{
		requireThat(cli, "cli").isNotNull();
		requireAccepted(cli.run(arguments));
	}
}
