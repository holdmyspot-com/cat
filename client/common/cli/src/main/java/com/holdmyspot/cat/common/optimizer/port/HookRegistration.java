package com.holdmyspot.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * A hook that a file of the prompt system or of a trial plugin registers, as trials load it.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param plugin  the name of the trial plugin whose file registers the hook, or an empty string for a file of the
 *                project
 * @param file    the file that registers the hook, relative to the plugin directory or to the project, with
 *                {@code /} separators
 * @param event   the event that runs the hook, such as {@code PostToolUse}
 * @param matcher the matcher that selects the event's occurrences, or an empty string if the registration has none
 * @param command the command that the hook runs, as the file spells it, or the hook's type if it runs no command
 */
public record HookRegistration(String plugin, String file, String event, String matcher, String command)
{
	/**
	 * Creates a hook registration.
	 *
	 * @param plugin  the name of the trial plugin whose file registers the hook, or an empty string for a file of the
	 *                project
	 * @param file    the file that registers the hook, relative to the plugin directory or to the project
	 * @param event   the event that runs the hook
	 * @param matcher the matcher that selects the event's occurrences, or an empty string if the registration has
	 *                none
	 * @param command the command that the hook runs, or the hook's type if it runs no command
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code file} or {@code event} are blank
	 */
	public HookRegistration
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(file, "file").isNotBlank();
		requireThat(event, "event").isNotBlank();
		requireThat(matcher, "matcher").isNotNull();
		requireThat(command, "command").isNotNull();
	}
}
