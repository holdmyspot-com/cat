package com.holdmyspot.cat.common.optimizer.cli;


import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.AbstractJvmScope;
import com.holdmyspot.cat.common.HomeProject;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.SessionIgnore;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.campaign.Lifecycle;
import com.holdmyspot.cat.common.optimizer.campaign.LogicalTrial;
import com.holdmyspot.cat.common.optimizer.campaign.TrialState;
import com.holdmyspot.cat.common.optimizer.execution.CapCalibrator;
import com.holdmyspot.cat.common.optimizer.execution.SliceSettings;
import com.holdmyspot.cat.common.optimizer.port.HostInterruptSignal;
import com.holdmyspot.cat.common.optimizer.port.PluginBase;
import com.holdmyspot.cat.common.optimizer.port.HookInspection;
import com.holdmyspot.cat.common.optimizer.port.ProfileInventory;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.proposal.SymbolicLinkProbe;
import com.holdmyspot.cat.common.optimizer.store.CampaignStore;
import com.holdmyspot.cat.common.optimizer.store.DevelopmentJudgeRecords;
import com.holdmyspot.cat.common.optimizer.store.FileCampaignStore;
import com.holdmyspot.cat.common.optimizer.store.JudgeRecordingStore;
import com.holdmyspot.cat.common.optimizer.store.TrialRecovery;
import com.holdmyspot.cat.common.optimizer.workspace.Forks;
import com.holdmyspot.cat.common.runner.HarnessSandbox;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse;
import com.holdmyspot.cat.common.runner.TrialPluginInput;
import com.holdmyspot.cat.common.secrets.OptimizerCommandScopeAccess;
import com.holdmyspot.cat.common.secrets.OptimizerCommandScopeSecrets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Owns the values and services shared while one {@code prompt-optimizer} command runs.
 * <p>
 * A launcher process runs one command, so this scope lives as long as the process's command. Each harness supplies a
 * production scope that resolves the process environment; tests supply a scope of fakes. The core reads no
 * environment of its own.
 * <p>
 * Values that depend on the project, such as the project root and the campaign store, are resolved when a command
 * first needs them, so that a working directory outside a Git repository is refused by the command rather than by the
 * scope's creation.
 * <p>
 * The campaigns and nested sessions of a command live in the CAT session directory of its host session.
 * The other sessions' campaigns are only read.
 */
public abstract class AbstractOptimizerCommandScope extends AbstractJvmScope implements OptimizerCommandScope
{
	static
	{
		OptimizerCommandScopeSecrets.setOptimizerCommandScopeAccess(new OptimizerCommandScopeAccess()
		{
			@Override
			public SliceSettings sliceSettings(AbstractOptimizerCommandScope scope)
			{
				return scope.sliceSettings();
			}

			@Override
			public Path projectRoot(AbstractOptimizerCommandScope scope) throws CommandRefusedException, IOException
			{
				return scope.projectRoot();
			}

			@Override
			public SessionDirectory sessionDirectory(AbstractOptimizerCommandScope scope)
				throws CommandRefusedException, IOException
			{
				return scope.sessionDirectory();
			}

			@Override
			public CampaignStore store(AbstractOptimizerCommandScope scope) throws CommandRefusedException, IOException
			{
				return scope.store();
			}

			@Override
			public List<Path> heldOutDirectories(AbstractOptimizerCommandScope scope) throws IOException
			{
				return scope.heldOutDirectories();
			}
		});
	}

	private Path projectRoot;
	private SessionDirectory sessionDirectory;
	private boolean sessionDirectoryCreated;
	private boolean ignoreNoticeTaken;
	private CampaignStore store;

	/**
	 * Creates an open scope.
	 *
	 * @param parent the owned process scope
	 * @throws NullPointerException if {@code parent} is null
	 */
	protected AbstractOptimizerCommandScope(JvmScope parent)
	{
		super(parent);
	}

	@Override
	public abstract SessionPort sessions() throws IOException;

	/**
	 * Reads metadata from the installed peer evaluator within this command's sandbox.
	 *
	 * @param evaluator the requested peer harness
	 * @param project   the absolute original project directory
	 * @param staged    the absolute existing staged source
	 * @param target    the target relative to both directories
	 * @return the peer's metadata report
	 * @throws IOException          if the peer cannot be selected or does not produce a valid report
	 * @throws InterruptedException if this command is interrupted while waiting for the peer
	 */
	public final EvaluatorMetadata peerEvaluatorMetadata(Harness evaluator, Path project, Path staged, Path target)
		throws IOException, InterruptedException
	{
		ensureOpen();
		return PeerEvaluator.read(json(), executableFiles(), peerEvaluatorArtifact(evaluator), evaluator,
			project, staged, target);
	}

