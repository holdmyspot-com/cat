package io.github.cowwoc.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.WorkspaceFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

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
 *   profile for the contract author, judge and prompt author. An empty dimension starts the session without
 *   selecting it.
 * @param files the fixtures that a trial's workspace receives on top of {@code source} before the first turn;
 *   {@link WorkspaceFixtures#NONE} for every other session
 * @param plugins the plugin directories that a trial installs, and no other plugin; empty for every other session
 * @param followUp chooses at most one turn to send after the last of {@code turns}, from the replies so far; that
 *   turn gets a time limit of its own as long as {@code timeLimit}. Empty to end the session after {@code turns}.
 */
public record SessionRequest(SessionRole role, Part part, Path source, List<String> turns, List<Path> readOnly,
	Duration timeLimit, TrialProfile profile, WorkspaceFixtures files, List<Path> plugins,
	Optional<FollowUp> followUp)
{
	/**
	 * Creates a request whose workspace receives no fixtures.
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
	 *   {@code part} is not {@link Part#HELD_OUT}, or if the {@code part} of triage, a prompt author or the contract
	 *   author is not {@link Part#DEVELOPMENT}
	 */
	public SessionRequest(SessionRole role, Part part, Path source, List<String> turns, List<Path> readOnly,
		Duration timeLimit, TrialProfile profile)
	{
		this(role, part, source, turns, readOnly, timeLimit, profile, WorkspaceFixtures.NONE);
	}

	/**
	 * Creates a request that installs no plugin.
	 *
	 * @param role the kind of work that the session performs
	 * @param part the part of the contract whose content the session may read
	 * @param source the directory that the session works in
	 * @param turns the complete turn sequence, sent one turn at a time
	 * @param readOnly paths that the session may read but never write, such as a judge's evidence
	 * @param timeLimit how long the session may run before its remaining turns are abandoned
	 * @param profile the model and reasoning effort that the session starts under
	 * @param files the fixtures that a trial's workspace receives on top of {@code source} before the first turn
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code turns} is empty, if {@code timeLimit} is not positive, if a judge's
	 *   {@code part} is not {@link Part#HELD_OUT}, if the {@code part} of triage, a prompt author or the contract
	 *   author is not {@link Part#DEVELOPMENT}, or if a session other than a trial receives fixtures
	 */
	public SessionRequest(SessionRole role, Part part, Path source, List<String> turns, List<Path> readOnly,
		Duration timeLimit, TrialProfile profile, WorkspaceFixtures files)
	{
		this(role, part, source, turns, readOnly, timeLimit, profile, files, List.of());
	}

	/**
	 * Creates a request that ends after its listed turns.
	 *
	 * @param role the kind of work that the session performs
	 * @param part the part of the contract whose content the session may read
	 * @param source the directory that the session works in
	 * @param turns the complete turn sequence, sent one turn at a time
	 * @param readOnly paths that the session may read but never write, such as a judge's evidence
	 * @param timeLimit how long the session may run before its remaining turns are abandoned
	 * @param profile the model and reasoning effort that the session starts under
	 * @param files the fixtures that a trial's workspace receives on top of {@code source} before the first turn
	 * @param plugins the plugin directories that a trial installs, and no other plugin
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code turns} is empty, if {@code timeLimit} is not positive, if a judge's
	 *   {@code part} is not {@link Part#HELD_OUT}, if the {@code part} of triage, a prompt author or the contract
	 *   author is not {@link Part#DEVELOPMENT}, or if a session other than a trial receives fixtures or plugins
	 */
	public SessionRequest(SessionRole role, Part part, Path source, List<String> turns, List<Path> readOnly,
		Duration timeLimit, TrialProfile profile, WorkspaceFixtures files, List<Path> plugins)
	{
		this(role, part, source, turns, readOnly, timeLimit, profile, files, plugins, Optional.empty());
	}

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
	 * @param files the fixtures that a trial's workspace receives on top of {@code source} before the first turn
	 * @param plugins the plugin directories that a trial installs, and no other plugin
	 * @param followUp chooses at most one turn to send after the last of {@code turns}
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code turns} is empty, if {@code timeLimit} is not positive, if a judge's
	 *   {@code part} is not {@link Part#HELD_OUT}, if the {@code part} of triage, a prompt author or the contract
	 *   author is not {@link Part#DEVELOPMENT}, or if a session other than a trial receives fixtures or plugins
	 */
	public SessionRequest
	{
		requireThat(role, "role").isNotNull();
		requireThat(part, "part").isNotNull();
		Part rolePart = switch (role)
		{
			case TRIAL -> part;
			case JUDGE -> Part.HELD_OUT;
			case TRIAGE, PROMPT_AUTHOR, CONTRACT_AUTHOR -> Part.DEVELOPMENT;
		};
		if (part != rolePart)
			throw new IllegalArgumentException("A " + role + " session reads the " + rolePart + " part, not " + part);
		requireThat(source, "source").isNotNull();
		requireThat(turns, "turns").isNotEmpty();
		requireThat(readOnly, "readOnly").isNotNull();
		requireThat(timeLimit, "timeLimit").isNotNull();
		requireThat(timeLimit.isPositive(), "timeLimit.isPositive").isTrue();
		requireThat(profile, "profile").isNotNull();
		requireThat(files, "files").isNotNull();
		requireThat(plugins, "plugins").isNotNull();
		requireThat(followUp, "followUp").isNotNull();
		if (role != SessionRole.TRIAL && !files.isEmpty())
			throw new IllegalArgumentException("A " + role + " session receives no fixtures");
		if (role != SessionRole.TRIAL && !plugins.isEmpty())
			throw new IllegalArgumentException("A " + role + " session installs no plugin");
		turns = List.copyOf(turns);
		plugins = List.copyOf(plugins);
		readOnly = List.copyOf(readOnly);
	}
}
