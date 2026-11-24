package com.holdmyspot.cat.common.optimizer.port;

import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.runner.AuthenticationEvidence;
import com.holdmyspot.cat.common.runner.RetainedTrialClaim;
import com.holdmyspot.cat.common.runner.RetainedTrialRequest;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Starts nested sessions through the current harness's runner.
 * <p>
 * Each session runs in a fresh, isolated session folder. Implementations hold one shared-capacity permit for the
 * session's lifetime.
 */
public interface SessionPort
{
	/**
	 * Starts a session.
	 *
	 * @param request what the session needs
	 * @return a handle that awaits or interrupts the session
	 * @throws NullPointerException if {@code request} is null
	 * @throws IOException if the session cannot be started
	 */
	SessionHandle start(SessionRequest request) throws IOException;

	/**
	 * Indicates whether the port supports recovery by original trial request ID.
	 *
	 * @return whether a broker can inspect and continue trials started by this port
	 */
	default boolean recoversTrials()
	{
		return false;
	}

	/**
	 * Launches a tracked trial or continues its claimed prefix.
	 *
	 * @param request the trial's original inputs
	 * @param requestId the request identifier recorded before the original launch
	 * @param claim the prefix that the broker must recheck before a resume, or empty for a fresh launch
	 * @return the running session's handle
	 * @throws IOException if the trial cannot be started or continued
	 */
	default SessionHandle startTrial(SessionRequest request, UUID requestId, Optional<RetainedTrialClaim> claim)
		throws IOException
	{
		if (claim.isPresent())
			throw new IOException("This session port does not support trial recovery");
		return start(request);
	}

	/**
	 * Starts a contract author and suspends it after its configured turns, or resumes its checkpoint for one
	 * correction turn and completes the conversation. A successful resumed result includes the retained replies and
	 * the correction reply, with usage for the conversation.
	 *
	 * @param request the original author turns or the single correction turn
	 * @param requestId the request identifier recorded before the original launch
	 * @param claim the checkpoint that the broker must recheck, or empty for a fresh launch
	 * @return the running session's handle
	 * @throws IllegalArgumentException if the request or claim does not match the author phase
	 * @throws IOException if the phase cannot be started
	 */
	default SessionHandle startContractAuthor(SessionRequest request, UUID requestId, Optional<RetainedTrialClaim> claim)
		throws IOException
	{
		throw new IOException("This session port does not support contract-author recovery");
	}

	/**
	 * Reconstructs the author result from the broker's acknowledged correction without opening a harness session.
	 *
	 * @param request the original author request
	 * @param predecessor the initial author checkpoint
	 * @param successor the observed corrected checkpoint
	 * @return the complete author result with both phases' evidence
	 * @throws IOException if the result cannot be recovered
	 */
	default SessionResult.Completed recoverContractAuthor(SessionRequest request, RetainedTrialClaim predecessor,
		RetainedTrialClaim successor) throws IOException
	{
		throw new IOException("This session port cannot export a retained author correction");
	}

	/**
	 * Indicates whether the port can suspend and continue a contract author through the broker.
	 *
	 * @return whether this port supports contract-author recovery
	 */
	default boolean recoversContractAuthor()
	{
		return false;
	}

	/**
	 * Asks the broker to inspect or discard a trial that the port launched.
	 *
	 * @param request the operation and original trial request identifier
	 * @return the broker's answer
	 * @throws IOException if the port cannot obtain the broker's answer
	 */
	default RetainedTrialResponse retainedTrial(RetainedTrialRequest request) throws IOException
	{
		throw new IOException("This session port does not support trial recovery");
	}

	/**
	 * Returns the end of the hold on the harness's shared capacity, which a rate-limited session of any campaign of the
	 * harness sets and which stops every campaign's sessions until it ends.
	 *
	 * @param now the current instant
	 * @return the end of the hold, or empty if no hold lasts beyond {@code now}
	 * @throws NullPointerException if {@code now} is null
	 * @throws IOException if the hold cannot be read
	 */
	default Optional<Instant> sharedHoldUntil(Instant now) throws IOException
	{
		return Optional.empty();
	}

	/**
	 * Returns the version of the environment in which this port's sessions run: the injected content, the hooks, the
	 * settings and limits that the runner applies, and the sandbox. A release of CAT changes it only when it changes
	 * what a session's model receives or how its actions take effect.
	 *
	 * @return the harness's session environment version
	 */
	int sessionEnvironmentVersion();

	/**
	 * Returns the CAT release that introduced this port's session environment version, which names the version to a
	 * user.
	 *
	 * @return the release, such as {@code 1.0}
	 */
	String sessionEnvironmentRelease();

	/**
	 * Returns the version of the judge that this port's sessions run: the judge's role profile, its prompt, rubric,
	 * decision rule and result schema, and the evidence section that
	 * {@link com.holdmyspot.cat.common.optimizer.evidence.EvidenceSection} renders from what
	 * {@link #readEvidence(Path)} reads for the same harness events. A release of CAT changes it whenever any of them
	 * changes.
	 *
	 * @return the harness's judge version
	 */
	int judgeVersion();

	/**
	 * Reads a completed trial's activity from its harness events: every tool call of the main agent and its children
	 * with its complete input and output, hook outcomes and assistant positions.
	 *
	 * @param transcript the trial's retained harness events
	 * @return the activity, without injected instructions, streaming fragments or telemetry
	 * @throws NullPointerException if {@code transcript} is null
	 * @throws IOException if the transcript cannot be read
	 */
	SessionEvidence readEvidence(Path transcript) throws IOException;