	/**
	 * Reads metadata using the selected evaluator's ordered trial plugin bases.
	 *
	 * @param evaluator the requested peer harness
	 * @param project the absolute original project directory
	 * @param staged the absolute existing staged source
	 * @param target the target relative to both directories
	 * @param plugins the selected plugin bases
	 * @return the peer's metadata report
	 * @throws IOException if the peer cannot be selected or does not produce a valid report
	 * @throws InterruptedException if this command is interrupted while waiting for the peer
	 */
	public final EvaluatorMetadata peerEvaluatorMetadata(Harness evaluator, Path project, Path staged, Path target,
		List<PluginBase> plugins) throws IOException, InterruptedException
	{
		if (plugins.isEmpty())
			return peerEvaluatorMetadata(evaluator, project, staged, target);
		ensureOpen();
		return PeerEvaluator.read(json(), executableFiles(), peerEvaluatorArtifact(evaluator), evaluator,
			project, staged, target, plugins, temporaryDirectory());
	}

	/**
	 * Reads the selected peer evaluator's installed copies for a configured plugin name.
	 *
	 * @param evaluator the requested peer harness
	 * @param project the absolute original project directory
	 * @param name the configured plugin name
	 * @return the directories reported by that harness's installation
	 * @throws IOException if the peer or its installation cannot be inspected
	 * @throws InterruptedException if this command is interrupted while waiting for the peer
	 */
	public final List<Path> peerInstalledPlugins(Harness evaluator, Path project, String name)
		throws IOException, InterruptedException
	{
		ensureOpen();
		return PeerEvaluator.installed(json(), executableFiles(), peerEvaluatorArtifact(evaluator), evaluator,
			project, name);
	}

	/**
	 * Resolves the installed artifact of the requested peer evaluator.
	 *
	 * @param evaluator the requested peer harness
	 * @return the installed artifact directory
	 * @throws IOException if no supported installed artifact can be selected
	 */
	protected Path peerEvaluatorArtifact(Harness evaluator) throws IOException
	{
		throw new IOException("No installed evaluator route is available for " + evaluator.productName());
	}

	/**
	 * Returns the port that runs the installed evaluator's trials through the owner.
	 *
	 * @param evaluator the selected peer harness
	 * @param metadata  the metadata from the selected installed evaluator
	 * @return the peer's trial port
	 * @throws IOException if no installed evaluator can launch through the owner
	 */
	protected SessionPort peerTrialSessions(Harness evaluator, EvaluatorMetadata metadata) throws IOException
	{
		throw new IOException("No installed trial evaluator is available for " + evaluator.productName());
	}

	/**
	 * Opens a read-only adapter for this evaluator's installed harness and user configuration.
	 *
	 * @param project the original project
	 * @param results a temporary path for inspection bookkeeping
	 * @return the native inspection adapter
	 * @throws IOException if the adapter cannot be initialized
	 */
	protected SessionPort evaluatorInspection(Path project, Path results) throws IOException
	{
		return sessions();
	}

	/**
	 * Returns why the selected evaluator's startup-hook comparison cannot run.
	 *
	 * @param evaluator the harness that normally delivers the hook
	 * @return the reason when the evaluator's native hook authority cannot be verified
	 */
	protected Optional<String> hookComparisonRefusal(Harness evaluator)
	{
		return Optional.empty();
	}

	/**
	 * Finds the broker route for a peer evaluator's native hook comparison.
	 *
	 * @param evaluator the harness that normally delivers the hook
	 * @return the peer comparison when supported by this scope
	 */
	Optional<HookCheck.PeerComparator> peerHookComparison(Harness evaluator)
	{
		return Optional.empty();
	}

	/**
	 * Finds the native approval resolver for a startup-hook comparison.
	 *
	 * @param evaluator the harness that normally delivers the hook
	 * @param project the owner's original project
	 * @param prompt the fixed user prompt for the comparison
	 * @return the native resolver, or empty when this scope has no native route for the evaluator
	 */
	protected Optional<HookPairResolver> nativeHookComparison(Harness evaluator, Path project, String prompt)
	{
		return Optional.empty();
	}

