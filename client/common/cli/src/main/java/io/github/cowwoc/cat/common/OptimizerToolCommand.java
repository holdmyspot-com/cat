package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes supported inspection launcher commands and binds host tool identities to them.
 */
public final class OptimizerToolCommand
{
	private static final Pattern INSPECTION_COMMAND = Pattern.compile(
		"^\\s*(\"[^\"\\r\\n]*/client/bin/optimizer-inspect\"|'[^'\\r\\n]*/client/bin/optimizer-inspect')(?=\\s|$)");
	private static final Pattern WRAPPED_INSPECTION_COMMAND = Pattern.compile(
		"^\\s*/bin/bash\\s+-lc\\s+'(?<launcher>\"[^\"\\r\\n]*/client/bin/optimizer-inspect\")" +
			"(?=\\s|')[^']*'\\s*$");
	/**
	 * Finds a directly quoted inspection launcher or the supported Bash wrapped launcher.
	 *
	 * @param command complete host command to inspect
	 * @return the recognized command and insertion point, or empty when its shape is unsupported
	 * @throws NullPointerException if {@code command} is null
	 */
	public static Optional<OptimizerToolCommand> findInspectionCall(String command)
	{
		Objects.requireNonNull(command, "command");
		Matcher launcher = INSPECTION_COMMAND.matcher(command);
		if (launcher.find())
			return Optional.of(new OptimizerToolCommand(command, launcher.end(), false));

		launcher = WRAPPED_INSPECTION_COMMAND.matcher(command);
		if (!launcher.matches())
			return Optional.empty();
		return Optional.of(new OptimizerToolCommand(command, launcher.end("launcher"), true));
	}

	private final String command;
	private final int insertAt;
	private final boolean wrapped;

	/**
	 * Creates a recognized inspection command.
	 *
	 * @param command supported inspection launcher command
	 * @param insertAt position where host identity options are inserted
	 * @param wrapped whether the command runs through the supported Bash wrapper
	 */
	private OptimizerToolCommand(String command, int insertAt, boolean wrapped)
	{
		this.command = command;
		this.insertAt = insertAt;
		this.wrapped = wrapped;
	}

	/**
	 * Inserts the exact host identities as quoted inspection command options.
	 *
	 * @param session non-blank host session identity
	 * @param turn non-blank host turn identity
	 * @param toolUse non-blank host tool-use identity
	 * @param model non-blank host model identity
	 * @return command with the four quoted host identity options inserted in their established order
	 * @throws NullPointerException if any host identity is null
	 * @throws IllegalArgumentException if any host identity is blank
	 */
	public String bindHostIdentity(String session, String turn, String toolUse, String model)
	{
		Objects.requireNonNull(session, "session");
		Objects.requireNonNull(turn, "turn");
		Objects.requireNonNull(toolUse, "toolUse");
		Objects.requireNonNull(model, "model");
		requireThat(session, "session").isNotBlank();
		requireThat(turn, "turn").isNotBlank();
		requireThat(toolUse, "toolUse").isNotBlank();
		requireThat(model, "model").isNotBlank();

		String authority = " --host-session-id " + shellQuote(session) + " --host-turn-id " +
			shellQuote(turn) + " --host-tool-use-id " + shellQuote(toolUse) +
			" --host-model-id " + shellQuote(model);
		if (wrapped)
			authority = authority.replace("'", "'\"'\"'");
		return command.substring(0, insertAt) + authority + command.substring(insertAt);
	}

	/**
	 * Preserves a host value as one POSIX shell argument.
	 *
	 * @param value exact host value
	 * @return quoted shell token
	 */
	private static String shellQuote(String value)
	{
		return "'" + value.replace("'", "'\"'\"'") + "'";
	}
}