	/**
	 * Indicates whether the harness reports the exit status of the commands that a session runs, which the built-in tool
	 * {@code exit-status} needs.
	 *
	 * @return {@code true} if it does
	 */
	boolean reportsExitStatus();

	/**
	 * Classifies the host's login for this harness, so that the confirmation step can warn about a subscription before
	 * any session starts.
	 * <p>
	 * The classification uses the names and types of credential fields only, and never reads or returns a secret
	 * value.
	 *
	 * @return the login, or {@link HostLogin#UNKNOWN} if the credentials do not identify one
	 * @throws IOException if the credential file exists but cannot be read
	 */
	HostLogin hostLogin() throws IOException;

	/**
	 * Returns the authentication that this harness's sessions use, as each session's runner records it: the source home,
	 * the names of the settings and files that the runner copies from it or shares with the session, and the class of
	 * the login that the harness selects. It names no secret value.
	 *
	 * @return the authentication, or empty if the sessions' runner chooses it without this port, as inside a nested
	 *   session, whose runner passes on its own launch's source home
	 * @throws IOException if the source home refuses the sessions' launches or cannot be read
	 */
	Optional<AuthenticationEvidence> authentication() throws IOException;

	/**
	 * Returns the hooks of a plugin that sessions of this harness do not run because the user has not approved them
	 * in the harness.
	 *
	 * @param plugin the plugin directory
	 * @return each hook as its event and its command in Markdown code, in declaration order; empty if the harness runs
	 *   every hook of an installed plugin
	 * @throws NullPointerException if {@code plugin} is null
	 * @throws IOException if the plugin's manifest is missing or invalid, or a file cannot be read
	 */
	List<String> unapprovedHooks(Path plugin) throws IOException;

	/**
	 * Finds a host setting that keeps the harness sandbox from denying sessions of the development part reads of
	 * held-out content.
	 *
	 * @param project the project's root directory
	 * @return a message that names the setting and the file that holds it and asks the user to remove it, or empty if
	 *   every session of the development part is denied those reads
	 * @throws NullPointerException if {@code project} is null
	 * @throws IOException if a settings file exists but cannot be read
	 */
	Optional<String> findHeldOutExposure(Path project) throws IOException;

	/**
	 * Lists the prompt files that load although no harness event reports them, in production and in trials: each
	 * instruction file that the harness reads at the project root by convention, and, when CAT is installed, each
	 * file that CAT's rules give the primary agent. In production, CAT is the user's installation; in trials, it is
	 * the trial plugin that is CAT, if the campaign lists one. A file inside the project is named relative to it, a
	 * file that a project-built trial plugin's mapping copies from the project is named as the project's file, and
	 * other files are named by their absolute paths.
	 *
	 * @param project the project's root directory
	 * @param plugins the campaign's trial plugins with their base copies
	 * @return the files, once each per list, with the rule that loads each one
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if a file cannot be read or a rule is invalid
	 */
	LoadedSources loadedSources(Path project, List<PluginBase> plugins) throws IOException;

	/**
	 * Lists the hooks that trials register: each hook that the project's harness settings declare, followed by each
	 * hook that a trial plugin declares, as trials install the plugin. A file that a project-built trial plugin's
	 * mapping copies from the project is named as the project's file.
	 *
	 * @param project the project's root directory
	 * @param plugins the campaign's trial plugins with their base copies
	 * @return the hooks, in declaration order; a file that is missing or is not valid JSON registers none
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if a file cannot be read
	 */
	List<HookRegistration> hookRegistrations(Path project, List<PluginBase> plugins) throws IOException;

	/**
	 * Lists the command hooks that run before the model acts in a new session, at its start or when the user submits
	 * a prompt: those that the project's harness settings declare, followed by those of the trial plugins, each with
	 * the input that the harness documents for its event.
	 *
	 * @param project the directory that the session runs in
	 * @param plugins the campaign's trial plugins with their base copies
	 * @param home    the process home of the session, which holds an empty harness home, or empty for the user's
	 *                home
	 * @param prompt  the prompt that the session submits
	 * @return the hooks, in declaration order; a file that is missing or is not valid JSON declares none
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if a file cannot be read
	 */
	List<HookInvocation> earlyHooks(Path project, List<PluginBase> plugins, Optional<Path> home, String prompt)
		throws IOException;

	/**
	 * Finds the user's installed copies of a plugin that the project does not build, through the harness's install
	 * records.
	 *
	 * @param name the plugin's name, {@code <plugin>@<marketplace>}
	 * @return the absolute directory of each installed copy that the records name and that holds the harness's plugin
	 *   manifest; empty if none does
	 * @throws NullPointerException if {@code name} is null
	 * @throws IOException          if the install records exist but cannot be read
	 */
	List<Path> installedPlugins(String name) throws IOException;

	/**
	 * Returns the name of every plugin that the harness's install records name in the user's harness home, so that the
	 * user can choose the trial plugins among them.
	 *
	 * @return each name, {@code <plugin>@<marketplace>}, once, in record order; empty if the records do not exist
	 * @throws IOException if the install records exist but cannot be read
	 */
	List<String> installedPluginNames() throws IOException;

	/**
	 * Lists the directories that hold the nested sessions that this port starts, for the run of every campaign of the
	 * project. The results folder holds the sessions of the development part and the held-out results folder holds the
	 * sessions of the held-out part. Each nested session is recorded as {@code <directory>/<harness>/<id>/session.json}.
	 *
	 * @return the absolute directories, which may not exist yet
	 */
	List<Path> resultsFolders();
}