	/**
	 * Selects and resolves both invocations using the evaluator's native approval rules.
	 */
	@FunctionalInterface
	protected interface HookPairResolver
	{
		/**
		 * Resolves the eligible invocations for the two comparison workspaces.
		 *
		 * @param production the full workspace copy
		 * @param trial the trial workspace copy
		 * @param trialHome the fresh trial home
		 * @param plugins the staged plugin inputs for the trial
		 * @return the pairs that can run and the native approval limitations, when observed
		 * @throws IOException if the resolver cannot establish the native approvals
		 * @throws InterruptedException if inspection is interrupted
		 */
		HookInspection read(Path production, Path trial, Path trialHome, List<TrialPluginInput> plugins)
			throws IOException, InterruptedException;
	}

	/**
	 * Starts a startup-hook comparison process. A launcher outside its host's tool sandbox must enforce
	 * the host's policy on the copied project before it starts the command.
	 *
	 * @param builder the command, copied project directory, environment and standard I/O
	 * @param scratch the comparison directory that holds both copies
	 * @param trialHome the isolated trial home, or empty for a production comparison
	 * @return the running hook process
	 * @throws IOException if the process cannot start under the host's policy
	 */
	protected Process startHookProcess(ProcessBuilder builder, Path scratch, Optional<Path> trialHome)
		throws IOException
	{
		return builder.start();
	}

	/**
	 * Returns the probe that checks symbolic-link creation in this command's CAT temporary directory.
	 *
	 * @return the symbolic-link probe
	 * @throws IOException if the host session or its directory cannot be resolved
	 */
	protected SymbolicLinkProbe symbolicLinkProbe() throws IOException
	{
		try
		{
			return SymbolicLinkProbe.inDirectory(sessionDirectory().temp());
		}
		catch (CommandRefusedException e)
		{
			throw new IOException(e.getMessage(), e);
		}
	}

	@Override
	public abstract ProfileInventory profiles();

	@Override
	public abstract HostInterruptSignal interrupts();

	@Override
	public abstract FileReplacer fileReplacer();

	@Override
	public abstract Path directory();

	@Override
	public abstract Path home();

	@Override
	public abstract Path launcher();

	/**
	 * Returns the harness that runs the launcher.
	 *
	 * @return the harness
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract Harness harness();

	/**
	 * Returns the decision whether the harness that runs the launcher, which runs the campaign's trials, sandboxes
	 * them on a platform.
	 *
	 * @return the decision
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract HarnessSandbox trialSandbox();

	/**
	 * Returns why the request server that starts this command's nested sessions would refuse every session, found
	 * without sending a request or starting a session. Only sessions that go to the session's request server need one.
	 *
	 * @return the message that names the reason and the user's remedy, or empty if a request server serves the host
	 *   session or the sessions do not go to a request server
	 * @throws IllegalStateException if this scope is closed
	 * @throws IOException           if the host session's CAT session directory cannot be opened
	 */
	protected abstract Optional<String> launchRefusal() throws IOException;

	/**
	 * Returns why this command could not have its optimization worktrees added, repaired or removed, found without
	 * sending a request: the operations go to the session's request server, and none serves the host session.
	 *
	 * @return the message that names the command that starts the request server, or empty if the operations have a
	 *   receiver
	 * @throws IllegalStateException if this scope is closed
	 * @throws IOException           if the host session's CAT session directory cannot be opened
	 */
	protected abstract Optional<String> worktreeRefusal() throws IOException;

	/**
	 * Returns why this command is refused because no harness session runs it, found before the command reads or
	 * changes any state. Only a Claude Code or Codex session can start or resume a campaign.
	 *
	 * @return the message that names the remedy, or empty if a harness session runs the command
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract Optional<String> noHarnessRefusal();

	/**
	 * Returns why a command that starts sessions is refused because it runs in a nested session whose caller
	 * lets it request no sessions, or whose request depth or limit is spent, found before the command reads or changes
	 * any state.
	 *
	 * @return the message that names the caller's options that would allow the sessions, or empty if the command does
	 *   not run in a nested session or the nested session allows a further session
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract Optional<String> nestedSessionRefusal();

	/**
	 * Checks if an ordinary run can reach a recoverable trial. This reads campaign evidence and asks the broker for
	 * status before the command acquires a campaign lock or resumes cleanup. The driver rechecks before it claims.
	 *
	 * @param campaignId the campaign identifier
	 * @return whether the run has a verified retained trial it can continue
	 */
	final boolean hasRecoverableTrial(String campaignId)
	{
		try
		{
			Optional<RetainedTrialLookup> lookup = retainedTrialPreflight();
			if (lookup.isEmpty())
				return false;
			CampaignStore store = store();
			Optional<Campaign> loaded = store.load(campaignId);
			if (loaded.isEmpty())
				return false;
			Campaign campaign = loaded.get();
			if (campaign.header().harness() != harness() || campaign.lifecycle() != Lifecycle.ACTIVE ||
				!campaign.isConfirmed() || campaign.pending() != null || campaign.revisionRequested() ||
				campaign.awaitsTestRevision())
				return false;
			return hasReadyTrial(store, campaign, lookup.get());
		}
		catch (CommandRefusedException | IOException _)
		{
			return false;
		}
	}

