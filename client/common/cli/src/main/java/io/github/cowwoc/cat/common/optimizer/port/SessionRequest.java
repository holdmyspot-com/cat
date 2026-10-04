package io.github.cowwoc.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * What a nested session needs in order to start.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param role the kind of work that the session performs
 * @param part the part of the contract whose content the session may read: {@link Part#HELD_OUT} for formal and
 *   baseline trials and for judges, which run under the held-out run root and are denied nothing, and
 *   {@link Part#DEVELOPMENT} for every other session, which is denied reading every held-out location
 * @param source the directory that the session works in
 * @param turns the complete turn sequence, sent one turn at a time
 * @param readOnly paths that the session may read but never write, such as a judge's evidence
 * @param timeLimit how long the session may run before its remaining turns are abandoned
 * @param profile the model and reasoning effort that the session starts under: a trial's cell profile, or the role
 *   profile for the deriver, judge and author. An empty dimension starts the session without
 *   selecting it.
 */
public record SessionRequest(SessionRole role, Part part, Path source, List<String> turns, List<Path> readOnly,
	Duration timeLimit, TrialProfile profile)
{
	/**
	 * Creates a request.
	 *
	 * @param role the kind of work that the session performs
	 * @param part the part of the contract whose content the session may read
	 * @param source the directory that the session works in
	 * @param turns the complete turn sequence, sent one turn at a time
	 * @param readOnly paths that the session may read but never write, such as a judge's evidence
	 * @param timeLimit how long the session may run before its remaining turns are abandoned
	 * @param profile the model and reasoning effort that the session starts under
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code turns} is empty, if {@code timeLimit} is not positive, if a judge's
	 *   {@code part} is not {@link Part#HELD_OUT}, or if the {@code part} of triage, an author or the deriver is not
	 *   {@link Part#DEVELOPMENT}
	 */
	public SessionRequest
	{
		requireThat(role, "role").isNotNull();
		requireThat(part, "part").isNotNull();
		Part rolePart = switch (role)
		{
			case TRIAL -> part;
			case JUDGE -> Part.HELD_OUT;
			case TRIAGE, AUTHOR, DERIVER -> Part.DEVELOPMENT;
		};
		if (part != rolePart)
			throw new IllegalArgumentException("A " + role + " session reads the " + rolePart + " part, not " + part);
		requireThat(source, "source").isNotNull();
		requireThat(turns, "turns").isNotEmpty();
		requireThat(readOnly, "readOnly").isNotNull();
		requireThat(timeLimit, "timeLimit").isNotNull();
		requireThat(timeLimit.isPositive(), "timeLimit.isPositive").isTrue();
		requireThat(profile, "profile").isNotNull();
		turns = List.copyOf(turns);
		readOnly = List.copyOf(readOnly);
	}
}
