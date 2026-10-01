package io.github.cowwoc.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * What a nested session needs in order to start.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param role the kind of work that the session performs
 * @param source the directory that the session works in
 * @param turns the complete turn sequence, sent one turn at a time
 * @param readOnly paths that the session may read but never write, such as a judge's evidence
 * @param timeLimit how long the session may run before its remaining turns are abandoned
 */
public record SessionRequest(SessionRole role, Path source, List<String> turns, List<Path> readOnly,
	Duration timeLimit)
{
	/**
	 * Creates a request.
	 *
	 * @param role the kind of work that the session performs
	 * @param source the directory that the session works in
	 * @param turns the complete turn sequence, sent one turn at a time
	 * @param readOnly paths that the session may read but never write, such as a judge's evidence
	 * @param timeLimit how long the session may run before its remaining turns are abandoned
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code turns} is empty or {@code timeLimit} is not positive
	 */
	public SessionRequest
	{
		requireThat(role, "role").isNotNull();
		requireThat(source, "source").isNotNull();
		requireThat(turns, "turns").isNotEmpty();
		requireThat(readOnly, "readOnly").isNotNull();
		requireThat(timeLimit, "timeLimit").isNotNull();
		requireThat(timeLimit.isPositive(), "timeLimit.isPositive").isTrue();
		turns = List.copyOf(turns);
		readOnly = List.copyOf(readOnly);
	}
}