	/**
	 * Looks for a running trial with no recorded terminal outcome, a current recovery record and a ready broker
	 * checkpoint that fits its observer progress.
	 *
	 * @param store the campaign store
	 * @param campaign the snapshot under consideration
	 * @param lookup the authenticated broker status lookup
	 * @return whether a trial has a matching recovery record and retained checkpoint
	 * @throws IOException if campaign evidence or broker status cannot be verified
	 */
	private boolean hasReadyTrial(CampaignStore store, Campaign campaign, RetainedTrialLookup lookup) throws IOException
	{
		String campaignId = campaign.header().id();
		List<CampaignEvent.Complete> outcomes = store.listUnadoptedOutcomes(campaign);
		for (LogicalTrial trial : campaign.trials())
		{
			if (trial.state() != TrialState.RUNNING ||
				outcomes.stream().anyMatch(outcome -> outcome.key().equals(trial.key())))
				continue;
			Optional<TrialRecovery> recovery = store.readTrialRecovery(campaignId, trial.key(),
				TrialRecovery.attemptOrdinal(trial));
			if (recovery.isEmpty())
				continue;
			TrialRecovery saved = recovery.get();
			RetainedTrialResponse status = lookup.status(saved.requestId());
			if (status.status() == RetainedTrialResponse.Status.READY && status.requestId().equals(saved.requestId()) &&
				status.identity().orElseThrow().session().orElseThrow().harness().equals(harness().commandName()) &&
				saved.canResumePrefix(trial, status.completedTurns().orElseThrow()))
				return true;
		}
		return false;
	}

	/**
	 * Opens a bounded status lookup for a nested command whose allowance is spent.
	 *
	 * @return the lookup, or empty if retained trials cannot be checked
	 * @throws IOException if the nested request channel cannot be read
	 */
	protected Optional<RetainedTrialLookup> retainedTrialPreflight() throws IOException
	{
		return Optional.empty();
	}

	/**
	 * Checks a retained trial through the nested session's request channel.
	 */
	@FunctionalInterface
	protected interface RetainedTrialLookup
	{
		/**
		 * Reads the broker's status of an original launch.
		 *
		 * @param requestId the original launch's identifier
		 * @return the broker's answer
		 * @throws IOException if a verified answer does not arrive
		 */
		RetainedTrialResponse status(UUID requestId) throws IOException;
	}

	/**
	 * Returns the source of campaign identifiers and draw seeds.
	 *
	 * @return the source
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract RandomGenerator random();

	/**
	 * Returns the launcher that runs {@code <harness> --version}.
	 *
	 * @return the launcher
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract HarnessVersion.Launcher harnessVersionLauncher();

	/**
	 * Returns the files, relative to the project, that decide which prompt files the harness loads.
	 *
	 * @return the files
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract List<Path> loaderConfiguration();

	/**
	 * Returns the user's harness settings, outside the project, that decide which prompt files the harness's trials
	 * load.
	 *
	 * @return the settings as text, or empty if the user sets none
	 * @throws IOException           if the user's settings cannot be read
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract Optional<String> userLoaderSettings() throws IOException;

	/**
	 * Returns the forks of the project's campaigns.
	 *
	 * @return the forks
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract Forks forks();

	/**
	 * Returns the calibration of the caps of certification attempts.
	 *
	 * @return the calibration
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract CapCalibrator calibrator();

	/**
	 * Validates the inputs of a launcher that runs outside its host sandbox.
	 *
	 * @param command the parsed command
	 * @throws IOException if its environment or paths cannot be trusted
	 */
	protected void validateLauncher(Command command) throws IOException
	{
		java.util.Objects.requireNonNull(command, "command");
	}

	/**
	 * Returns why a background launcher cannot run safely, or empty when it can.
	 *
	 * @return the refusal reason
	 * @throws IOException if the host cannot be identified
	 */
	protected Optional<String> backgroundRefusal() throws IOException
	{
		return Optional.empty();
	}

	/**
	 * Sends the host's exit notice after all state and delivery writes and lock releases.
	 *
	 * @param campaign the campaign identifier
	 * @throws IOException if the host refuses the notice
	 * @throws InterruptedException if the notice wait is interrupted
	 */
	protected void backgroundEnded(String campaign) throws IOException, InterruptedException
	{
		java.util.Objects.requireNonNull(campaign, "campaign");
	}

	/**
	 * Returns the slice's time budget: the 8-minute budget plus the 1-minute grace period that fits the skill's
	 * 10-minute tool timeout.
	 *
	 * @return the budget
	 * @throws IllegalStateException if this scope is closed
	 */
	final SliceSettings sliceSettings()
	{
		ensureOpen();
		return SliceSettings.DEFAULT;
	}

	/**
	 * Reads the harness's version. Each call runs the launcher, so a command that never needs the version, such as
	 * {@code status}, never runs it.
	 *
	 * @return the first non-blank line of {@code <harness> --version}, or {@code unknown} if there is none or the
	 *   command fails
	 * @throws IllegalStateException if this scope is closed
	 */
	final String harnessVersion()
	{
		return HarnessVersion.read(harness().commandName(), harnessVersionLauncher());
	}

	/**
	 * Returns the project root: the directory that holds {@code .git}, at or above the working directory.
	 *
	 * @return the root
	 * @throws IllegalStateException   if this scope is closed
	 * @throws CommandRefusedException if the working directory is not inside a Git repository, or the root is the
	 *                                 user's home folder or contains it, which {@link HomeProject} refuses as a CAT
	 *                                 project
	 * @throws IOException             if the working directory cannot be resolved
	 */
	final Path projectRoot() throws CommandRefusedException, IOException
	{
		ensureOpen();
		if (projectRoot == null)
		{
			Path start = directory().toRealPath();
			for (Path candidate = start; candidate != null; candidate = candidate.getParent())
			{
				if (Files.exists(candidate.resolve(".git")))
				{
					Optional<String> refusal = HomeProject.refusal(candidate, home());
					if (refusal.isPresent())
						throw new CommandRefusedException(refusal.get());
					projectRoot = candidate;
					return projectRoot;
				}
			}
			throw new CommandRefusedException(start + " is not inside a Git repository.");
		}
		return projectRoot;
	}

	/**
	 * Returns the CAT session directory of the host session that runs this command, without creating anything on disk
	 * unless {@link #createdSessionDirectory()} already did. A command reads and validates through this value, so that
	 * a command that is refused changes nothing in the project.
	 *
	 * @return the directory, which may not exist
	 * @throws IllegalStateException if this scope is closed
	 * @throws CommandRefusedException if the working directory is not inside a Git repository whose root may be a CAT
	 *   project, or the environment names no host session
	 * @throws IOException if the working directory cannot be resolved or the environment's session ID is invalid
	 */
	final SessionDirectory sessionDirectory() throws CommandRefusedException, IOException
	{
		ensureOpen();
		if (sessionDirectory == null)
			sessionDirectory = resolveSessionDirectory(projectRoot());
		return sessionDirectory;
	}

	/**
	 * Returns the CAT session directory of the host session that runs this command, creating it, its marker and the
	 * project's {@code .gitignore} entry on the first call. A command calls this only after it has validated everything
	 * that can refuse it.
	 *
	 * @return the directory
	 * @throws IllegalStateException if this scope is closed
	 * @throws CommandRefusedException if the working directory is not inside a Git repository whose root may be a CAT
	 *   project, or the environment names no host session
	 * @throws IOException if the working directory cannot be resolved or the directory cannot be created
	 */
	final SessionDirectory createdSessionDirectory() throws CommandRefusedException, IOException
	{
		ensureOpen();
		if (!sessionDirectoryCreated)
		{
			sessionDirectory = openSessionDirectory(projectRoot());
			sessionDirectoryCreated = true;
		}
		return sessionDirectory;
	}

	/**
	 * Returns, once per scope, the notice that creating the host session's CAT session directory added CAT's session
	 * directories to the project's {@code .gitignore}, or could not, in the form for the agent that reads the
	 * command's output. This method creates nothing.
	 *
	 * @return the notice; empty if the command has not created the directory, creating it left the file as it was, or
	 *   an earlier call returned the notice
	 */
	final Optional<String> takeIgnoreNotice()
	{
		ensureOpen();
		if (!sessionDirectoryCreated || ignoreNoticeTaken)
			return Optional.empty();
		ignoreNoticeTaken = true;
		return sessionDirectory.ignoreNotice().map(SessionIgnore.IgnoreNotice::forAgent);
	}

	/**
	 * Resolves the CAT session directory of the host session that runs this command without creating anything.
	 *
	 * @param projectRoot the project root
	 * @return the directory, which may not exist
	 * @throws CommandRefusedException if the environment names no host session
	 * @throws IOException if the environment's session ID is invalid
	 */
	protected abstract SessionDirectory resolveSessionDirectory(Path projectRoot)
		throws CommandRefusedException, IOException;

	/**
	 * Opens the CAT session directory of the host session that runs this command, creating it.
	 *
	 * @param projectRoot the project root
	 * @return the directory
	 * @throws CommandRefusedException if the environment names no host session
	 * @throws IOException if the directory cannot be created
	 */
	protected abstract SessionDirectory openSessionDirectory(Path projectRoot)
		throws CommandRefusedException, IOException;

	/**
	 * Returns the harness's CAT data directory, which outlives campaigns, sessions and plugin updates.
	 *
	 * @return the directory, which may not exist
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract Path dataDirectory();

	/**
	 * Returns the development judges that earlier campaigns qualified, kept in the harness's CAT data directory.
	 *
	 * @return the records
	 * @throws IllegalStateException if this scope is closed
	 */
	final DevelopmentJudgeRecords judgeRecords()
	{
		ensureOpen();
		return new DevelopmentJudgeRecords(dataDirectory(), json(), fileReplacer());
	}

	/**
	 * Returns the store of the campaigns of the host session that runs this command.
	 *
	 * @return the store
	 * @throws IllegalStateException if this scope is closed
	 * @throws CommandRefusedException if the working directory is not inside a Git repository whose root may be a CAT
	 *   project, or the environment names no host session
	 * @throws IOException if the working directory cannot be resolved or the session's directory cannot be created
	 */
	final CampaignStore store() throws CommandRefusedException, IOException
	{
		ensureOpen();
		if (store == null)
		{
			store = new JudgeRecordingStore(new FileCampaignStore(sessionDirectory().campaigns(), projectRoot(),
				json(), fileReplacer()), judgeRecords());
		}
		return store;
	}

	/**
	 * Returns the directory that holds the session folders of the nested sessions that this command's campaigns start
	 * through the harness's runner. The held-out results folder is its sibling.
	 *
	 * @return the directory, which may not exist
	 * @throws IllegalStateException if this scope is closed
	 * @throws IOException if the working directory is not inside a Git repository whose root may be a CAT project,
	 *   the environment names no host session, or the session's directory cannot be created
	 */
	protected final Path resultsFolder() throws IOException
	{
		try
		{
			return sessionDirectory().resultsFolder(harness().commandName());
		}
		catch (CommandRefusedException e)
		{
			throw new IOException(e.getMessage(), e);
		}
	}

	/**
	 * Returns the project of the host session that runs this command, which holds the results folder.
	 *
	 * @return the project root
	 * @throws IllegalStateException if this scope is closed
	 * @throws IOException if the working directory is not inside a Git repository whose root may be a CAT project, or
	 *   cannot be resolved
	 */
	protected final Path hostProject() throws IOException
	{
		try
		{
			return projectRoot();
		}
		catch (CommandRefusedException e)
		{
			throw new IOException(e.getMessage(), e);
		}
	}

	/**
	 * Lists the {@code held_out} directories of the campaigns of every session of the project.
	 *
	 * @return the real paths of those that exist
	 * @throws IllegalStateException if this scope is closed
	 * @throws IOException if the working directory is not inside a Git repository whose root may be a CAT project,
	 *   the environment names no host session, or the campaigns cannot be listed
	 */
	protected final List<Path> heldOutDirectories() throws IOException
	{
		try
		{
			List<Path> directories = new ArrayList<>();
			for (Path campaigns : sessionDirectory().campaignsBySession().values())
				directories.addAll(FileCampaignStore.heldOutDirectories(campaigns));
			return directories;
		}
		catch (CommandRefusedException e)
		{
			throw new IOException(e.getMessage(), e);
		}
	}
}
