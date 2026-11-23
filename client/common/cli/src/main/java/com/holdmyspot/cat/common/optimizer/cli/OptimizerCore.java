package com.holdmyspot.cat.common.optimizer.cli;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.optimizer.execution.SliceReports;
import com.holdmyspot.cat.common.optimizer.report.ReportStatus;
import com.holdmyspot.cat.common.optimizer.report.Status;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import tools.jackson.databind.node.ObjectNode;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.SessionIgnore;
import com.holdmyspot.cat.common.ProjectRuleLoader;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.CellTier;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationHistory;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationRecord;
import com.holdmyspot.cat.common.optimizer.campaign.Choice;
import com.holdmyspot.cat.common.optimizer.campaign.DeliveryRequest;
import com.holdmyspot.cat.common.optimizer.campaign.Failure;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.campaign.Header;
import com.holdmyspot.cat.common.optimizer.campaign.HistoryEntry;
import com.holdmyspot.cat.common.optimizer.campaign.Lifecycle;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementIdentity;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.campaign.PendingDecision;
import com.holdmyspot.cat.common.optimizer.campaign.Registry;
import com.holdmyspot.cat.common.optimizer.campaign.TestRevision;
import com.holdmyspot.cat.common.optimizer.campaign.TestRevisionRequest;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.campaign.TrialFiles;
import com.holdmyspot.cat.common.optimizer.contract.CertificationScope;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.Dependent;
import com.holdmyspot.cat.common.optimizer.contract.DerivedSource;
import com.holdmyspot.cat.common.optimizer.contract.Cell;
import com.holdmyspot.cat.common.optimizer.contract.InvalidProposalException;
import com.holdmyspot.cat.common.optimizer.contract.ProfileCatalog;
import com.holdmyspot.cat.common.optimizer.contract.PromptConfiguration;
import com.holdmyspot.cat.common.optimizer.contract.PromptConfigurationException;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestFiles;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestLocations;
import com.holdmyspot.cat.common.optimizer.contract.ProposalSchema;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.WorkspaceFile;
import com.holdmyspot.cat.common.optimizer.delivery.Delivery;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveryResult;
import com.holdmyspot.cat.common.optimizer.evidence.EvidenceSection;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.optimizer.execution.CampaignLock;
import com.holdmyspot.cat.common.optimizer.execution.Cleanup;
import com.holdmyspot.cat.common.optimizer.execution.DeliveryReport;
import com.holdmyspot.cat.common.optimizer.execution.Driver;
import com.holdmyspot.cat.common.optimizer.execution.DriverContext;
import com.holdmyspot.cat.common.optimizer.execution.GuardPilot;
import com.holdmyspot.cat.common.optimizer.execution.LeftoverNestedSessions;
import com.holdmyspot.cat.common.optimizer.execution.PilotResult;
import com.holdmyspot.cat.common.optimizer.execution.PilotSlice;
import com.holdmyspot.cat.common.optimizer.execution.ProcessHandleTerminator;
import com.holdmyspot.cat.common.optimizer.execution.SliceResult;
import com.holdmyspot.cat.common.optimizer.execution.SliceRunner;
import com.holdmyspot.cat.common.optimizer.execution.TrialSelection;
import com.holdmyspot.cat.common.optimizer.execution.prompt.ApproachRankingPrompt;
import com.holdmyspot.cat.common.optimizer.execution.prompt.JudgePrompt;
import com.holdmyspot.cat.common.optimizer.execution.prompt.PromptAuthorPrompt;
import com.holdmyspot.cat.common.optimizer.execution.prompt.TriagePrompt;
import com.holdmyspot.cat.common.optimizer.execution.prompt.ValuesPrompt;
import com.holdmyspot.cat.common.optimizer.inventory.Authority;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryScanner;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.ExtraPermissionSessions;
import com.holdmyspot.cat.common.optimizer.port.HookRegistration;
import com.holdmyspot.cat.common.optimizer.port.LoadedSources;
import com.holdmyspot.cat.common.optimizer.port.PluginBase;
import com.holdmyspot.cat.common.optimizer.port.PluginRemovals;
import com.holdmyspot.cat.common.optimizer.port.ProfileInventory;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.SourceSelection;
import com.holdmyspot.cat.common.optimizer.port.UnreachableReference;
import com.holdmyspot.cat.common.optimizer.port.WorkspacePort;
import com.holdmyspot.cat.common.runner.TrialPluginInput;
import com.holdmyspot.cat.common.optimizer.proposal.ContractAuthorProtocol;
import com.holdmyspot.cat.common.optimizer.proposal.DerivedProposal;
import com.holdmyspot.cat.common.optimizer.proposal.DraftDirectory;
import com.holdmyspot.cat.common.optimizer.proposal.HookReport;
import com.holdmyspot.cat.common.optimizer.proposal.JudgeTemplate;
import com.holdmyspot.cat.common.optimizer.proposal.PluginCheck;
import com.holdmyspot.cat.common.optimizer.proposal.PluginMapping;
import com.holdmyspot.cat.common.optimizer.proposal.ProfileEvidence;
import com.holdmyspot.cat.common.optimizer.proposal.Proposal;
import com.holdmyspot.cat.common.optimizer.proposal.RegressionScenarios;
import com.holdmyspot.cat.common.optimizer.proposal.TestRevisionProtocol;
import com.holdmyspot.cat.common.optimizer.report.CertificationStatus;
import com.holdmyspot.cat.common.optimizer.report.CertifiedEnvironment;
import com.holdmyspot.cat.common.optimizer.report.ChoiceItem;
import com.holdmyspot.cat.common.optimizer.report.ReportEvidence;
import com.holdmyspot.cat.common.optimizer.report.StatusReport;
import com.holdmyspot.cat.common.optimizer.store.CampaignStore;
import com.holdmyspot.cat.common.optimizer.store.CertificationHistoryJson;
import com.holdmyspot.cat.common.optimizer.store.FileCampaignStore;
import com.holdmyspot.cat.common.optimizer.store.FileCandidateOverlays;
import com.holdmyspot.cat.common.optimizer.workspace.ControlSource;
import com.holdmyspot.cat.common.optimizer.workspace.ForkTree;
import com.holdmyspot.cat.common.optimizer.workspace.ForkView;
import com.holdmyspot.cat.common.optimizer.workspace.LinkReport;
import com.holdmyspot.cat.common.optimizer.workspace.PluginBuild;
import com.holdmyspot.cat.common.optimizer.workspace.ReferencedFiles;
import com.holdmyspot.cat.common.optimizer.workspace.StagedSources;
import com.holdmyspot.cat.common.optimizer.workspace.WorkspaceException;
import com.holdmyspot.cat.common.optimizer.workspace.WorktreeRefusedException;
import com.holdmyspot.cat.common.runner.PluginManifest;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;

import com.holdmyspot.cat.common.optimizer.execution.BackgroundDriver;
import com.holdmyspot.cat.common.optimizer.execution.SliceSettings;
import com.holdmyspot.cat.common.optimizer.port.HostInterruptSignal;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.SplittableRandom;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Implements the commands on the real store, fork, driver and sessions of one project.
 * <p>
 * The campaigns of a command are those of the host session that runs it. Every command that runs, changes or delivers
 * a campaign first checks that the host session started the campaign in the harness that runs the command, and
 * refuses before it changes anything otherwise, naming the session that owns the campaign and the command that
 * resumes it. {@code start} refuses a target whose resumable campaign another session owns in the same way.
 * {@code status} is the exception: any session in either harness may report any campaign, and is told which session
 * owns a campaign that it did not start.
 * <p>
 * Every command first completes the removal of any campaign's files that an earlier command started. {@code run}
 * removes the files of a campaign whose certified delivery it completed, and {@code choose} those of a campaign whose
 * user retained the incumbent. A removal that fails adds a warning to the command's summary, or to its refusal, and
 * the command continues; the next command retries the removal.
 * <p>
 * A command that changes a campaign holds the campaign's {@link CampaignLock} from before its first change until its
 * last, so it never writes the campaign while another process runs it. While another process holds the lock, such a
 * command is refused before it changes anything, and {@code run} reports the campaign as busy instead.
 */
public final class OptimizerCore implements Core
{
	private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").
		withZone(ZoneOffset.UTC);
	private static final int RANDOM_ID_DIGITS = 6;
	/**
	 * The largest prompt test configuration that CAT reads.
	 */
	private static final int MAXIMUM_CONFIGURATION_BYTES = 1_048_576;
	/**
	 * The deepest level below {@code .cat/tests} at which a refusal looks for other prompt test configurations.
	 */
	private static final int MAXIMUM_CONFIGURATION_DEPTH = 8;
	/**
	 * The most prompt test configuration files that a refusal examines.
	 */
	private static final int MAXIMUM_CONFIGURATIONS_EXAMINED = 200;
	/**
	 * The most prompt test configurations that a refusal lists.
	 */
	private static final int MAXIMUM_CONFIGURATIONS_LISTED = 20;
	private final AbstractOptimizerCommandScope scope;
	private final ExecutableFiles executables;
	private final ProposalSchema schema;
	@Override
	public void validate(Command command) throws CommandRefusedException, IOException
	{
		try
		{
			scope.validateLauncher(command);
		}
		catch (IOException e)
		{
			throw new CommandRefusedException(e.getMessage(), e);
		}
	}

	private boolean background;
	private Instant backgroundStart;
	private final Map<String, BackgroundDriver> backgroundDrivers = new HashMap<>();
	private final Map<String, CampaignLock> backgroundLocks = new HashMap<>();
	private final Map<String, DeliveryResult> backgroundDeliveries = new HashMap<>();

	@Override
	public CommandResult background(Core.Operation operation)
		throws CommandRefusedException, IOException, InterruptedException
	{
		Optional<String> refusal = scope.backgroundRefusal();
		if (refusal.isPresent())
			throw new CommandRefusedException(refusal.get());
		background = true;
		backgroundStart = scope.clock().instant();
		try
		{
			CommandResult result = operation.run();
			if (result.status() != null && result.status().status().label().equals("running"))
				result = new CommandResult(SliceReports.awaiting(
					result.status(), new ReportStatus.Active(
						Status.AWAITING_USER),
					"The background driver paused. Use `status` for its reason or `cancel` to stop it."),
					result.certification(), result.harness(), result.proposalFile(), result.delivery(), result.message(),
					result.notices());
			for (String id : backgroundDrivers.keySet())
			{
				String text = new Commands(this, scope.json(), ZoneOffset.UTC, scope.trialSandbox(),
					Platform.current()).render(result);
				BackgroundDriver.write(lockFile(id).resolveSibling("background-result.json"), text, scope.fileReplacer());
				if (Cleanup.isRemovable(require(scope.store(), id, true), backgroundDeliveries.get(id)))
					BackgroundDriver.write(backgroundArchive(id), text, scope.fileReplacer());
			}
			closeBackgroundOwners();
			releaseBackgroundLocks();
			List<String> notices = new ArrayList<>();
			for (Map.Entry<String, DeliveryResult> delivery : backgroundDeliveries.entrySet())
				noticeOf(cleanup().end(require(scope.store(), delivery.getKey(), true), delivery.getValue()), notices);
			return result.withNotices(notices);
		}
		finally
		{
			try
			{
				closeBackgroundOwners();
			}
			finally
			{
				releaseBackgroundLocks();
			}
			backgroundDeliveries.clear();
			background = false;
			for (Map.Entry<String, BackgroundDriver> driver : backgroundDrivers.entrySet())
			{
				String id = driver.getKey();
				// State, the final response and delivery are stored, and all campaign locks are released before here.
				try
				{
					scope.backgroundEnded(id);
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					noticeFailed(id, e);
				}
				catch (IOException e)
				{
					noticeFailed(id, e);
				}
			}
			backgroundDrivers.clear();
		}
	}

	/**
	 * Removes driver identities before releasing their locks, so a new driver cannot race their removal.
	 *
	 * @throws IOException if an identity cannot be removed
	 */
	@SuppressWarnings("PMD.CloseResource")
	private void closeBackgroundOwners() throws IOException
	{
		for (BackgroundDriver driver : backgroundDrivers.values())
			driver.close();
	}

	/**
	 * Releases the locks retained through persistence of the final background response.
	 *
	 * @throws IOException if a lock cannot be released
	 */
	@SuppressWarnings("PMD.CloseResource")
	private void releaseBackgroundLocks() throws IOException
	{
		IOException failure = null;
		for (CampaignLock lock : backgroundLocks.values())
		{
			try
			{
				lock.close();
			}
			catch (IOException e)
			{
				if (failure == null)
					failure = e;
				else
					failure.addSuppressed(e);
			}
		}
		backgroundLocks.clear();
		if (failure != null)
			throw failure;
	}

	/**
	 * Retains the driver's lock until its final response is stored.
	 *
	 * @param id the campaign identifier
	 * @param lock the acquired lock
	 * @return the lock to use in the command body
	 * @throws IOException if the driver identity cannot be stored
	 */
	private CampaignLock retainBackgroundLock(String id, CampaignLock lock) throws IOException
	{
		try
		{
			recordBackground(id);
			if (!background)
				return lock;
			backgroundLocks.put(id, lock);
			return lock.lend();
		}
		catch (IOException failure)
		{
			lock.close();
			throw failure;
		}
	}

	/**
	 * Records notification trouble without replacing a measured outcome.
	 *
	 * @param id the campaign identifier
	 * @param failure the queue failure
	 */
	private void noticeFailed(String id, Exception failure)
	{
		try
		{
			BackgroundDriver.write(noticeFailureFile(id), "The background exit notice could not be queued: " +
				failure.getMessage(), scope.fileReplacer());
		}
		catch (IOException storageFailure)
		{
			failure.addSuppressed(storageFailure);
		}
	}

	/**
	 * Records a background driver after taking its lock. A command that is not a background driver discards the
	 * identity that an earlier driver left, so that it is not taken for this holder's.
	 *
	 * @param id the campaign identifier
	 * @throws IOException if its identity cannot be stored
	 */
	private void recordBackground(String id) throws IOException
	{
		Files.deleteIfExists(lockFile(id).resolveSibling("background-result.json"));
		if (!background)
			BackgroundDriver.discardStale(lockFile(id));
		else if (!backgroundDrivers.containsKey(id))
			backgroundDrivers.put(id, new BackgroundDriver(lockFile(id), backgroundStart, scope.json(),
				scope.fileReplacer()));
	}

	/**
	 * Returns the mode's admission settings.
	 *
	 * @return the settings
	 */
	private SliceSettings runSettings()
	{
		if (background)
			return scope.sliceSettings().withoutDeadline();
		return scope.sliceSettings();
	}

	/**
	 * Returns the host interrupt signal for a foreground run, or the cancellation mailbox for a background pilot.
	 *
	 * @return the signal
	 */
	private HostInterruptSignal runInterrupts()
	{
		if (!background)
			return scope.interrupts();
		return _ -> stopRequested();
	}

	/**
	 * Returns whether this command's driver was asked to stop.
	 *
	 * @return whether cancellation was requested
	 * @throws IOException if a cancellation mailbox cannot be read
	 */
	private boolean stopRequested() throws IOException
	{
		for (String id : backgroundDrivers.keySet())
			if (BackgroundDriver.stopRequested(lockFile(id), scope.json()))
				return true;
		return false;
	}

	/**
	 * Returns the stored failure of a host exit notice.
	 *
	 * @param id the campaign identifier
	 * @return the failure file
	 * @throws IOException if the campaign directory cannot be resolved
	 */
	private Path noticeFailureFile(String id) throws IOException
	{
		return backgroundArchive(id).resolveSibling(id + ".notice-failure.txt");
	}

	/**
	 * Returns the final response retained after successful delivery removes the campaign's working files.
	 *
	 * @param id the campaign identifier
	 * @return the response archive file
	 * @throws IOException if the session state cannot be located
	 */
	private Path backgroundArchive(String id) throws IOException
	{
		Path directory = campaignsOf(id).resolve(".background-results");
		return directory.resolve(id + ".json");
	}

	/**
	 * The directory that holds each campaign of another session that this command reads, by the campaign's
	 * identifier. Every other campaign is the host session's.
	 */
	private final Map<String, Path> otherSessionCampaigns = new HashMap<>();

	/**
	 * Creates the core.
	 *
	 * @param scope the command's scope, including its executable-file runner
	 * @throws NullPointerException if any of the arguments are null
	 */
	public OptimizerCore(AbstractOptimizerCommandScope scope)
	{
		requireThat(scope, "scope").isNotNull();
		this.scope = scope;
		this.executables = scope.executableFiles();
		this.schema = new ProposalSchema(scope.json());
	}

	/**
	 * Returns a contract author protocol for a campaign, with the target's current recorded scenarios, the
	 * harness's role profile, the campaign's trial plugins, the launcher that provides the contract tools and the
	 * harness's tool-output limits.
	 *
	 * @param root the original project root, where scenarios are recorded
	 * @param campaign the campaign, whose header holds the target and the user's goal, and whose trial plugins are
	 *   recorded
	 * @return the protocol
	 * @throws IOException if recorded scenarios or the installed role profile cannot be read, or Git fails
	 */
	private ContractAuthorProtocol contractAuthor(Path root, Campaign campaign) throws IOException
	{
		return contractAuthor(root, campaign, reports(campaign));
	}

	/**
	 * Returns a protocol that validates exit-status evidence against every selected evaluator.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param evaluators the staged evaluator reports
	 * @return the owner's contract-author protocol
	 * @throws IOException if an installed role profile or recorded scenario cannot be read
	 */
	private ContractAuthorProtocol contractAuthor(Path root, Campaign campaign,
		Map<Harness, EvaluatorMetadata> evaluators) throws IOException
	{
		Header header = campaign.header();
		boolean reportsExitStatus = scope.sessions().reportsExitStatus();
		if (!evaluators.isEmpty())
			reportsExitStatus = evaluators.values().stream().allMatch(evaluator -> evaluator.runtime().reportsExitStatus());
		JudgeTemplate judge = new JudgeTemplate(scope.json(), scope.profiles().roleProfile());
		RegressionScenarios scenarios = regressionScenarios(root);
		if (!evaluators.isEmpty())
		{
			return new ContractAuthorProtocol(scope.json(), schema, judge, scenarios, header.target(), header.goal(),
				reportsExitStatus, evaluatorPlugins(campaign), scope.launcher(), scope.profiles()::toolOutputBytes);
		}
		return new ContractAuthorProtocol(scope.json(), schema, judge, scenarios, header.target(), header.goal(),
			reportsExitStatus, campaign.plugins().orElseThrow(), scope.launcher(), scope.profiles()::toolOutputBytes);
	}

	/**
	 * Reads recorded scenarios and refuses broken workspace links before campaign changes or model launch.
	 *
	 * @param root the original project root
	 * @return the recorded scenarios
	 * @throws IOException if discovery or the symbolic-link probe fails, or a workspace link needs checkout repair
	 */
	private RegressionScenarios regressionScenarios(Path root) throws IOException
	{
		RegressionScenarios scenarios = RegressionScenarios.read(scope.json(), root, scope.home());
		scenarios.requireLinkCheckouts(scope.symbolicLinkProbe(), scope.forks().trackedLinks(root));
		return scenarios;
	}

	/**
	 * Reads what the trial profiles depend on: the harness's profile inventory, its capability ranking and the profile
	 * that each binding file of a staged source binds.
	 *
	 * @param staged the staged source of the prompt system
	 * @return the evidence
	 * @throws IOException if the inventory or a binding file cannot be read
	 */
	private ProfileEvidence profileEvidence(Path staged) throws IOException
	{
		ProfileInventory profiles = scope.profiles();
		ProfileCatalog inventory = profiles.inventory();
		Map<String, TrialProfile> bindings = new TreeMap<>();
		List<Path> files;
		try (Stream<Path> walk = Files.walk(staged))
		{
			files = walk.filter(Files::isRegularFile).map(staged::relativize).filter(profiles::canBind).toList();
		}
		for (Path file : files)
		{
			TrialProfile binding = profiles.binding(Files.readString(staged.resolve(file), StandardCharsets.UTF_8));
			if (binding.isBound())
				bindings.put(PortablePaths.text(file), binding);
		}
		return new ProfileEvidence(inventory, profiles.ranking(), bindings);
	}

	/**
	 * Describes the staged binding files whose model the harness does not support.
	 *
	 * @param profiles the profile that each staged binding file binds
	 * @return the notice that names each such file with its model and asks the user to change the binding and run
	 *   {@code start} again, or an empty string if the harness supports every bound model
	 */
	private String unsupportedBindings(ProfileEvidence profiles)
	{
		List<String> unsupported = new ArrayList<>();
		for (Map.Entry<String, TrialProfile> binding : new TreeMap<>(profiles.bindings()).entrySet())
		{
			String model = binding.getValue().model();
			if (!model.isEmpty() && !scope.profiles().supportsModel(model))
				unsupported.add(binding.getKey() + " binds the model " + model);
		}
		if (unsupported.isEmpty())
			return "";
		return "The contract author session did not start because the harness does not support a bound model: " +
			String.join("; ", unsupported) + ". Change the binding and run `start` again.";
	}

	@Override
	public CommandResult start(Path target, int ceiling, String goal, Path extraPermissions,
		Set<Harness> evaluationHarnesses) throws CommandRefusedException, IOException, InterruptedException
	{
		requireThat(target, "target").isNotNull();
		requireThat(ceiling, "ceiling").isBetween(1, true, Campaign.MAXIMUM_CEILING, true);
		if (goal != null)
			requireThat(goal, "goal").isNotBlank();
		Set<Harness> requested = Set.copyOf(evaluationHarnesses);
		Instant sliceStart = scope.clock().instant();
		refuseWithoutWorktrees();
		refuseUnlaunchable();
		return startBody(target, ceiling, goal, extraPermissions, requested, sliceStart);
	}

	/**
	 * Refuses a command that can end by adding or removing a campaign's optimization worktree before its first change,
	 * if nothing would carry out those operations because no request server serves the host session. A delivery then
	 * never writes the base and cannot remove the campaign afterwards.
	 *
	 * @throws WorktreeRefusedException with the refusal's message, which names the command that starts the request
	 *   server, if no request server serves the host session
	 * @throws IOException if the host session's CAT session directory cannot be opened
	 */
	private void refuseWithoutWorktrees() throws IOException
	{
		Optional<String> refusal = scope.worktreeRefusal();
		if (refusal.isPresent())
			throw new WorktreeRefusedException(refusal.get());
	}

	/**
	 * Refuses the command before it starts a session if every session would be refused because no request server
	 * serves the host session.
	 *
	 * @throws CommandRefusedException with the refusal's message, which names the reason and the user's remedy, if
	 *   every session would be refused
	 * @throws IOException if the host session's CAT session directory cannot be opened
	 */
	private void refuseUnlaunchable() throws CommandRefusedException, IOException
	{
		Optional<String> refusal = scope.launchRefusal();
		if (refusal.isPresent())
			throw new CommandRefusedException(refusal.get());
	}

	/**
	 * Starts or resumes the campaign of a target.
	 *
	 * @param target the target, relative to the working directory
	 * @param ceiling the maximum simultaneous nested sessions of a new campaign
	 * @param goal the user's goal for a new campaign, or {@code null} if the user stated none. A resumed campaign
	 *   keeps the goal it was started with, so a goal that differs from it is refused.
	 * @param extraPermissions the file of extra permissions for a new campaign, or {@code null} if the user named
	 *   none. A resumed campaign keeps the permissions it was started with, so a file whose permissions differ from
	 *   them is refused.
	 * @param requested the explicit evaluator set, or empty to use the default for a new or resumed campaign
	 * @param sliceStart when the command started, which bounds the guard pilot that the command runs
	 * @return the result
	 * @throws CommandRefusedException if the command is refused
	 * @throws IOException if the store, the workspace or a session fails
	 * @throws InterruptedException if the thread is interrupted while it waits for a session
	 */
	private CommandResult startBody(Path target, int ceiling, String goal, Path extraPermissions,
		Set<Harness> requested, Instant sliceStart) throws CommandRefusedException, IOException, InterruptedException
	{
		Path root = scope.projectRoot();
		String ownerSession = scope.sessionDirectory().sessionId();
		Path named = scope.directory().toRealPath().resolve(target).normalize();
		if (!Files.exists(named))
			throw new CommandRefusedException("The target does not exist: " + named);
		Path absolute = named.toRealPath();
		requireInsideProject(root, absolute);
		if (!Files.isRegularFile(absolute))
		{
			throw new CommandRefusedException("A campaign optimizes one prompt file, so the target must be a file, but " +
				absolute + " is not. Run start with the prompt file to optimize.");
		}
		CampaignStore store = scope.store();
		Registry.Opening opening = Registry.open(headers(), ownerSession, root, absolute, scope.harness());
		Set<Harness> evaluators = switch (opening)
		{
			case Registry.Opening.Refuse refuse -> throw refused(refuse.owner());
			case Registry.Opening.Resume resume ->
			{
				Header header = resume.header();
				Set<Harness> recorded = header.evaluationHarnesses();
				if (!requested.isEmpty() && !requested.equals(recorded))
				{
					throw new CommandRefusedException("Campaign " + header.id() + " evaluates with " +
						evaluatorNames(recorded) + ". The start command requests " + evaluatorNames(requested) +
						". To change evaluation harnesses, start a new campaign.");
				}
				yield recorded;
			}
			case Registry.Opening.Create _ ->
			{
				if (requested.isEmpty())
					yield Set.of(scope.harness());
				yield requested;
			}
		};
		return withCleanup(_ ->
		{
			Optional<String> exposure = scope.sessions().findHeldOutExposure(root);
			if (exposure.isPresent())
				throw new CommandRefusedException(exposure.get());
			regressionScenarios(root);
			if (opening instanceof Registry.Opening.Resume resume)
			{
				requireUnchangedInputs(resume.header(), goal, extraPermissions);
				return resume(root, store, resume.header(), sliceStart);
			}
			return create(root, store, absolute, ceiling, goal, extraPermissions, evaluators, sliceStart);
		});
	}

	/**
	 * Verifies that each selected evaluator can read the initial target before creating an optimizer campaign.
	 *
	 * @param root the project root
	 * @param target the project-relative target
	 * @param selected the requested evaluation harnesses
	 * @param cleanup the owner of any temporary source and frozen bases
	 * @return the preflight fork and frozen plugins, if a new shared campaign needs plugins
	 * @throws CommandRefusedException if the shared configuration or an evaluator cannot be used
	 * @throws IOException if the command cannot prepare the temporary source
	 * @throws InterruptedException if the command is interrupted waiting for an evaluator
	 */
	private Optional<ForkInputs> prepareStart(Path root, Path target, Set<Harness> selected,
		TemporaryDirectories cleanup)
		throws CommandRefusedException, IOException, InterruptedException
	{
		if (selected.equals(Set.of(scope.harness())))
			return Optional.empty();
		Map<Harness, List<PromptConfiguration.ConfiguredPlugin>> configured = new EnumMap<>(Harness.class);
		for (Harness evaluator : new TreeSet<>(selected))
		{
			try
			{
				configured.put(evaluator, configuredPlugins(root, target, evaluator));
			}
			catch (CommandRefusedException e)
			{
				throw evaluatorUnavailable(evaluator, e.getMessage(), e);
			}
		}
		Path scratch = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "evaluator-preflight-"));
		if (configured.values().stream().anyMatch(plugins -> !plugins.isEmpty()))
			return Optional.of(prepareSharedPlugins(root, target, scratch, configured));
		Set<Path> paths = new HashSet<>();
		paths.add(target);
		for (Path path : scope.forks().tracked(root))
		{
			if (!InventoryScanner.isOmitted(path))
				paths.add(path);
		}
		ForkTree tree = ForkTree.record(root, scratch, paths);
		Path staged = new StagedSources(tree, scratch.resolve("stage")).stage(Overlay.of(tree.inventory()), Set.of());
		verifyTrialRoutes(evaluatorReports(root, staged, target, selected, Map.of()));
		return Optional.empty();
	}

	/**
	 * A fork and the ordered plugin lists frozen in its workspace.
	 *
	 * @param fork the verified project files and base copies
	 * @param plugins the plugins prepared for each selected evaluator in configuration order
	 */
	private record ForkInputs(ForkView fork, Map<Harness, List<TrialPlugin>> plugins)
	{
	}

	/**
	 * Builds or copies the selected trial plugins in a provisional worktree and verifies their native loader inputs.
	 *
	 * @param root the project root
	 * @param target the project-relative target
	 * @param scratch the temporary directory to own this worktree and its pinned copies
	 * @param configured the ordered trial plugin configurations by evaluator
	 * @return the frozen inputs
	 * @throws CommandRefusedException if the fork, a plugin or an evaluator is unavailable
	 * @throws IOException if the source or plugin bases cannot be created or removed
	 * @throws InterruptedException if a build or a peer process is interrupted
	 */
	private ForkInputs prepareSharedPlugins(Path root, Path target, Path scratch,
		Map<Harness, List<PromptConfiguration.ConfiguredPlugin>> configured)
		throws CommandRefusedException, IOException, InterruptedException
	{
		ForkView fork;
		try
		{
			fork = scope.forks().prepare(root, scratch, Set.of(target));
		}
		catch (WorkspaceException e)
		{
			throw new CommandRefusedException(e.getMessage(), e);
		}
		try
		{
			Map<Harness, List<TrialPlugin>> prepared = new EnumMap<>(Harness.class);
			for (Map.Entry<Harness, List<PromptConfiguration.ConfiguredPlugin>> entry : configured.entrySet())
			{
				Harness evaluator = entry.getKey();
				try
				{
					SortedSet<Path> prompts = new TreeSet<>(Set.of(target));
					prepared.put(evaluator, prepareTrialPlugins(root, fork, target, prompts, entry.getValue(), evaluator));
				}
				catch (CommandRefusedException | IOException e)
				{
					throw evaluatorUnavailable(evaluator, e.getMessage(), e);
				}
			}
			verifyPreparedEvaluators(root, target, fork, prepared);
			return new ForkInputs(fork, Map.copyOf(prepared));
		}
		finally
		{
			scope.forks().remove(root, scratch);
		}
	}

	/**
	 * Checks that every selected evaluator loads the target with its own frozen plugin copies.
	 *
	 * @param root the project root
	 * @param target the project-relative target
	 * @param fork the project files and frozen bases
	 * @param plugins the trial plugins of each evaluator
	 * @throws CommandRefusedException if an evaluator cannot load the target or start trials
	 * @throws IOException if the source cannot be staged
	 * @throws InterruptedException if an evaluator probe is interrupted
	 */
	private void verifyPreparedEvaluators(Path root, Path target, ForkView fork,
		Map<Harness, List<TrialPlugin>> plugins) throws CommandRefusedException, IOException, InterruptedException
	{
		WorkspacePort workspace = fork.workspace();
		Path staged = workspace.stage(Overlay.of(fork.inventory()), Set.of());
		verifyTrialRoutes(evaluatorReports(root, staged, target, plugins.keySet(), evaluatorBases(plugins, workspace)));
	}

	/**
	 * Checks whether the serving owner can launch each peer that successfully probed its loader.
	 *
	 * @param observed the evaluator evidence
	 * @throws CommandRefusedException if a peer cannot launch through the owner
	 */
	private void verifyTrialRoutes(Map<Harness, EvaluatorMetadata> observed) throws CommandRefusedException
	{
		for (EvaluatorMetadata metadata : observed.values())
		{
			if (metadata.harness() != scope.harness())
				requirePeerPort(metadata);
		}
	}

	/**
	 * Observes loader and profile evidence for each selected evaluator, or names the evaluator that cannot run.
	 *
	 * @param root the project root
	 * @param staged the existing staged source
	 * @param target the project-relative target
	 * @param selected the requested evaluation harnesses
	 * @param plugins the pinned plugin bases the evaluator will install, with absent keys meaning no plugins
	 * @return the evaluator reports
	 * @throws CommandRefusedException if a report cannot be read or its evaluator does not load the target
	 * @throws InterruptedException if the command is interrupted waiting for an evaluator
	 */
	private Map<Harness, EvaluatorMetadata> evaluatorReports(Path root, Path staged, Path target, Set<Harness> selected,
		Map<Harness, List<PluginBase>> plugins)
		throws CommandRefusedException, InterruptedException
	{
		Map<Harness, EvaluatorMetadata> observed = new EnumMap<>(Harness.class);
		for (Harness evaluator : new TreeSet<>(selected))
		{
			EvaluatorMetadata metadata;
			try
			{
				List<PluginBase> bases = plugins.getOrDefault(evaluator, List.of());
				if (evaluator == scope.harness())
					metadata = EvaluatorInfo.inspect(scope, root, staged, target, bases);
				else
					metadata = scope.peerEvaluatorMetadata(evaluator, root, staged, target, bases);
			}
			catch (IOException e)
			{
				throw evaluatorUnavailable(evaluator, e.getMessage(), e);
			}
			if (metadata.refusal().isPresent())
				throw new CommandRefusedException(metadata.refusal().get(), null,
					CommandRefusedException.Reason.EVALUATOR_UNAVAILABLE);
			List<UnreachableReference> unreachable = metadata.observations().map(EvaluatorObservations::unreachable).
				orElse(List.of());
			if (!unreachable.isEmpty())
				throw evaluatorUnavailable(evaluator, unreachableNotice(unreachable), null);
			observed.put(evaluator, metadata);
		}
		return observed;
	}

	/**
	 * Returns the peer's installed trial route without launching a session.
	 *
	 * @param metadata the peer observation
	 * @return the trial port
	 * @throws CommandRefusedException if the owner cannot dispatch to this evaluator
	 */
	private SessionPort requirePeerPort(EvaluatorMetadata metadata) throws CommandRefusedException
	{
		Harness evaluator = metadata.harness();
		try
		{
			return scope.peerTrialSessions(evaluator, metadata);
		}
		catch (IOException e)
		{
			throw evaluatorUnavailable(evaluator, e.getMessage(), e);
		}
	}

	/**
	 * Reports why a particular evaluator cannot participate.
	 *
	 * @param evaluator the unavailable evaluator
	 * @param reason what prevented evaluation
	 * @param cause the underlying exception
	 * @return the typed refusal
	 */
	private static CommandRefusedException evaluatorUnavailable(Harness evaluator, String reason, Exception cause)
	{
		return new CommandRefusedException("Could not validate the " + evaluator.productName() + " evaluator. " +
			reason, cause, CommandRefusedException.Reason.EVALUATOR_UNAVAILABLE);
	}

	/**
	 * Lists the selected harnesses in command-name order.
	 *
	 * @param evaluators the selected harnesses
	 * @return their command names
	 */
	private static String evaluatorNames(Set<Harness> evaluators)
	{
		return evaluators.stream().map(Harness::commandName).sorted().collect(Collectors.joining(", "));
	}

	/**
	 * Refuses a resume that names a goal or a file of extra permissions that differs from what the campaign recorded.
	 *
	 * @param header the campaign's header
	 * @param goal the goal that the command names, or {@code null} if it names none
	 * @param extraPermissions the file of extra permissions that the command names, or {@code null} if it names none
	 * @throws CommandRefusedException if the goal differs from the campaign's, or the file is missing or invalid, or
	 *   holds permissions that differ from the campaign's; the message names the goals and the added and removed
	 *   writes and hosts, and says to start a new campaign
	 */
	private void requireUnchangedInputs(Header header, String goal, Path extraPermissions)
		throws CommandRefusedException
	{
		List<String> differences = new ArrayList<>();
		if (goal != null && !goal.equals(header.goal()))
		{
			String recorded = "no goal";
			if (header.goal() != null)
				recorded = "the goal \"" + header.goal() + "\"";
			differences.add("Campaign " + header.id() + " was started with " + recorded + ", but `start` names the " +
				"goal \"" + goal + "\".");
		}
		if (extraPermissions != null)
		{
			ExtraPermissions recorded = header.extraPermissions();
			ExtraPermissions named = readExtraPermissions(extraPermissions);
			Set<Path> addedWrites = new TreeSet<>(named.writes());
			addedWrites.removeAll(recorded.writes());
			Set<Path> removedWrites = new TreeSet<>(recorded.writes());
			removedWrites.removeAll(named.writes());
			Set<String> addedHosts = new TreeSet<>(named.hosts());
			addedHosts.removeAll(recorded.hosts());
			Set<String> removedHosts = new TreeSet<>(recorded.hosts());
			removedHosts.removeAll(named.hosts());
			if (!addedWrites.isEmpty() || !removedWrites.isEmpty() || !addedHosts.isEmpty() || !removedHosts.isEmpty())
			{
				StringBuilder difference = new StringBuilder("The file ").append(extraPermissions).
					append(" names extra permissions that differ from those of campaign ").append(header.id()).append('.');
				if (!addedWrites.isEmpty() || !addedHosts.isEmpty())
					difference.append(" It adds ").append(describeEntries(addedWrites, addedHosts)).append('.');
				if (!removedWrites.isEmpty() || !removedHosts.isEmpty())
					difference.append(" It removes ").append(describeEntries(removedWrites, removedHosts)).append('.');
				differences.add(difference.toString());
			}
		}
		if (differences.isEmpty())
			return;
		throw new CommandRefusedException(String.join("\n", differences) + "\nA campaign keeps the goal and the " +
			"extra permissions it started with. To use these instead, run `cancel " + header.id() + "`, then run " +
			"`start` again to start a new campaign with them. To continue this campaign, run `start` without them.");
	}

	/**
	 * Describes writes and hosts for a message.
	 *
	 * @param writes the writes
	 * @param hosts the hosts
	 * @return the writes and then the hosts, each kind introduced by its name and omitted when empty
	 */
	private static String describeEntries(Set<Path> writes, Set<String> hosts)
	{
		List<String> parts = new ArrayList<>();
		if (!writes.isEmpty())
			parts.add("the writes " + writes.stream().map(Path::toString).collect(Collectors.joining(", ")));
		if (!hosts.isEmpty())
			parts.add("the hosts " + String.join(", ", hosts));
		return String.join(" and ", parts);
	}

	/**
	 * Returns the header of every campaign of every session of the project.
	 *
	 * @return each header with the session that started its campaign
	 * @throws CommandRefusedException if the environment names no host session
	 * @throws IOException if the campaigns cannot be listed
	 */
	private List<Registry.OwnedHeader> headers() throws CommandRefusedException, IOException
	{
		SessionDirectory host = scope.sessionDirectory();
		List<Registry.OwnedHeader> headers = new ArrayList<>();
		for (Header header : scope.store().listHeaders())
			headers.add(new Registry.OwnedHeader(host.sessionId(), header));
		for (Map.Entry<String, Path> session : host.campaignsBySession().entrySet())
		{
			if (session.getKey().equals(host.sessionId()))
				continue;
			for (Header header : new FileCampaignStore(session.getValue(), scope.projectRoot(), scope.json(),
				scope.fileReplacer()).listHeaders())
			{
				headers.add(new Registry.OwnedHeader(session.getKey(), header));
			}
		}
		return headers;
	}

	/**
	 * Refuses a prompt system that refers to a file that trials cannot receive, such as an import of a missing file,
	 * because its trials would silently run without that file.
	 *
	 * @param root the project root
	 * @throws CommandRefusedException if the prompt system refers to such a file; the message names each reference
	 * @throws IOException if a prompt file cannot be read
	 */
	private void requireReachableReferences(Path root) throws CommandRefusedException, IOException
	{
		List<UnreachableReference> unreachable = scope.sessions().loadedSources(root, List.of()).unreachable();
		if (!unreachable.isEmpty())
			throw new CommandRefusedException(unreachableNotice(unreachable));
	}

	/**
	 * Describes the source references that would disappear from trials.
	 *
	 * @param unreachable the unsupported references
	 * @return the refusal text
	 */
	private static String unreachableNotice(List<UnreachableReference> unreachable)
	{
		StringJoiner message = new StringJoiner("\n", "Trials receive only the project's files, so they cannot " +
			"receive these files that the prompt system refers to:\n",
			"\nMove each file into the project, or remove the reference, then run `start` again.");
		for (UnreachableReference reference : unreachable)
			message.add("- " + reference.describe());
		return message.toString();
	}

	/**
	 * Refuses a target that lies outside the project or is the project itself.
	 *
	 * @param root the project root
	 * @param target the absolute target
	 * @throws CommandRefusedException if the target does not lie inside the project
	 */
	private static void requireInsideProject(Path root, Path target) throws CommandRefusedException
	{
		if (!target.startsWith(root) || target.equals(root))
			throw new CommandRefusedException("The target must be inside the project " + root + ": " + target);
	}

	/**
	 * Runs a command's body after completing the removals that earlier commands started, and adds a warning for each
	 * removal that fails to the body's result or refusal, after the notice about the project's {@code .gitignore}
	 * when opening the session's CAT session directory created it and changed that file or could not.
	 *
	 * @param <E> the exception that the body throws besides refusals and I/O failures
	 * @param body the command's body
	 * @return the body's result, with the notices that the removals and the body added
	 * @throws CommandRefusedException if the body refuses the command; its message ends with the warnings
	 * @throws IOException if the campaigns cannot be listed or the body fails
	 * @throws E if the body throws it
	 */
	private <E extends Exception> CommandResult withCleanup(Body<E> body) throws CommandRefusedException, IOException, E
	{
		List<String> notices = new ArrayList<>(cleanup().resume());
		CommandResult result;
		try
		{
			result = body.run(notices);
		}
		catch (RegressionScenarios.CheckoutRepairException e)
		{
			scope.takeIgnoreNotice().ifPresent(notice -> notices.add(0, notice));
			String message = e.getMessage();
			if (!notices.isEmpty())
				message += "\n" + String.join("\n", notices);
			throw new CommandRefusedException(message, e);
		}
		catch (CommandRefusedException e)
		{
			scope.takeIgnoreNotice().ifPresent(notice -> notices.add(0, notice));
			if (notices.isEmpty())
				throw e;
			throw new CommandRefusedException(e.getMessage() + "\n" + String.join("\n", notices), e, e.reason());
		}
		scope.takeIgnoreNotice().ifPresent(notice -> notices.add(0, notice));
		return result.withNotices(notices);
	}

	/**
	 * The body of a command, which runs after the removals that earlier commands started.
	 *
	 * @param <E> the exception that the body throws besides refusals and I/O failures
	 */
	@FunctionalInterface
	private interface Body<E extends Exception>
	{
		/**
		 * Runs the body.
		 *
		 * @param notices receives the lines that follow the result's summary
		 * @return the result
		 * @throws CommandRefusedException if the command is refused
		 * @throws IOException if the command fails
		 * @throws E if the command throws it
		 */
		CommandResult run(List<String> notices) throws CommandRefusedException, IOException, E;
	}

	/**
	 * Adds the notice that describes what ending a campaign did to its files.
	 *
	 * @param removal what the ending did
	 * @param notices receives the notice
	 */
	private static void noticeOf(Cleanup.Removal removal, List<String> notices)
	{
		switch (removal)
		{
			case Cleanup.Removal.Removed _ -> notices.add("Removed the campaign's files from " +
				SessionDirectory.ROOT + "/.");
			case Cleanup.Removal.Failed failed -> notices.add(failed.warning());
			case Cleanup.Removal.Kept _, Cleanup.Removal.Deferred _ ->
			{
				// The files stay, or another process holds the lock and a later command removes them silently
			}
		}
	}

	/**
	 * Returns the refusal for a campaign that another session or the other harness started.
	 *
	 * @param owner the campaign and the session that started it
	 * @return the exception to throw
	 * @throws CommandRefusedException if the environment names no host session
	 * @throws IOException if the host session's CAT session directory cannot be opened
	 */
	private CommandRefusedException refused(Registry.OwnedHeader owner) throws CommandRefusedException, IOException
	{
		Header header = owner.header();
		if (!owner.sessionId().equals(scope.sessionDirectory().sessionId()))
		{
			return new CommandRefusedException("Campaign " + header.id() + " for " + header.target() + " " +
				ownership(owner) + " Use `status " + header.id() + "` to read it from here.");
		}
		String harness = header.harness().commandName();
		return new CommandRefusedException("Campaign " + header.id() + " for " + header.target() +
			" was started by " + harness + " and can only be resumed there. Use `status " + header.id() +
			"` to read it from here.");
	}

	/**
	 * Describes which session owns a campaign and how to resume it.
	 *
	 * @param owner the campaign and the session that started it
	 * @return the sentences that name the session and the command that resumes it
	 */
	private static String ownership(Registry.OwnedHeader owner)
	{
		Harness harness = owner.header().harness();
		return "belongs to " + harness.commandName() + " session " + owner.sessionId() +
			", and only that session can run, change or deliver it. Resume that session with `" +
			harness.resumeCommand(owner.sessionId()) + "` to continue the campaign.";
	}

	/**
	 * Resumes a campaign that this harness started. A cancelled campaign stays cancelled: the result reports it, names
	 * it and tells the user to choose its resume, and nothing is presented or started.
	 *
	 * @param root the project root
	 * @param store the store
	 * @param header the campaign's header
	 * @param sliceStart when the command started, which bounds the guard pilot that the command runs
	 * @return the result
	 * @throws CommandRefusedException if the campaign cannot be shown, or its trial plugins are unrecorded and cannot
	 *   be built, copied or mapped
	 * @throws IOException if the store or the workspace fails
	 * @throws InterruptedException if the thread is interrupted while it waits for a session or a build
	 */
	private CommandResult resume(Path root, CampaignStore store, Header header, Instant sliceStart)
		throws CommandRefusedException, IOException, InterruptedException
	{
		Campaign campaign = require(store, header.id(), true);
		if (campaign.lifecycle() == Lifecycle.CANCELLED)
		{
			String id = header.id();
			return report(root, campaign, null, "The campaign " + id + " was cancelled, so start did not continue " +
				"it. To continue it, run `choose " + id + " " + new ChoiceItem(Choice.RESUME, 0, false, 0).id() + "`.");
		}
		if (campaign.pending() instanceof PendingDecision.PilotFailure)
			return report(root, campaign, null, pilotPausedNotice(campaign));
		if (campaign.isConfirmed() && !campaign.revisionRequested())
			return report(root, campaign, null, null);
		try (CampaignLock _ = lock(header.id()))
		{
			return resumeHeld(root, store, require(store, header.id(), true), sliceStart);
		}
	}

	/**
	 * Resumes a campaign whose write lock this command holds and that waits for a confirmed contract.
	 *
	 * @param root the project root
	 * @param store the store
	 * @param campaign the campaign
	 * @param sliceStart when the command started, which bounds the guard pilot that the command runs
	 * @return the result
	 * @throws CommandRefusedException if the campaign cannot be shown, or its trial plugins are unrecorded and cannot
	 *   be built, copied or mapped
	 * @throws IOException if the store or the workspace fails
	 * @throws InterruptedException if the thread is interrupted while it waits for a session or a build
	 */
	private CommandResult resumeHeld(Path root, CampaignStore store, Campaign campaign, Instant sliceStart)
		throws CommandRefusedException, IOException, InterruptedException
	{
		ForkView fork = openFork(root, campaign);
		verifyEvaluatorConditions(root, campaign, fork);
		// A build that failed left the trial plugins unrecorded, so start builds them again
		if (campaign.plugins().isEmpty() && campaign.evaluatorPlugins().isEmpty())
		{
			List<PromptConfiguration.ConfiguredPlugin> configured = List.of();
			if (campaign.header().evaluationHarnesses().equals(Set.of(scope.harness())))
				configured = configuredPlugins(root, relativeTarget(campaign));
			campaign = withTrialPlugins(root, store, campaign, fork, configured);
		}
		Optional<DerivedProposal> stored;
		try
		{
			stored = validatedProposal(root, campaign);
		}
		catch (InvalidProposalException e)
		{
			// A proposal that no longer validates against the project cannot be confirmed or revised, so only a new
			// derivation lets the campaign continue
			CommandResult derived = derive(root, store, campaign, fork, null, null, sliceStart);
			String message = "The stored proposal no longer validates against the project, so the contract author " +
				"derived a new one. The stored proposal's problems: " + String.join("; ", e.problems());
			if (derived.message() != null)
				message += "\n" + derived.message();
			return new CommandResult(derived.status(), derived.harness(), derived.proposalFile(), derived.delivery(),
				message);
		}
		if (stored.isPresent())
			return present(root, store, campaign, fork, stored.get(), sliceStart);
		return derive(root, store, campaign, fork, null, null, sliceStart);
	}

	/**
	 * Creates a campaign and derives its first proposal.
	 *
	 * @param root the project root
	 * @param store the store
	 * @param target the canonical target
	 * @param ceiling the maximum simultaneous nested sessions of the new campaign
	 * @param goal what the user wants the target to achieve, or {@code null} if the user stated no goal
	 * @param extraPermissions the file that lists the writes and hosts that the campaign's sessions need beyond their
	 *   own, or {@code null} if the user named none
	 * @param evaluators the validated evaluation harnesses
	 * @param sliceStart when the command started, which bounds the guard pilot that the command runs
	 * @return the result
	 * @throws CommandRefusedException if the file of extra permissions is missing or invalid, the prompt test
	 *   configuration is missing or invalid, the base worktree has uncommitted changes, or the trial plugins cannot be
	 *   built, copied or mapped
	 * @throws IOException if the store, the workspace or a session fails
	 * @throws InterruptedException if the thread is interrupted while it waits for a session or a build
	 */
	private CommandResult create(Path root, CampaignStore store, Path target, int ceiling, String goal,
		Path extraPermissions, Set<Harness> evaluators, Instant sliceStart)
		throws CommandRefusedException, IOException, InterruptedException
	{
		ExtraPermissions extra = readExtraPermissions(extraPermissions);
		String id = ID_TIME.format(scope.clock().instant()) + "-" +
			HexFormat.of().toHexDigits(scope.random().nextInt()).substring(Integer.BYTES * 2 - RANDOM_ID_DIGITS);
		Path directory = campaignDirectory(id);
		Path relative = root.relativize(target);
		// The configuration and the prompt system's references are checked before anything is created, so a refusal
		// leaves nothing behind
		List<PromptConfiguration.ConfiguredPlugin> configured = List.of();
		if (evaluators.equals(Set.of(scope.harness())))
		{
			configured = configuredPlugins(root, relative);
			requireReachableReferences(root);
		}
		ForkInputs inputs = prepareFork(root, directory, relative, evaluators);
		ForkView fork = inputs.fork();
		CommandResult result;
		// The fork creates the campaign's directory, which holds the lock file
		try (CampaignLock _ = lock(id))
		{
			FileInventory inventory = fork.inventory();
			Overlay incumbent = Overlay.of(inventory);
			new FileCandidateOverlays(directory, inventory, scope.json(), scope.fileReplacer()).save(incumbent);
			Header header = new Header(id, root, target, scope.harness(), Lifecycle.ACTIVE, goal, extra, evaluators);
			MeasurementIdentity identity = campaignEnvironment();
			if (evaluators.equals(Set.of(scope.harness())))
				identity = environment(inventory::entry);
			Campaign campaign = Campaign.start(header, ceiling, incumbent.identity(), identity);
			if (inputs.plugins().isEmpty())
			{
				store.replaceSnapshot(campaign);
				campaign = withTrialPlugins(root, store, campaign, fork, configured);
			}
			else
			{
				campaign = withPreparedPlugins(campaign, fork.workspace(), inputs.plugins());
				store.replaceSnapshot(campaign);
			}
			result = derive(root, store, campaign, fork, null, null, sliceStart);
		}
		if (fork.ignoreNotice().isEmpty())
			return result;
		String message = fork.ignoreNotice().get();
		if (result.message() != null)
			message += "\n" + result.message();
		return new CommandResult(result.status(), result.harness(), result.proposalFile(), result.delivery(), message);
	}

	/**
	 * Validates shared inputs in a disposable workspace, then creates the campaign fork and verifies its copies.
	 *
	 * @param root the project root
	 * @param directory the intended campaign directory
	 * @param target the project-relative target
	 * @param evaluators the selected scope
	 * @return the prepared fork and any evaluator-specific plugins
	 * @throws CommandRefusedException if the fork or a shared evaluator is unavailable
	 * @throws IOException if a worktree or a plugin base cannot be created or read
	 * @throws InterruptedException if a plugin build or a peer probe is interrupted
	 */
	private ForkInputs prepareFork(Path root, Path directory, Path target, Set<Harness> evaluators)
		throws CommandRefusedException, IOException, InterruptedException
	{
		try (TemporaryDirectories temporary = new TemporaryDirectories())
		{
			Optional<ForkInputs> prepared = prepareStart(root, target, evaluators, temporary);
			scope.createdSessionDirectory();
			ForkView fork;
			try
			{
				fork = scope.forks().create(root, directory, Set.of(target));
			}
			catch (WorkspaceException e)
			{
				throw new CommandRefusedException(e.getMessage(), e);
			}
			if (evaluators.equals(Set.of(scope.harness())))
				return new ForkInputs(fork, Map.of());
			try
			{
				return new ForkInputs(fork, installPreparedPlugins(root, target, prepared, fork, evaluators));
			}
			catch (CommandRefusedException | IOException | InterruptedException e)
			{
				try
				{
					scope.forks().remove(root, directory);
					TemporaryDirectories.deleteRecursively(directory);
				}
				catch (IOException cleanup)
				{
					e.addSuppressed(cleanup);
				}
				throw e;
			}
		}
	}

	/**
	 * Transfers the validated frozen bases into the final campaign and checks them against its staged project.
	 *
	 * @param root the project root
	 * @param target the project-relative target
	 * @param prepared the provisional fork and its pinned bases, empty for plugin-free evaluation
	 * @param fork the final fork
	 * @param evaluators the selected scope
	 * @return the ordered, frozen plugins for each selected evaluator
	 * @throws CommandRefusedException if the project changed or the final sources do not load the target
	 * @throws IOException if the bases or staged sources cannot be read or written
	 * @throws InterruptedException if an evaluator probe is interrupted
	 */
	private Map<Harness, List<TrialPlugin>> installPreparedPlugins(Path root, Path target,
		Optional<ForkInputs> prepared, ForkView fork, Set<Harness> evaluators)
		throws CommandRefusedException, IOException, InterruptedException
	{
		if (prepared.isEmpty())
		{
			Map<Harness, List<TrialPlugin>> empty = new EnumMap<>(Harness.class);
			for (Harness evaluator : evaluators)
				empty.put(evaluator, List.of());
			verifyPreparedEvaluators(root, target, fork, empty);
			return empty;
		}
		ForkInputs inputs = prepared.get();
		verifyPublicationInventory(inputs.fork(), fork);
		WorkspacePort workspace = fork.workspace();
		for (List<TrialPlugin> plugins : inputs.plugins().values())
		{
			for (TrialPlugin plugin : plugins)
			{
				String digest = plugin.baseDigest();
				String copied = workspace.copyPluginBase(inputs.fork().workspace().pluginBase(digest));
				if (!copied.equals(digest))
					throw new IOException("The prepared trial plugin " + plugin.name() + " changed while it was copied");
			}
		}
		verifyPreparedEvaluators(root, target, fork, inputs.plugins());
		return inputs.plugins();
	}

	/**
	 * Requires the production fork to match the provisional source that built the plugins, allowing exactly the
	 * generated session ignore entry that creation may append.
	 *
	 * @param prepared the verified source
	 * @param created the production fork
	 * @throws IOException if the original ignore file cannot be read
	 * @throws CommandRefusedException if the fork now contains different user files
	 */
	private static void verifyPublicationInventory(ForkView prepared, ForkView created)
		throws IOException, CommandRefusedException
	{
		FileInventory inventory = prepared.inventory();
		if (inventory.equals(created.inventory()))
			return;
		Map<Path, FileEntry> expected = new HashMap<>(inventory.entries());
		Path ignore = Path.of(SessionIgnore.FILE_NAME);
		if (expected.get(ignore) instanceof FileEntry.RegularFile original)
		{
			byte[] content = prepared.workspace().readFile(Overlay.of(inventory), ignore).orElseThrow().bytes();
			String suffix = SessionIgnore.BLOCK;
			if (content.length > 0 && content[content.length - 1] != '\n')
				suffix = "\n" + suffix;
			byte[] addition = suffix.getBytes(StandardCharsets.UTF_8);
			byte[] combined = Arrays.copyOf(content, content.length + addition.length);
			System.arraycopy(addition, 0, combined, content.length, addition.length);
			expected.put(ignore, new FileEntry.RegularFile(Sha256.of(combined), original.permissions()));
		}
		if (!expected.equals(created.inventory().entries()))
			throw new CommandRefusedException("The project changed while CAT prepared the trial plugins. Run start again.");
	}

	/**
	 * Reads the file of extra permissions that a new campaign's user named.
	 *
	 * @param file the file, relative to the working directory, or {@code null} if the user named none
	 * @return the permissions, or {@link ExtraPermissions#NONE} if the user named no file
	 * @throws CommandRefusedException if the file does not exist, cannot be read or is not valid; the message names the
	 *   file and the problem
	 */
	private ExtraPermissions readExtraPermissions(Path file) throws CommandRefusedException
	{
		if (file == null)
			return ExtraPermissions.NONE;
		try
		{
			return ExtraPermissions.read(scope.json(), scope.directory().resolve(file));
		}
		catch (IOException e)
		{
			throw new CommandRefusedException(e.getMessage() + ". Fix the file named by " + ExtraPermissions.OPTION +
				", or leave the option out, then run `start` again.", e);
		}
	}

	/**
	 * Returns the session port that starts a campaign's sessions with the permissions that its user asked for.
	 *
	 * @param campaign the campaign
	 * @return the port
	 * @throws IOException if the port cannot be created
	 */
	private SessionPort sessions(Campaign campaign) throws IOException
	{
		return new ExtraPermissionSessions(scope.sessions(), campaign.header().extraPermissions());
	}

	/**
	 * Returns the directory that holds a campaign's files.
	 *
	 * @param id the campaign's identifier
	 * @return the directory, in the CAT session directory of the session that started the campaign
	 * @throws IOException if the host session's CAT session directory cannot be opened
	 */
	private Path campaignDirectory(String id) throws IOException
	{
		return FileCampaignStore.directory(campaignsOf(id), id);
	}

	/**
	 * Returns the directory that holds the campaigns of the session that started a campaign.
	 *
	 * @param id the campaign's identifier
	 * @return the directory
	 * @throws IOException if the host session's CAT session directory cannot be opened
	 */
	private Path campaignsOf(String id) throws IOException
	{
		Path other = otherSessionCampaigns.get(id);
		if (other != null)
			return other;
		try
		{
			return scope.sessionDirectory().campaigns();
		}
		catch (CommandRefusedException e)
		{
			throw new IOException(e.getMessage(), e);
		}
	}

	/**
	 * Returns the directory that holds a campaign's held-out content.
	 *
	 * @param id the campaign's identifier
	 * @return the directory, in the CAT session directory of the session that started the campaign
	 * @throws IOException if the host session's CAT session directory cannot be opened
	 */
	private Path heldOutDirectory(String id) throws IOException
	{
		return FileCampaignStore.heldOutDirectory(campaignsOf(id), id);
	}

	/**
	 * Returns the file whose lock gives one process at a time the right to write a campaign.
	 *
	 * @param id the campaign's identifier
	 * @return the file, in the CAT session directory of the session that started the campaign
	 * @throws IOException if the host session's CAT session directory cannot be opened
	 */
	private Path lockFile(String id) throws IOException
	{
		return FileCampaignStore.lockFile(campaignsOf(id), id);
	}

	/**
	 * Takes a campaign's write lock for a command that changes the campaign.
	 *
	 * @param id the campaign's identifier
	 * @return the lock, which the caller releases
	 * @throws CommandRefusedException if another process holds the lock
	 * @throws IOException if the lock file cannot be created or locking fails
	 */
	private CampaignLock lock(String id) throws CommandRefusedException, IOException
	{
		Optional<CampaignLock> lock = CampaignLock.tryAcquire(scope.fileLocks(), lockFile(id));
		if (lock.isEmpty())
		{
			if (BackgroundDriver.owner(lockFile(id), scope.fileLocks(), scope.json()).isPresent())
				throw new CommandRefusedException("A background driver holds campaign " + id +
					". Use `status` to see progress or `cancel` to stop it.");
			throw new CommandRefusedException("Campaign " + id + " is running in another process. Wait for its " +
				"slice to end, then try again, or use `status` and `cancel`.");
		}
		try
		{
			return retainBackgroundLock(id, lock.get());
		}
		catch (IOException e)
		{
			lock.get().close();
			throw e;
		}
	}

	/**
	 * Reads the trial plugins that a target's prompt test configuration lists for the harness.
	 *
	 * @param root the project root
	 * @param target the target, relative to the project
	 * @return the plugins, in configuration order
	 * @throws CommandRefusedException if the configuration does not exist, is invalid, has no key for the harness, or
	 *   lists without a build a plugin whose name a manifest that Git tracks in the project holds
	 * @throws IOException if the configuration, a tracked manifest or Git cannot be read
	 */
	private List<PromptConfiguration.ConfiguredPlugin> configuredPlugins(Path root, Path target)
		throws CommandRefusedException, IOException
	{
		return configuredPlugins(root, target, scope.harness());
	}

	/**
	 * Reads the trial plugins that the target configures for one evaluator.
	 *
	 * @param root the project root
	 * @param target the target, relative to the project
	 * @param evaluator the harness whose configuration is required
	 * @return the plugins, in configuration order
	 * @throws CommandRefusedException if the configuration for this evaluator is invalid or missing
	 * @throws IOException if the configuration or tracked files cannot be read
	 */
	private List<PromptConfiguration.ConfiguredPlugin> configuredPlugins(Path root, Path target, Harness evaluator)
		throws CommandRefusedException, IOException
	{
		Path file = PromptConfiguration.location(target);
		Path absolute = root.resolve(file);
		String harness = evaluator.commandName();
		String retry = "Run `start` again once the configuration names the plugins that trials install.";
		if (!Files.isRegularFile(absolute))
		{
			throw new CommandRefusedException("The prompt test configuration " + PortablePaths.text(file) + " does " +
				"not exist. It lists the plugins that trials install, such as {\"" + harness + "\": {\"plugins\": " +
				"[]}} for trials that install none.\n" + pluginChoices(root) + retry);
		}
		PromptConfiguration configuration;
		try
		{
			configuration = PromptConfiguration.parse(scope.json(), BoundedInput.readUtf8(absolute,
				MAXIMUM_CONFIGURATION_BYTES, "Prompt test configuration"));
		}
		catch (PromptConfigurationException e)
		{
			throw new CommandRefusedException("The prompt test configuration " + PortablePaths.text(file) + " is " +
				"invalid: " + String.join("; ", e.problems()) + ". Correct it and run `start` again.", e);
		}
		Optional<List<PromptConfiguration.ConfiguredPlugin>> plugins = configuration.plugins(harness);
		if (plugins.isEmpty())
		{
			throw new CommandRefusedException("The prompt test configuration " + PortablePaths.text(file) + " has " +
				"no \"" + harness + "\" key, so it does not say which plugins trials in " + harness + " install. Add " +
				"the key, with an empty plugin list if trials install none.\n" + configurationsWithKey(root, file, harness) +
				pluginChoices(root) + retry);
		}
		requireBuiltWhenTracked(root, file, plugins.get(), evaluator);
		return plugins.get();
	}

	/**
	 * Lists the other prompt test configurations in the project that have a key for the harness, with the prompt files
	 * that they configure, so that a refusal can point to the targets that the harness can run. The search does not
	 * follow symbolic links, descends at most {@value #MAXIMUM_CONFIGURATION_DEPTH} levels below {@code .cat/tests},
	 * examines at most {@value #MAXIMUM_CONFIGURATIONS_EXAMINED} files named {@value PromptConfiguration#FILE_NAME} and
	 * lists at most {@value #MAXIMUM_CONFIGURATIONS_LISTED} configurations. A configuration that cannot be read, exceeds
	 * {@code MAXIMUM_CONFIGURATION_BYTES} or does not parse is skipped.
	 *
	 * @param root the project root
	 * @param excluded the configuration that the refusal is about, relative to the project
	 * @param harness the evaluator's command name
	 * @return the heading and one line per configuration, in order of configuration path, each ending with a line
	 *   break, or an empty string if no other configuration has the key
	 */
	private String configurationsWithKey(Path root, Path excluded, String harness)
	{
		Path tests = root.resolve(".cat").resolve("tests");
		List<Path> files;
		try (Stream<Path> walk = Files.walk(tests, MAXIMUM_CONFIGURATION_DEPTH))
		{
			files = walk.filter(path -> path.getFileName() != null &&
					path.getFileName().toString().equals(PromptConfiguration.FILE_NAME) &&
					Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).
				map(root::relativize).
				filter(path -> !path.equals(excluded)).
				limit(MAXIMUM_CONFIGURATIONS_EXAMINED).
				sorted(Comparator.comparing(PortablePaths::text)).
				toList();
		}
		catch (IOException | UncheckedIOException _)
		{
			return "";
		}
		StringBuilder entries = new StringBuilder(256);
		int listed = 0;
		for (Path file : files)
		{
			if (listed == MAXIMUM_CONFIGURATIONS_LISTED)
				break;
			try
			{
				PromptConfiguration configuration = PromptConfiguration.parse(scope.json(),
					BoundedInput.readUtf8(root.resolve(file), MAXIMUM_CONFIGURATION_BYTES,
						"Prompt test configuration"));
				if (configuration.plugins(harness).isEmpty())
					continue;
			}
			catch (IOException | PromptConfigurationException | UncheckedIOException _)
			{
				continue;
			}
			Path directory = file.getParent();
			Path relative = Path.of(".cat", "tests").relativize(directory);
			StringBuilder entry = new StringBuilder(128);
			for (Path candidate : List.of(Path.of(relative + ".md"), Path.of(".cat", "rules", relative + ".md")))
			{
				if (Files.isRegularFile(root.resolve(candidate), LinkOption.NOFOLLOW_LINKS) &&
					PromptTestLocations.scenarioDirectory(candidate).equals(directory))
				{
					entry.append(PortablePaths.text(candidate)).append(", configured by ");
					break;
				}
			}
			entry.append(PortablePaths.text(file));
			entries.append("- ").append(entry).append('\n');
			++listed;
		}
		if (listed == 0)
			return "";
		return "These prompt test configurations have a \"" + harness + "\" key, so their prompt files can be " +
			"targets in " + harness + ":\n" + entries;
	}

	/**
	 * Lists the plugins that a prompt test configuration can name for the harness: every plugin that the harness's
	 * install records name in the user's harness home, by the name that the configuration uses for it, and every
	 * plugin manifest of the harness that Git tracks in the project, which the configuration lists with a build.
	 *
	 * @param root the project root
	 * @return the lines of both lists, each list headed by a line that says what it holds, ending with a line break
	 */
	private String pluginChoices(Path root)
	{
		String harness = scope.harness().commandName();
		Path manifest = scope.harness().pluginManifest();
		StringBuilder choices = new StringBuilder(1024);
		choices.append("Plugins installed in ").append(harness).append(", by the name that the configuration uses:\n");
		try
		{
			List<String> names = scope.sessions().installedPluginNames();
			if (names.isEmpty())
				choices.append("- none\n");
			for (String name : names)
			{
				choices.append("- ").append(name).append(", ").
					append(installedVersions(scope.sessions().installedPlugins(name), manifest)).append('\n');
			}
		}
		catch (IOException e)
		{
			choices.append("- they cannot be listed: ").append(e.getMessage()).append('\n');
		}

		choices.append("Plugins that the project builds, whose manifest ").append(PortablePaths.text(manifest)).
			append(" Git tracks; the configuration lists such a plugin by its name, with a build:\n");
		try
		{
			List<Path> tracked = trackedManifests(root);
			if (tracked.isEmpty())
				choices.append("- none\n");
			for (Path path : tracked)
			{
				Path directory = pluginDirectory(root, path, manifest);
				String name;
				try
				{
					name = PluginManifest.name(directory, manifest);
				}
				catch (IOException _)
				{
					// The configuration cannot name a plugin whose manifest holds no valid name
					name = "a plugin whose manifest holds no valid name";
				}
				choices.append("- ").append(name).append(", ").append(manifestVersion(directory, manifest)).
					append(", from ").append(PortablePaths.text(path)).append('\n');
			}
		}
		catch (IOException e)
		{
			choices.append("- they cannot be listed: ").append(e.getMessage()).append('\n');
		}
		return choices.toString();
	}

	/**
	 * Describes the versions of a plugin's installed copies.
	 *
	 * @param copies   the installed copies
	 * @param manifest the harness's plugin manifest path, relative to a copy
	 * @return the version of each copy, such as {@code version 1.0}, joined by commas
	 */
	private static String installedVersions(List<Path> copies, Path manifest)
	{
		if (copies.isEmpty())
			return "but no installed copy holds its manifest";
		StringJoiner versions = new StringJoiner(", ");
		for (Path copy : copies)
			versions.add(manifestVersion(copy, manifest));
		return versions.toString();
	}

	/**
	 * Describes the version that a plugin's manifest states.
	 *
	 * @param directory the plugin directory
	 * @param manifest  the harness's plugin manifest path, relative to {@code directory}
	 * @return {@code version <version>}, or a phrase that says why no version can be shown
	 */
	private static String manifestVersion(Path directory, Path manifest)
	{
		try
		{
			return PluginManifest.version(directory, manifest).map(version -> "version " + version).
				orElse("whose manifest states no version");
		}
		catch (IOException _)
		{
			// The listing only informs the user's choice, so an unreadable manifest is described instead of refused
			return "whose manifest cannot be read";
		}
	}

	/**
	 * Returns the harness's plugin manifests that Git tracks in the project.
	 *
	 * @param root the project root
	 * @return each manifest's path, relative to the project, in path order
	 * @throws IOException if Git cannot list the tracked files
	 */
	private List<Path> trackedManifests(Path root) throws IOException
	{
		return trackedManifests(root, scope.harness());
	}

	/**
	 * Returns the selected evaluator's plugin manifests that Git tracks.
	 *
	 * @param root the project root
	 * @param evaluator the harness whose manifests to look for
	 * @return the project-relative manifests in path order
	 * @throws IOException if Git cannot list the tracked files
	 */
	private List<Path> trackedManifests(Path root, Harness evaluator) throws IOException
	{
		Path manifest = evaluator.pluginManifest();
		List<Path> manifests = new ArrayList<>();
		for (Path tracked : new TreeSet<>(scope.forks().tracked(root)))
		{
			if (tracked.endsWith(manifest))
				manifests.add(tracked);
		}
		return manifests;
	}

	/**
	 * Returns the plugin directory that holds a tracked manifest.
	 *
	 * @param root     the project root
	 * @param tracked  the manifest, relative to the project
	 * @param manifest the harness's plugin manifest path, relative to a plugin directory
	 * @return the absolute plugin directory
	 */
	private static Path pluginDirectory(Path root, Path tracked, Path manifest)
	{
		if (tracked.getNameCount() > manifest.getNameCount())
			return root.resolve(tracked.subpath(0, tracked.getNameCount() - manifest.getNameCount()));
		return root;
	}

	/**
	 * Refuses a configured plugin without a build whose name the manifest of a plugin that the project tracks holds,
	 * because trials would install the user's installed copy instead of the project's plugin.
	 *
	 * @param root the project root
	 * @param file the configuration, relative to the project
	 * @param plugins the configured plugins
	 * @param evaluator the harness that installs the plugins
	 * @throws CommandRefusedException if such a plugin exists
	 * @throws IOException if Git cannot list the tracked files
	 */
	private void requireBuiltWhenTracked(Path root, Path file, List<PromptConfiguration.ConfiguredPlugin> plugins,
		Harness evaluator)
		throws CommandRefusedException, IOException
	{
		Map<String, String> unbuilt = new HashMap<>();
		for (PromptConfiguration.ConfiguredPlugin plugin : plugins)
		{
			if (plugin.build().isEmpty())
				unbuilt.put(plugin.name().substring(0, plugin.name().indexOf('@')), plugin.name());
		}
		if (unbuilt.isEmpty())
			return;
		Path manifest = evaluator.pluginManifest();
		for (Path tracked : trackedManifests(root, evaluator))
		{
			Path directory = pluginDirectory(root, tracked, manifest);
			String name;
			try
			{
				name = PluginManifest.name(directory, manifest);
			}
			catch (IOException _)
			{
				// A manifest without a valid name cannot be installed under the configured plugin's name
				continue;
			}
			String configured = unbuilt.get(name);
			if (configured != null)
			{
				throw new CommandRefusedException("The prompt test configuration " + PortablePaths.text(file) +
					" lists the plugin " + configured + " without a build, but the project tracks the manifest " +
					PortablePaths.text(tracked) + " of a plugin with that name, so trials would install the user's " +
					"installed copy instead of the project's plugin. Give the plugin a build that builds it from the " +
					"project, and run `start` again.");
			}
		}
	}

	/**
	 * Builds the trial plugins that the project builds in the campaign's optimization worktree, copies the base of
	 * each trial plugin once, maps the prompt files onto each project-built plugin and records the plugins, with the
	 * version that each base copy's manifest declares, in the campaign. The worktree returns to the fork's files
	 * afterwards, so the build output never takes part in trials. A failure leaves the plugins unrecorded, so that
	 * {@code start} builds them again.
	 * <p>
	 * The caller holds the campaign's write lock, so no other process writes the campaign meanwhile.
	 *
	 * @param root the project root
	 * @param store the store
	 * @param campaign the campaign, which has no recorded trial plugins
	 * @param fork the campaign's fork
	 * @param configured the plugins that the prompt test configuration lists for the harness
	 * @return the campaign with its trial plugins recorded
	 * @throws CommandRefusedException if a build fails or produces no plugin of its name, an installed plugin does not
	 *   have exactly one installed copy, or the target would not load in trials
	 * @throws IOException if a plugin cannot be copied, a base copy's manifest cannot be read, the worktree cannot be
	 *   restored or the campaign cannot be stored
	 * @throws InterruptedException if the thread is interrupted while it waits for a build
	 */
	private Campaign withTrialPlugins(Path root, CampaignStore store, Campaign campaign, ForkView fork,
		List<PromptConfiguration.ConfiguredPlugin> configured)
		throws CommandRefusedException, IOException, InterruptedException
	{
		if (!campaign.header().evaluationHarnesses().equals(Set.of(scope.harness())))
		{
			if (!configured.isEmpty())
				throw new IllegalArgumentException("Shared evaluation requires its evaluator-specific configurations");
			Map<Harness, List<TrialPlugin>> empty = new EnumMap<>(Harness.class);
			campaign.header().evaluationHarnesses().forEach(evaluator -> empty.put(evaluator, List.of()));
			Campaign withoutPlugins = withPreparedPlugins(campaign, fork.workspace(), empty);
			store.replaceSnapshot(withoutPlugins);
			return withoutPlugins;
		}
		SortedSet<Path> promptFiles = authority(root, campaign, fork.inventory()).promptFiles(fork.inventory());
		List<TrialPlugin> mapped = prepareTrialPlugins(root, fork, relativeTarget(campaign), promptFiles, configured,
			scope.harness());
		Map<String, String> versions = pluginVersions(fork.workspace(), scope.harness(), mapped);
		Campaign built = campaign.withPlugins(mapped, versions);
		store.replaceSnapshot(built);
		return built;
	}

	/**
	 * Records frozen plugin lists, using the single list when the campaign evaluates in just one harness.
	 *
	 * @param campaign the campaign's recorded evaluation scope
	 * @param workspace the workspace holding the frozen plugin bases
	 * @param plugins the prepared plugin list for every selected evaluator
	 * @return the campaign with the prepared plugin lists recorded
	 * @throws IOException if a base's manifest cannot be read
	 */
	private static Campaign withPreparedPlugins(Campaign campaign, WorkspacePort workspace,
		Map<Harness, List<TrialPlugin>> plugins) throws IOException
	{
		if (plugins.size() == 1)
		{
			Map.Entry<Harness, List<TrialPlugin>> evaluator = plugins.entrySet().iterator().next();
			return campaign.withPlugins(evaluator.getValue(),
				pluginVersions(workspace, evaluator.getKey(), evaluator.getValue()));
		}
		return campaign.withEvaluatorPlugins(plugins);
	}

	/**
	 * Builds or copies and maps one evaluator's plugins, restoring the worktree after the build.
	 *
	 * @param root the project root
	 * @param fork the source and temporary build worktree
	 * @param target the project-relative target
	 * @param promptFiles the project prompt files that may require mapping
	 * @param configured the ordered plugins of the evaluator
	 * @param evaluator the harness that installs the plugins
	 * @return the mapped plugins backed by frozen copies
	 * @throws CommandRefusedException if a plugin cannot be built, installed or mapped
	 * @throws IOException if the source or a plugin base cannot be read or written
	 * @throws InterruptedException if a build or evaluator probe is interrupted
	 */
	private List<TrialPlugin> prepareTrialPlugins(Path root, ForkView fork, Path target, SortedSet<Path> promptFiles,
		List<PromptConfiguration.ConfiguredPlugin> configured, Harness evaluator)
		throws CommandRefusedException, IOException, InterruptedException
	{
		WorkspacePort workspace = fork.workspace();
		FileInventory inventory = fork.inventory();
		// A build that runs Git in the worktree needs the worktree's links to name the project where it is now
		workspace.reconnectWorktree(Overlay.of(fork.inventory()));
		List<TrialPlugin> copied = new ArrayList<>();
		try
		{
			for (PromptConfiguration.ConfiguredPlugin plugin : configured)
			{
				if (plugin.build().isPresent())
					copied.add(builtPlugin(workspace, plugin.name(), plugin.build().get(), evaluator));
				else
					copied.add(installedPlugin(root, workspace, plugin.name(), evaluator));
			}
		}
		finally
		{
			// The build output is not one of the fork's files, so it must not reach the trials' staged sources
			workspace.materialize(Overlay.of(inventory));
		}
		List<PluginBase> bases = bases(copied, workspace);
		Set<Path> fromProject;
		if (evaluator == scope.harness())
			fromProject = loadsFromProject(root, bases, promptFiles);
		else
		{
			Path staged = workspace.stage(Overlay.of(inventory), Set.of());
			EvaluatorMetadata report = scope.peerEvaluatorMetadata(evaluator, root, staged, target, unmappedBases(bases));
			fromProject = projectSources(evaluator, report.loadedSources(), promptFiles);
		}
		List<TrialPlugin> mapped = new ArrayList<>();
		Map<String, FileInventory> baseFiles = new HashMap<>();
		for (TrialPlugin plugin : copied)
		{
			if (!plugin.isProjectBuilt())
			{
				mapped.add(plugin);
				continue;
			}
			FileInventory base = InventoryScanner.scan(workspace.pluginBase(plugin.baseDigest()), Set.of());
			baseFiles.put(plugin.name(), base);
			mapped.add(new TrialPlugin(plugin.name(), plugin.baseDigest(), plugin.source(),
				PluginMapping.map(inventory, promptFiles, fromProject, base), plugin.repository()));
		}
		PluginMapping.Coverage coverage = PluginMapping.check(inventory, promptFiles, fromProject, mapped, baseFiles);
		Optional<String> refusal = coverage.targetRefusal(target);
		if (refusal.isPresent())
			throw new CommandRefusedException(refusal.get());
		return List.copyOf(mapped);
	}

	/**
	 * Reads the versions recorded by the evaluator's exact plugin bases.
	 *
	 * @param workspace the workspace that holds the frozen bases
	 * @param evaluator the harness that installs the listed plugins
	 * @param plugins the evaluator's ordered frozen plugins
	 * @return the versions by plugin name
	 * @throws IOException if a base's manifest cannot be read
	 */
	private static Map<String, String> pluginVersions(WorkspacePort workspace, Harness evaluator,
		List<TrialPlugin> plugins) throws IOException
	{
		Map<String, String> versions = new HashMap<>();
		Path manifest = evaluator.pluginManifest();
		for (TrialPlugin plugin : plugins)
		{
			PluginManifest.version(workspace.pluginBase(plugin.baseDigest()), manifest).
				ifPresent(version -> versions.put(plugin.name(), version));
		}
		return versions;
	}

	/**
	 * Builds a trial plugin that the project builds and copies its base.
	 *
	 * @param workspace the campaign's workspace, whose optimization worktree the build runs in
	 * @param name the plugin's name
	 * @param build the plugin's build
	 * @param evaluator the harness that installs the plugin
	 * @return the plugin, without mappings, with the repository that the output's manifest states
	 * @throws CommandRefusedException if the build cannot start, exits with a status other than zero, or its output
	 *   holds no harness manifest or one of another name
	 * @throws IOException if the base cannot be copied or its manifest cannot be read
	 * @throws InterruptedException if the thread is interrupted while it waits for the build
	 */
	private TrialPlugin builtPlugin(WorkspacePort workspace, String name, TrialPlugin.Build build, Harness evaluator)
		throws CommandRefusedException, IOException, InterruptedException
	{
		String command = "`" + String.join(" ", build.command()) + "`";
		String outcome = " Correct the build and run `start` again.";
		// A build without a command names a directory that the fork commit holds as it is
		if (!build.command().isEmpty())
		{
			PluginBuild.Result result;
			try
			{
				result = PluginBuild.run(executables, workspace.worktree(), build);
			}
			catch (IOException e)
			{
				throw new CommandRefusedException("The build " + command + " of the trial plugin " + name + " did " +
					"not start: " + e.getMessage() + ". Correct the build in the prompt test configuration and run " +
					"`start` again.", e);
			}
			outcome = " The build exited with status " + result.exitStatus() + ", and its output ended with:\n" +
				result.tail() + "\n" + outcome.strip();
			if (result.exitStatus() != 0)
			{
				throw new CommandRefusedException("The build " + command + " of the trial plugin " + name +
					" failed." + outcome);
			}
		}
		Path manifest = evaluator.pluginManifest();
		Path output = workspace.worktree().resolve(build.output());
		String built;
		try
		{
			built = PluginManifest.name(output, manifest);
		}
		catch (IOException e)
		{
			String reason = e.getMessage();
			if (!reason.endsWith("."))
				reason += ".";
			throw new CommandRefusedException("The build " + command + " of the trial plugin " + name + " left no " +
				"valid manifest " + PortablePaths.text(manifest) + " in its output " + PortablePaths.text(build.output()) +
				": " + reason + outcome, e);
		}
		if (!built.equals(name))
		{
			throw new CommandRefusedException("The build " + command + " of the trial plugin " + name + " produced " +
				"the plugin " + built + " in " + PortablePaths.text(build.output()) + ". Name the plugin as its " +
				"manifest does in the prompt test configuration." + outcome);
		}
		String digest = workspace.copyPluginBase(output);
		return new TrialPlugin(name, digest, build, List.of(),
			PluginManifest.repository(workspace.pluginBase(digest), manifest).orElse(null));
	}

	/**
	 * Copies the base of a trial plugin that the project does not build from the user's installed copy.
	 *
	 * @param root the project root
	 * @param workspace the campaign's workspace
	 * @param name the plugin's name, {@code <plugin>@<marketplace>}
	 * @param evaluator the harness that installs the plugin
	 * @return the plugin, with the repository that the installed copy's manifest states
	 * @throws CommandRefusedException if the harness's install records do not name exactly one installed copy
	 * @throws IOException if the install records cannot be read, or the copy cannot be copied or its manifest read
	 * @throws InterruptedException if the evaluator's install lookup is interrupted
	 */
	private TrialPlugin installedPlugin(Path root, WorkspacePort workspace, String name, Harness evaluator)
		throws CommandRefusedException, IOException, InterruptedException
	{
		String harness = evaluator.commandName();
		List<Path> copies;
		if (evaluator == scope.harness())
			copies = scope.sessions().installedPlugins(name);
		else
			copies = scope.peerInstalledPlugins(evaluator, root, name);
		if (copies.isEmpty())
		{
			throw new CommandRefusedException("The trial plugin " + name + " is not installed in " + harness + ": its " +
				"install records name no copy that holds the plugin's manifest. Install the plugin, or give it a build " +
				"in the prompt test configuration, and run `start` again.");
		}
		if (copies.size() > 1)
		{
			throw new CommandRefusedException("The install records of " + harness + " name " + copies.size() +
				" copies of the trial plugin " + name + ": " + copies.stream().map(Path::toString).
				collect(Collectors.joining(", ")) + ". Trials install one, so keep one installed copy and run " +
				"`start` again.");
		}
		Path copy = copies.getFirst();
		String digest = workspace.copyPluginBase(copy);
		return new TrialPlugin(name, digest, new TrialPlugin.Installed(), List.of(),
			PluginManifest.repository(workspace.pluginBase(digest), evaluator.pluginManifest()).orElse(null));
	}

	/**
	 * Returns the base copy of each trial plugin.
	 *
	 * @param plugins the trial plugins
	 * @param workspace the campaign's workspace, which holds the base copies
	 * @return each plugin with its base copy, in plugin order
	 */
	private static List<PluginBase> bases(List<TrialPlugin> plugins, WorkspacePort workspace)
	{
		List<PluginBase> bases = new ArrayList<>();
		for (TrialPlugin plugin : plugins)
			bases.add(new PluginBase(plugin, workspace.pluginBase(plugin.baseDigest())));
		return bases;
	}

	/**
	 * Returns the exact base directories of each evaluator's ordered plugin list.
	 *
	 * @param plugins the evaluator's plugins
	 * @param workspace the workspace that holds their bases
	 * @return the bases by evaluator
	 */
	private static Map<Harness, List<PluginBase>> evaluatorBases(Map<Harness, List<TrialPlugin>> plugins,
		WorkspacePort workspace)
	{
		Map<Harness, List<PluginBase>> result = new EnumMap<>(Harness.class);
		plugins.forEach((evaluator, configured) -> result.put(evaluator, bases(configured, workspace)));
		return result;
	}

	/**
	 * Returns all ordered plugin lists of a shared campaign, including empty lists stored by its old schema.
	 *
	 * @param campaign the shared campaign
	 * @return the prepared plugins by evaluator
	 */
	private static Map<Harness, List<TrialPlugin>> evaluatorPlugins(Campaign campaign)
	{
		if (!campaign.evaluatorPlugins().isEmpty())
			return campaign.evaluatorPlugins();
		Map<Harness, List<TrialPlugin>> result = new EnumMap<>(Harness.class);
		for (Harness evaluator : campaign.header().evaluationHarnesses())
			result.put(evaluator, campaign.pluginsFor(evaluator).orElse(List.of()));
		return result;
	}

	/**
	 * Returns the plugins for the purpose of including every mapped source in the common staged project.
	 *
	 * @param campaign the campaign
	 * @return the relevant mappings across its selected evaluator lists
	 */
	private static List<TrialPlugin> sourcePlugins(Campaign campaign)
	{
		return evaluatorPlugins(campaign).values().stream().flatMap(List::stream).distinct().toList();
	}

	/**
	 * Returns the prompt files that trials load from the staged project itself rather than from a trial plugin.
	 *
	 * @param root the project root
	 * @param bases the trial plugins with their base copies
	 * @param promptFiles the in-project prompt files, relative to the project
	 * @return the prompt files that the harness reads from the project, the prompt files below the project rules
	 *   directory, which CAT's rule loader reads from the staged project, and the project files that the trial
	 *   plugins' rules give the agent from the project, relative to the project
	 * @throws IOException if a file that loads cannot be read
	 */
	private Set<Path> loadsFromProject(Path root, List<PluginBase> bases, Set<Path> promptFiles) throws IOException
	{
		return projectSources(scope.harness(), scope.sessions().loadedSources(root, unmappedBases(bases)).trials(),
			promptFiles);
	}

	/**
	 * Hides the project mappings so the native loader reports files of a plugin's base as plugin sources.
	 *
	 * @param bases the evaluator's frozen bases and mappings
	 * @return the same bases without mappings
	 */
	private static List<PluginBase> unmappedBases(List<PluginBase> bases)
	{
		// Without mappings, a file that loads from a plugin's base is named by its absolute path, never as a project
		// file
		List<PluginBase> unmapped = new ArrayList<>();
		for (PluginBase base : bases)
		{
			TrialPlugin plugin = base.plugin();
			unmapped.add(new PluginBase(new TrialPlugin(plugin.name(), plugin.baseDigest(), plugin.source(), List.of(),
				plugin.repository()), base.directory()));
		}
		return unmapped;
	}

	/**
	 * Returns the project sources that were discovered directly or that the evaluator's loader recognizes.
	 *
	 * @param evaluator the harness whose loader is used
	 * @param loaded the native loader observations with unmapped bases
	 * @param promptFiles the editable prompt files from the project
	 * @return the project-relative source paths
	 */
	private static Set<Path> projectSources(Harness evaluator, List<DerivedSource> loaded, Set<Path> promptFiles)
	{
		Set<Path> paths = new HashSet<>();
		for (DerivedSource source : loaded)
		{
			Path path = Path.of(source.path());
			if (!path.isAbsolute())
				paths.add(path);
		}
		Path projectRules = ProjectRuleLoader.rulesDirectory(Path.of(""));
		for (Path promptFile : promptFiles)
		{
			if (evaluator.readsFromProject(promptFile) || promptFile.startsWith(projectRules))
				paths.add(promptFile);
		}
		return paths;
	}

	@Override
	public CommandResult run(String campaign) throws CommandRefusedException, IOException, InterruptedException
	{
		Instant sliceStart = scope.clock().instant();
		refuseWithoutWorktrees();
		refuseUnlaunchable();
		return withCleanup(notices ->
		{
			Path root = scope.projectRoot();
			Campaign loaded = require(scope.store(), campaign, true);
			Optional<CampaignLock> held = CampaignLock.tryAcquire(scope.fileLocks(), lockFile(campaign));
			if (held.isEmpty())
			{
				CommandResult snapshot = report(root, loaded, null, null);
				StatusReport busy = SliceReports.awaiting(snapshot.status(), new ReportStatus.Active(Status.BUSY),
					snapshot.status().pending());
				return new CommandResult(busy, scope.harness(), null, null, null).withNotices(snapshot.notices());
			}
			CommandResult result;
			try (CampaignLock lock = retainBackgroundLock(campaign, held.get()))
			{
				result = runHeld(root, campaign, lock, sliceStart);
			}
			// The cleanup takes the lock itself, so the command releases it first
			DeliveryResult delivery = Optional.ofNullable(result.delivery()).map(DeliveryReport::result).orElse(null);
			if (background)
				backgroundDeliveries.put(campaign, delivery);
			else
				noticeOf(cleanup().end(require(scope.store(), campaign, true), delivery), notices);
			return result;
		});
	}

	/**
	 * Advances a held campaign, including automatic test revisions that require another driver pass.
	 *
	 * @param root the project root
	 * @param id the campaign identifier
	 * @param lock the held campaign lock
	 * @param started the command start
	 * @return the result at a decision, outcome or foreground slice boundary
	 * @throws CommandRefusedException if the campaign cannot advance
	 * @throws IOException if state cannot be read or written
	 * @throws InterruptedException if waiting is interrupted
	 */
	private CommandResult runHeld(Path root, String id, CampaignLock lock, Instant started)
		throws CommandRefusedException, IOException, InterruptedException
	{
		while (true)
		{
			Campaign loaded = require(scope.store(), id, true);
			if (loaded.lifecycle() == Lifecycle.ACTIVE && !reports(loaded).isEmpty())
				verifyEvaluatorConditions(root, loaded, openFork(root, loaded));
			if (loaded.lifecycle() == Lifecycle.ACTIVE && loaded.awaitsTestRevision() && !loaded.revisionRequested())
			{
				if (loaded.pending() instanceof PendingDecision.PilotFailure)
					return report(root, loaded, null, pilotPausedNotice(loaded));
				Optional<String> unfinished = reviseTests(root, loaded, lock, started);
				loaded = require(scope.store(), id, true);
				if (unfinished.isPresent())
					return report(root, loaded, null, unfinished.get());
			}
			SliceResult result = SliceRunner.run(context(root, loaded, true), id, List.of(), lock.lend(), started);
			loaded = require(scope.store(), id, true);
			if (!background || loaded.lifecycle() != Lifecycle.ACTIVE || !loaded.awaitsTestRevision() ||
				loaded.revisionRequested() || loaded.pending() instanceof PendingDecision.TestRevisionBlocked)
				return new CommandResult(result.status(), scope.harness(), null, result.delivery(), null);
		}
	}

	/**
	 * Runs a test revision session for each prompt test whose revision the campaign awaits. A sound test is recorded
	 * at once. Once every session has ended, the corrected tests replace the confirmed tests, their guard pilot runs,
	 * and the revised contract starts a new lineage under the confirmed certification scope; then each test whose
	 * revision cannot proceed pauses the campaign.
	 * <p>
	 * The corrected contract and what the sessions decided are stored before the pilot runs, so that when the slice
	 * ends before the pilot does, the next call continues the pilot without any session. The stored revision is deleted
	 * once the pilot has ended.
	 *
	 * @param root the project root
	 * @param campaign the campaign, which awaits a test revision and no contract revision
	 * @param lock the campaign's write lock, which the caller holds
	 * @param sliceStart when the command started, which bounds the guard pilot of the revised contract
	 * @return a notice for the user if a session failed or its result is invalid, so that the remaining requests stay
	 *   pending until {@code run} retries them, or if the slice ended before the revised contract's guard pilot did,
	 *   so that the next {@code run} continues it; otherwise empty
	 * @throws CommandRefusedException if the campaign does not accept a test revision, or the fork does not hold the
	 *   revised contract's files
	 * @throws IOException if the store, the workspace, the stored proposal or a session fails
	 * @throws InterruptedException if the thread is interrupted while it waits for a session
	 */
	private Optional<String> reviseTests(Path root, Campaign campaign, CampaignLock lock, Instant sliceStart)
		throws CommandRefusedException, IOException, InterruptedException
	{
		String id = campaign.header().id();
		ProposalFile.Stored stored = new ProposalFile(heldOutDirectory(id), scope.json(), scope.fileReplacer()).read().
			orElseThrow(() -> new IOException("Campaign " + id + " has no stored confirmed proposal"));
		ContractAuthorProtocol contractAuthor = contractAuthor(root, campaign);
		RevisionFile revisionFile = revisionFile(id);
		Optional<RevisionFile.Stored> pending = revisionFile.read();
		ForkView fork = openFork(root, campaign);
		DerivedProposal current;
		List<TestRevision> revisions;
		List<CampaignEvent.BlockTestRevision> blocked;
		if (pending.isPresent())
		{
			// The sessions ended in an earlier slice, which stored what they decided
			current = restore(contractAuthor, pending.get().proposal());
			revisions = pending.get().revisions();
			blocked = pending.get().blocked();
		}
		else
		{
			Examined examined = examineTests(root, campaign, lock, contractAuthor, fork, restore(contractAuthor, stored),
				stored.profiles());
			if (examined.notice() != null)
				return Optional.of(examined.notice());
			current = examined.current();
			revisions = examined.revisions();
			blocked = examined.blocked();
			if (!revisions.isEmpty())
				revisionFile.write(current, stored, revisions, blocked);
		}

		campaign = require(scope.store(), id, true);
		List<CampaignEvent> events = new ArrayList<>();
		if (!revisions.isEmpty())
		{
			RevisedContract revised = revisedContract(root, campaign, fork, current, stored, revisions, sliceStart);
			// A blocked revision is examined again with the next run, which also continues the pilot
			if (revised.unfinished() != null)
				return Optional.of(revised.unfinished());
			if (revised.events().getFirst() instanceof CampaignEvent.PilotFailed failure)
			{
				apply(root, campaign, List.of(failure), lock);
				return Optional.of(pilotPausedNotice(campaign));
			}
			events.addAll(revised.events());
		}
		// Confirming the revised contract clears pending decisions, so a blocked revision pauses the campaign after it
		events.addAll(blocked);
		if (!events.isEmpty())
			apply(root, campaign, events, lock);
		discardRevision(id);
		return Optional.empty();
	}

	/**
	 * Runs a test revision session for each prompt test whose revision the campaign awaits.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param lock the campaign's write lock, which the caller holds
	 * @param contractAuthor the contract author's protocol
	 * @param fork the campaign's fork
	 * @param confirmed the confirmed contract
	 * @param profiles the confirmed contract's profile evidence
	 * @return what the sessions decided, or a notice that a session failed or its result is invalid
	 * @throws CommandRefusedException if the campaign does not accept a test revision
	 * @throws IOException if the store, the workspace or a session fails
	 * @throws InterruptedException if the thread is interrupted while it waits for a session
	 */
	private Examined examineTests(Path root, Campaign campaign, CampaignLock lock,
		ContractAuthorProtocol contractAuthor, ForkView fork, DerivedProposal confirmed, ProfileEvidence profiles)
		throws CommandRefusedException, IOException, InterruptedException
	{
		String id = campaign.header().id();
		TestRevisionProtocol protocol = new TestRevisionProtocol(scope.json(), schema, contractAuthor);
		Overlay incumbent = new FileCandidateOverlays(campaignDirectory(id), fork.inventory(), fork::version,
			scope.json(), scope.fileReplacer()).require(campaign.incumbent().snapshot());
		Path staged = fork.workspace().stage(incumbent, Set.of());

		DerivedProposal current = confirmed;
		List<TestRevision> revisions = new ArrayList<>();
		List<CampaignEvent.BlockTestRevision> blocked = new ArrayList<>();
		for (TestRevisionRequest request : campaign.testRevisions().requests())
		{
			TestRevisionProtocol.Examination examined = new TestRevisionProtocol.Examination(current, profiles, request,
				evidenceSections(request));
			SessionResult result = await(sessions(campaign).start(protocol.request(staged, examined)));
			String session = "The test revision of `" + request.test() + "`";
			if (!(result instanceof SessionResult.Completed completed))
				return Examined.notice(session + " " + describe(result) + " Run `run` again to retry.");
			campaign = require(scope.store(), id, true);
			if (completed.usage() != null)
			{
				campaign = Transitions.apply(campaign, new CampaignEvent.ObserveTokenUsage(SessionRole.CONTRACT_AUTHOR,
					completed.usage()));
				scope.store().replaceSnapshot(campaign);
			}

			TestRevisionProtocol.Result outcome;
			try
			{
				outcome = protocol.result(completed, examined);
			}
			catch (InvalidProposalException e)
			{
				return Examined.notice(session + " is invalid: " + String.join("; ", e.problems()) +
					". Run `run` again to retry.");
			}
			switch (outcome)
			{
				case TestRevisionProtocol.Result.Revised revised ->
				{
					PromptTest before = current.contract().findTest(request.test()).orElseThrow();
					PromptTest after = revised.proposal().contract().findTest(request.test()).orElseThrow();
					revisions.add(new TestRevision(campaign.lineage().number(), request.test(), before.scenario().render(),
						after.scenario().render(), revised.reasons()));
					current = revised.proposal();
				}
				// A sound test is recorded at once, so a later failed session does not examine it again
				case TestRevisionProtocol.Result.Sound sound -> apply(root, campaign,
					List.of(new CampaignEvent.ReportTestSound(request.test(), sound.reasons())), lock);
				case TestRevisionProtocol.Result.Blocked block -> blocked.add(new CampaignEvent.BlockTestRevision(
					request.test(), block.reason(), block.reasons(), block.proposedChange()));
			}
		}
		return new Examined(current, revisions, blocked, null);
	}

	/**
	 * What the test revision sessions decided.
	 *
	 * @param current the confirmed contract with every corrected test
	 * @param revisions the corrections, one per corrected test
	 * @param blocked the test revisions that cannot proceed
	 * @param notice a notice that a session failed or its result is invalid, or {@code null} if every session ended
	 */
	private record Examined(DerivedProposal current, List<TestRevision> revisions,
		List<CampaignEvent.BlockTestRevision> blocked, String notice)
	{
		/**
		 * Returns the outcome of sessions that did not all end.
		 *
		 * @param notice the notice
		 * @return the outcome
		 */
		static Examined notice(String notice)
		{
			return new Examined(null, List.of(), List.of(), notice);
		}
	}

	/**
	 * Restores a stored proposal and validates it against the project.
	 *
	 * @param contractAuthor the contract author's protocol
	 * @param stored the stored proposal
	 * @return the proposal
	 * @throws IOException if the stored proposal is invalid
	 */
	private DerivedProposal restore(ContractAuthorProtocol contractAuthor, ProposalFile.Stored stored)
		throws IOException
	{
		try
		{
			return stored.restore(contractAuthor.proposal(stored.derivation(), stored.draft(),
				stored.observedSources(), stored.profiles(), stored.salt()));
		}
		catch (InvalidProposalException e)
		{
			throw new IOException("The stored proposal is invalid: " + String.join("; ", e.problems()), e);
		}
	}

	/**
	 * Returns the file that holds a campaign's revised contract while its guard pilot has not ended.
	 *
	 * @param id the campaign's identifier
	 * @return the file
	 * @throws IOException if the host session's CAT session directory cannot be opened
	 */
	private RevisionFile revisionFile(String id) throws IOException
	{
		return new RevisionFile(heldOutDirectory(id), scope.json(), scope.fileReplacer());
	}

	/**
	 * Deletes a campaign's stored test revision, if it has one, and the draft directories that only it needed.
	 *
	 * @param id the campaign's identifier
	 * @throws IOException if a file cannot be deleted
	 */
	private void discardRevision(String id) throws IOException
	{
		if (!revisionFile(id).delete())
			return;
		Path heldOut = heldOutDirectory(id);
		Optional<ProposalFile.Stored> confirmed = new ProposalFile(heldOut, scope.json(), scope.fileReplacer()).read();
		if (confirmed.isPresent())
			DraftDirectory.keepOnly(heldOut, confirmed.get().draft());
	}

	/**
	 * Stores a contract whose tests a test revision corrected, runs its guard pilot and returns the events that
	 * start its lineage under the confirmed certification scope.
	 * <p>
	 * The caller holds the campaign's write lock, so no other process writes the campaign meanwhile.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param fork the campaign's fork
	 * @param revised the confirmed proposal with the corrected tests
	 * @param stored the confirmed proposal as it was stored
	 * @param revisions the changes, one per corrected test
	 * @param sliceStart when the command started, which bounds the pilot
	 * @return the events that start the lineage, or a notice that the slice ended before the pilot did
	 * @throws CommandRefusedException if the fork does not hold the revised contract's files, or something else blocks
	 *   confirming it
	 * @throws IOException if the proposal, the pilot or the workspace cannot be read or written
	 * @throws InterruptedException if the thread is interrupted while it waits for a pilot session
	 */
	private RevisedContract revisedContract(Path root, Campaign campaign, ForkView fork,
		DerivedProposal revised, ProposalFile.Stored stored, List<TestRevision> revisions, Instant sliceStart)
		throws CommandRefusedException, IOException, InterruptedException
	{
		Set<Path> participants = participants(root, revised);
		if (!unheld(fork, participants).isEmpty())
			fork = fork.extend(root, participants);
		PluginCheck plugin = pluginCheck(root, campaign, fork);
		Map<String, PilotResult> pilots = pilot(root, campaign, fork, revised, plugin,
			new PilotSlice(scope.clock(), runSettings(), runInterrupts(), sliceStart));
		if (background && stopRequested())
			return new RevisedContract(List.of(new CampaignEvent.Cancel()), null);
		Optional<String> pause = GuardPilot.pauseReason(pilots);
		// If the pilot paused, the user chooses whether to retry the saved revision's pilot.
		if (pause.isPresent())
			return new RevisedContract(List.of(new CampaignEvent.PilotFailed(pause.get())), null);
		// The slice ended with trials to run, so the host agent repeats `run`, which continues the pilot before any
		// round
		if (GuardPilot.endedBySlice(pilots))
			return new RevisedContract(List.of(), pilotProgress(pilots, "Run `run` again"));
		// The proposal previews its confirmation, which needs the campaign that awaits the revised contract
		CampaignEvent.ReviseTests revise = new CampaignEvent.ReviseTests(revisions);
		Proposal proposal = proposal(root, Transitions.apply(campaign, revise), fork, revised, pilots, plugin);
		CertificationScope certificationScope = campaign.contract().profiles().scope();
		if (!proposal.isConfirmable(certificationScope))
			throw new CommandRefusedException(proposal.confirmationRefusal(certificationScope));
		requireParticipantsHeld(root, fork, revised, trialSelection(root, campaign, revised));
		// The stored proposal changes only once nothing blocks confirming it, so it stays the confirmed contract's
		new ProposalFile(heldOutDirectory(campaign.header().id()), scope.json(), scope.fileReplacer()).write(revised,
			stored.profiles(), stored.evaluators());

		List<CampaignEvent> events = new ArrayList<>();
		events.add(revise);
		events.addAll(moveOnto(campaign, fork));
		events.addAll(confirmation(proposal, certificationScope, stored.evaluators(), campaign, fork.workspace()));
		return new RevisedContract(events, null);
	}

	/**
	 * The outcome of the guard pilot that a test revision's corrected contract runs.
	 *
	 * @param events the events that start the revised contract's lineage, or that pause the campaign; empty if the
	 *   pilot is unfinished
	 * @param unfinished a notice that says how far the pilot is and how to continue it, or {@code null} if the pilot
	 *   ended
	 */
	private record RevisedContract(List<CampaignEvent> events, String unfinished)
	{
	}

	/**
	 * Renders the evidence section of each failure of a test revision request, as a judge receives it, against the
	 * project as the failure's trial started. A failure whose trial's transcript, effects or initial project are no
	 * longer retained has no section.
	 *
	 * @param request the request
	 * @return each rendered section, by failure identifier
	 * @throws IOException if a retained trial's evidence cannot be read
	 */
	private Map<String, String> evidenceSections(TestRevisionRequest request) throws IOException
	{
		Map<String, String> sections = new HashMap<>();
		for (Map.Entry<String, Failure> failure : request.failures().entrySet())
		{
			TrialFiles files = failure.getValue().files();
			if (Files.isRegularFile(files.transcript()) && Files.isDirectory(files.effects()) &&
				Files.isDirectory(files.initialProject()))
			{
				SessionEvidence activity = scope.sessions().readEvidence(files.transcript());
				sections.put(failure.getKey(), EvidenceSection.render(activity, files.transcript(), files.effects(),
					files.initialProject()));
			}
		}
		return sections;
	}

	/**
	 * Describes why a session did not complete.
	 *
	 * @param result the session's result
	 * @return the description, as a predicate that ends with a period
	 * @throws IllegalArgumentException if the session completed
	 */
	private String describe(SessionResult result)
	{
		return switch (result)
		{
			case SessionResult.Completed _ -> throw new IllegalArgumentException("The session completed");
			case SessionResult.Failed failed -> "did not complete: " + failed.reason() + ".";
			case SessionResult.LaunchRefused refused -> "did not start. " + refused.reason();
			case SessionResult.Interrupted _ -> "was interrupted.";
			case SessionResult.Refused refused ->
			{
				if (refused.afterLongestHold())
				{
					yield "was refused by the provider after a 30-minute hold of " + scope.harness().commandName() +
						" requests.";
				}
				if (refused.hold().endsAt().isAfter(scope.clock().instant()))
					yield "was refused by the provider until " + refused.hold().endsAt() + ".";
				yield "was refused by the provider.";
			}
			case SessionResult.RateLimited limited ->
			{
				if (limited.hold().endsAt().isAfter(scope.clock().instant()))
				{
					yield "was rejected because of a rate limit until " + limited.hold().endsAt() + ".";
				}
				yield "was rejected because of a rate limit.";
			}
		};
	}

	@Override
	public CommandResult status(String subject) throws CommandRefusedException, IOException
	{
		requireThat(subject, "subject").isNotNull();
		return withCleanup(_ -> statusBody(subject));
	}

	/**
	 * Reports a campaign by its identifier or its target, or the certification record of a target without a campaign.
	 *
	 * @param subject the campaign's identifier, or a target relative to the working directory
	 * @return the result
	 * @throws CommandRefusedException if the subject names neither a campaign nor a certification record, or names a
	 *   target outside the project
	 * @throws IOException if the store, the record or a prompt file cannot be read
	 */
	private CommandResult statusBody(String subject) throws CommandRefusedException, IOException
	{
		Path root = scope.projectRoot();
		CampaignStore store = scope.store();
		boolean isId = Commands.isCampaignId(subject);
		if (isId)
		{
			Optional<Campaign> campaign = store.load(subject);
			if (campaign.isPresent())
				return reportSavedStatus(root, campaign.get());
			Path archived = backgroundArchive(subject);
			if (Files.isRegularFile(archived))
			{
				ObjectNode saved = (ObjectNode) scope.json().readTree(Files.readString(archived));
				List<String> notices = new ArrayList<>();
				if (Files.isRegularFile(noticeFailureFile(subject)))
					notices.add(Files.readString(noticeFailureFile(subject)));
				return new CommandResult(null, null, null, null, null, null, notices, saved);
			}
			Optional<SessionCampaign> other = loadFromOtherSession(subject);
			if (other.isPresent())
				return reportOtherSession(root, other.get());
		}

		Path absolute = scope.directory().toRealPath().resolve(subject).normalize();
		// A target that no longer exists may still have a campaign or a record
		if (Files.exists(absolute))
			absolute = absolute.toRealPath();
		requireInsideProject(root, absolute);
		Optional<Registry.OwnedHeader> header = Registry.ofTarget(headers(), root, absolute);
		if (header.isPresent() && header.get().header().lifecycle().isResumable())
			return report(root, header.get());

		// A finished campaign must not hide a certification that a later campaign delivered
		Path relative = root.relativize(absolute);
		CertificationHistory history = history(root, relative);
		if (history.entries().isEmpty() && header.isPresent())
			return report(root, header.get());
		if (history.entries().isEmpty())
		{
			String absent = "no campaign or certification record exists for " + relative + ".";
			if (isId)
				throw new CommandRefusedException("No campaign has the identifier " + subject + ", and " + absent);
			throw new CommandRefusedException("N" + absent.substring(1), null,
				CommandRefusedException.Reason.NO_CAMPAIGN_OR_CERTIFICATION_RECORD);
		}
		Entries files = path -> InventoryScanner.entry(root, path);
		MeasurementIdentity known = environment(files);
		return CommandResult.of(certificationStatus(history, files, known,
			record -> record.parameters().minimumPassRate()));
	}

	/**
	 * A campaign and the session that started it.
	 *
	 * @param sessionId the ID of the session that started the campaign
	 * @param campaign the campaign
	 */
	private record SessionCampaign(String sessionId, Campaign campaign)
	{
	}

	/**
	 * Loads a campaign that another session of the project started, and reads its files from that session's CAT session
	 * directory for the rest of the command.
	 *
	 * @param id the campaign's identifier
	 * @return the campaign, or empty if no other session has it
	 * @throws CommandRefusedException if the environment names no host session
	 * @throws IOException if the campaigns cannot be read
	 */
	private Optional<SessionCampaign> loadFromOtherSession(String id) throws CommandRefusedException, IOException
	{
		SessionDirectory host = scope.sessionDirectory();
		for (Map.Entry<String, Path> session : host.campaignsBySession().entrySet())
		{
			if (session.getKey().equals(host.sessionId()))
				continue;
			Optional<Campaign> campaign = new FileCampaignStore(session.getValue(), scope.projectRoot(), scope.json(),
				scope.fileReplacer()).load(id);
			if (campaign.isPresent())
			{
				otherSessionCampaigns.put(id, session.getValue());
				return Optional.of(new SessionCampaign(session.getKey(), campaign.get()));
			}
		}
		return Optional.empty();
	}

	/**
	 * Reports a campaign of any session.
	 *
	 * @param root the project root
	 * @param owner the campaign and the session that started it
	 * @return the result
	 * @throws CommandRefusedException if the environment names no host session
	 * @throws IOException if the campaign cannot be read
	 */
	private CommandResult report(Path root, Registry.OwnedHeader owner) throws CommandRefusedException, IOException
	{
		String id = owner.header().id();
		if (owner.sessionId().equals(scope.sessionDirectory().sessionId()))
			return reportSavedStatus(root, require(scope.store(), id, false));
		SessionCampaign other = loadFromOtherSession(id).orElseThrow(() ->
			new IOException("Campaign " + id + " of session " + owner.sessionId() + " was removed while it was read."));
		return reportOtherSession(root, other);
	}

	/**
	 * Reports a campaign that another session started, with a notice that names that session.
	 *
	 * @param root the project root
	 * @param other the campaign and the session that started it
	 * @return the result
	 * @throws IOException if the campaign's fork cannot be read
	 */
	private CommandResult reportOtherSession(Path root, SessionCampaign other) throws IOException
	{
		Registry.OwnedHeader owner = new Registry.OwnedHeader(other.sessionId(), other.campaign().header());
		return reportSavedStatus(root, other.campaign()).
			withNotices(List.of("Campaign " + owner.header().id() + " " + ownership(owner)));
	}

	/**
	 * Reports saved progress, including a guard pilot that can continue without a user decision.
	 *
	 * @param root the project root
	 * @param campaign the campaign snapshot
	 * @return the status result
	 * @throws IOException if the pilot checkpoint or other status evidence cannot be read
	 */
	private CommandResult reportSavedStatus(Path root, Campaign campaign) throws IOException
	{
		if (campaign.lifecycle() == Lifecycle.ACTIVE && !campaign.isConfirmed() && campaign.pending() == null)
		{
			Map<String, PilotResult> pilots = pilotFile(campaign).read();
			if (GuardPilot.hasUnfinishedCheckpoint(pilots) && GuardPilot.pauseReason(pilots).isEmpty())
				return report(root, campaign, null, null, true);
		}
		return report(root, campaign, null, null);
	}

	/**
	 * Compares the latest entry of each harness with the prompt files and conditions that exist now.
	 *
	 * @param history the target's certification history, which holds at least one entry
	 * @param files the files that exist now
	 * @param known the conditions that CAT knows now
	 * @param minimumPassRate the minimum pass rate {@code p_min} that a record must prove now
	 * @return the status
	 * @throws IOException if a prompt file cannot be read
	 */
	private static CertificationStatus certificationStatus(CertificationHistory history, Entries files,
		MeasurementIdentity known, Function<CertificationRecord, BigDecimal> minimumPassRate) throws IOException
	{
		Map<Path, Sha256> promptFiles = new TreeMap<>();
		for (HistoryEntry delivered : history.latest())
		{
			for (Path path : delivered.promptFiles().keySet())
			{
				Optional<FileEntry> entry = files.entry(path);
				if (entry.isPresent() && entry.get() instanceof FileEntry.RegularFile file)
					promptFiles.put(path, file.contentHash());
			}
		}
		return CertificationStatus.of(history, promptFiles, known, minimumPassRate);
	}

	/**
	 * Reads the certification history of a target.
	 *
	 * @param root the project root
	 * @param target the target, relative to the project
	 * @return the history, which is empty if the target has never been certified
	 * @throws IOException if the history file exists but cannot be read
	 */
	private CertificationHistory history(Path root, Path target) throws IOException
	{
		Path file = root.resolve(PromptTestLocations.certificationHistory(target));
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			return CertificationHistory.EMPTY;
		return new CertificationHistoryJson(scope.json()).read(file);
	}

	@Override
	public CommandResult confirm(String campaign, CertificationScope certificationScope)
		throws CommandRefusedException, IOException
	{
		requireThat(certificationScope, "certificationScope").isNotNull();
		return withCleanup(_ ->
		{
			Path root = scope.projectRoot();
			CampaignStore store = scope.store();
			require(store, campaign, true);
			try (CampaignLock lock = lock(campaign))
			{
				return confirmHeld(root, require(store, campaign, true), certificationScope, lock);
			}
		});
	}

	/**
	 * Confirms the stored proposal of a campaign whose write lock this command holds.
	 *
	 * @param root the project root
	 * @param loaded the campaign
	 * @param certificationScope the certification scope that the user confirmed
	 * @param lock the campaign's write lock
	 * @return the result
	 * @throws CommandRefusedException if the campaign has no confirmable proposal or does not accept the confirmation
	 * @throws IOException if the store or the workspace fails
	 */
	private CommandResult confirmHeld(Path root, Campaign loaded, CertificationScope certificationScope,
		CampaignLock lock) throws CommandRefusedException, IOException
	{
		String campaign = loaded.header().id();
		if (loaded.pending() instanceof PendingDecision.PilotFailure)
		{
			throw new CommandRefusedException("Campaign " + campaign + " has no proposal to confirm yet. " +
				pilotPausedNotice(loaded));
		}
		ForkView fork = openFork(root, loaded);
		try
		{
			verifyEvaluatorConditions(root, loaded, fork);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			InterruptedIOException interrupted = new InterruptedIOException("Interrupted while validating evaluators");
			interrupted.initCause(e);
			throw interrupted;
		}
		DerivedProposal derived = stored(root, loaded).orElseThrow(() -> new CommandRefusedException(
			"Campaign " + campaign + " has no proposal to confirm. Run `revise` or `start` to derive one."));
		// A revision after confirmation runs on the files that its presentation recorded beside the fork's
		if (loaded.isConfirmed())
			fork = fork.including(participants(root, derived));
		PluginCheck plugin = pluginCheck(root, loaded, fork);
		Map<String, PilotResult> pilots = pilotFile(loaded).read();
		Proposal proposal = proposal(root, loaded, fork, derived, pilots, plugin);
		if (!proposal.isConfirmable(certificationScope))
			throw new CommandRefusedException(proposal.confirmationRefusal(certificationScope));
		SourceSelection selection = trialSelection(root, loaded, derived);
		requireParticipantsHeld(root, fork, derived, selection);
		requireFreshPeerPilot(loaded, fork, derived, selection, pilots);
		List<CampaignEvent> events = new ArrayList<>(moveOnto(loaded, fork));
		events.addAll(confirmation(proposal, certificationScope, reports(loaded), loaded, fork.workspace()));
		return apply(root, loaded, events, lock);
	}

	/**
	 * Verifies that the first proposal evaluated by a sole peer still has evidence under the current owner judge.
	 * The evaluator probe pins the peer conditions, but a sole peer does not exercise the owner's judge version.
	 *
	 * @param campaign the campaign awaiting confirmation
	 * @param fork the proposal's frozen sources
	 * @param derived the proposal
	 * @param selection the sources the trials receive
	 * @param pilots the displayed pilot results
	 * @throws IOException if the frozen sources or trial ports cannot be read
	 * @throws CommandRefusedException if the pilot's measurement conditions changed
	 */
	private void requireFreshPeerPilot(Campaign campaign, ForkView fork, DerivedProposal derived,
		SourceSelection selection, Map<String, PilotResult> pilots) throws IOException, CommandRefusedException
	{
		Set<Harness> evaluators = campaign.header().evaluationHarnesses();
		if (campaign.isConfirmed() || evaluators.size() != 1 || evaluators.contains(campaign.header().harness()) ||
			pilots.isEmpty())
			return;
		Harness evaluator = evaluators.iterator().next();
		if (!guardPilot(campaign, fork).matchesStored(derived.contract(), evaluator, evaluatorPlugins(campaign).
			get(evaluator), pilotControl(campaign, fork, derived), selection, pilots))
		{
			throw new CommandRefusedException("The guard pilot ran under measurement conditions that have changed. " +
				"Run `start` for the same target to see the proposal with a refreshed guard pilot before confirming it.");
		}
	}

	/**
	 * Requires that the fork holds the derived prompt sources of the proposal that the user confirms, which presenting
	 * the proposal recorded, so that trials never read a live file.
	 *
	 * @param root the project root
	 * @param fork the fork that the confirmed contract runs on
	 * @param derived the proposal that the user confirms
	 * @param selection the files of the candidate that every trial receives
	 * @throws CommandRefusedException if the fork lacks a file that the proposal needs, a workspace file of a prompt
	 *   test would replace a file of the fork that every trial receives, or a workspace file is a copy of a file that
	 *   every trial receives
	 */
	private static void requireParticipantsHeld(Path root, ForkView fork, DerivedProposal derived,
		SourceSelection selection) throws CommandRefusedException
	{
		Set<String> promptCopies = promptFileCopies(derived.contract(), selection);
		if (!promptCopies.isEmpty())
		{
			throw new CommandRefusedException("These workspace files of the proposal's prompt tests are copies of files " +
				"that every trial already receives from its candidate, so each copy would stand in for the " +
				"candidate's version: " + String.join("; ", promptCopies) + ". Run `revise` with feedback that " +
				"removes these links from the tests' " + PromptTestFiles.WORKSPACE + "/ directories.");
		}
		Set<String> collisions = collisionsOf(fork.inventory(), derived.contract(), selection);
		if (!collisions.isEmpty())
		{
			throw new CommandRefusedException("These workspace files of the proposal's prompt tests collide with " +
				"project files, so the trials could not receive them: " + String.join(", ", collisions) + ". Run " +
				"`revise` with feedback that asks for other paths.");
		}
		String unheld = unheld(fork, participants(root, derived));
		if (unheld.isEmpty())
			return;
		throw new CommandRefusedException("These files appeared after CAT presented the proposal, so its guard pilot " +
			"did not see them: " + unheld + ". Run `start` for the same target again so that CAT presents the " +
			"proposal with them.");
	}

	/**
	 * Returns the workspace files of a contract's prompt tests that are copies of a file that every trial receives from
	 * its candidate. A file of a test root is never one, because trials do not receive the test roots.
	 *
	 * @param contract the contract
	 * @param selection the files of the candidate that every trial receives
	 * @return one description of each copy, naming its prompt test, path and source
	 */
	private static Set<String> promptFileCopies(Contract contract, SourceSelection selection)
	{
		Set<String> copies = new TreeSet<>();
		for (PromptTest test : contract.tests())
		{
			for (WorkspaceFile workspaceFile : test.workspaceFiles())
			{
				if (workspaceFile.source().isEmpty())
					continue;
				Path source = Path.of(workspaceFile.source()).normalize();
				if (source.isAbsolute() || PromptTestLocations.rootOf(source).isPresent() || !selection.selects(source))
					continue;
				copies.add("`" + test.id() + "` copies `" + workspaceFile.source() + "` to `" + workspaceFile.path() + "`");
			}
		}
		return copies;
	}

	/**
	 * Returns the workspace files of a contract's prompt tests that would replace a file of the fork that every trial
	 * receives, or that need a directory where every trial receives a file.
	 *
	 * @param inventory the files of the fork
	 * @param contract the contract
	 * @param selection the files of the candidate that every trial receives
	 * @return the colliding paths of every test case, relative to the workspace root
	 */
	private static Set<String> collisionsOf(FileInventory inventory, Contract contract, SourceSelection selection)
	{
		Set<String> collisions = new TreeSet<>();
		for (PromptTest test : contract.tests())
		{
			for (TestCase testCase : test.allTestCases())
			{
				for (String target : test.workspaceTargets(testCase).keySet())
				{
					Path path = Path.of(target);
					boolean collides = selection.selects(path) && inventory.holds(path);
					for (Path parent = path.getParent(); parent != null && !collides; parent = parent.getParent())
						collides = selection.selects(parent) && inventory.entry(parent).isPresent();
					if (collides)
						collisions.add(target);
				}
			}
		}
		return collisions;
	}

	/**
	 * Moves a confirmed campaign whose revision waits for confirmation onto the fork that holds the files of the
	 * revised contract, if that fork holds more files than the campaign's. The fork becomes the campaign's current
	 * fork, the incumbent and the working candidate keep their edits on it, and the optimization worktree is rewritten
	 * to hold the working candidate. Repeating the move after an interruption repeats each step.
	 * <p>
	 * The caller holds the campaign's write lock, so no other process writes the campaign meanwhile.
	 *
	 * @param campaign the campaign
	 * @param fork the fork that the revised contract runs on
	 * @return the event that moves the campaign, or none if the campaign is not confirmed or is on that fork already
	 * @throws CommandRefusedException if the working candidate's edits do not apply to the larger fork
	 * @throws IOException if the overlays, the fork's selection or the worktree cannot be read or written
	 */
	private List<CampaignEvent> moveOnto(Campaign campaign, ForkView fork)
		throws CommandRefusedException, IOException
	{
		if (!campaign.isConfirmed())
			return List.of();
		FileInventory larger = fork.inventory();
		FileCandidateOverlays overlays = new FileCandidateOverlays(campaignDirectory(campaign.header().id()), larger,
			fork::version, scope.json(), scope.fileReplacer());
		if (overlays.require(campaign.incumbent().snapshot()).fork().equals(larger))
			return List.of();
		Overlay working;
		try
		{
			working = overlays.require(campaign.development().working().snapshot()).onto(larger);
		}
		catch (IllegalArgumentException e)
		{
			throw new CommandRefusedException("The working candidate's edits do not apply beside the files that the " +
				"revised contract needs: " + e.getMessage(), e);
		}
		Overlay incumbent = Overlay.of(larger);
		fork.adopt();
		overlays.save(incumbent);
		overlays.save(working);
		fork.workspace().materialize(working);
		return List.of(new CampaignEvent.ExtendFork(incumbent.identity(), working.identity()));
	}

	/**
	 * Returns the participants of a proposal that the fork does not hold.
	 *
	 * @param fork the campaign's fork
	 * @param participants the proposal's participants
	 * @return the participants, separated by commas, or an empty string if the fork holds them all
	 */
	private static String unheld(ForkView fork, Set<Path> participants)
	{
		FileInventory held = fork.inventory();
		return participants.stream().filter(path -> !held.holds(path)).map(Path::toString).
			collect(Collectors.joining(", "));
	}

	/**
	 * Returns the derived prompt sources and the referenced files of a proposal that exist.
	 *
	 * @param root the project root
	 * @param derived the contract author's proposal
	 * @return the sources and files, named as the fork names participants
	 */
	private static Set<Path> participants(Path root, DerivedProposal derived)
	{
		Set<Path> existing = new LinkedHashSet<>();
		Set<Path> candidates = new LinkedHashSet<>(derivedPaths(root, derived));
		candidates.addAll(derived.contract().referencedFiles());
		for (Path source : candidates)
		{
			if (Files.exists(root.resolve(source), LinkOption.NOFOLLOW_LINKS))
				existing.add(source);
		}
		return existing;
	}

	/**
	 * Returns the derived prompt sources as the fork names them.
	 *
	 * @param root the project root
	 * @param derived the contract author's proposal
	 * @return paths relative to the project for sources inside it, and absolute paths for sources outside it
	 */
	private static Set<Path> derivedPaths(Path root, DerivedProposal derived)
	{
		return derivedPaths(root, derived.derivedSources());
	}

	/**
	 * Returns derived prompt sources as the fork names them.
	 *
	 * @param root    the project root
	 * @param sources the sources
	 * @return paths relative to the project for sources inside it, and absolute paths for sources outside it
	 */
	private static Set<Path> derivedPaths(Path root, List<DerivedSource> sources)
	{
		Set<Path> paths = new LinkedHashSet<>();
		for (DerivedSource source : sources)
		{
			Path absolute;
			try
			{
				absolute = root.resolve(source.path()).normalize();
			}
			catch (InvalidPathException _)
			{
				continue;
			}
			if (!absolute.startsWith(root))
			{
				paths.add(absolute);
				continue;
			}
			Path relative = root.relativize(absolute);
			if (!relative.toString().isEmpty() && !InventoryScanner.isOmitted(relative))
				paths.add(relative);
		}
		return paths;
	}

	@Override
	public CommandResult revise(String campaign, String feedback)
		throws CommandRefusedException, IOException, InterruptedException
	{
		requireThat(feedback, "feedback").isNotBlank();
		Instant sliceStart = scope.clock().instant();
		return withCleanup(_ ->
		{
			require(scope.store(), campaign, true);
			try (CampaignLock lock = lock(campaign))
			{
				return reviseBody(campaign, feedback, lock, sliceStart);
			}
		});
	}

	/**
	 * Derives a revised proposal from the user's feedback.
	 *
	 * @param campaign the campaign's identifier
	 * @param feedback the user's feedback
	 * @param lock the campaign's write lock, which the caller holds
	 * @param sliceStart when the command started, which bounds the guard pilot that the command runs
	 * @return the result
	 * @throws CommandRefusedException if the campaign cannot be revised, or every session would be refused because no
	 *   request server serves the host session. The campaign is unchanged: a revision request that the contract
	 *   author never answered would leave it waiting for a revised contract that does not exist.
	 * @throws IOException if the store, the workspace or the session fails
	 * @throws InterruptedException if the thread is interrupted while it waits for the session
	 */
	private CommandResult reviseBody(String campaign, String feedback, CampaignLock lock, Instant sliceStart)
		throws CommandRefusedException, IOException, InterruptedException
	{
		refuseUnlaunchable();
		Path root = scope.projectRoot();
		CampaignStore store = scope.store();
		Campaign loaded = require(store, campaign, true);
		regressionScenarios(root);
		ForkView fork = openFork(root, loaded);
		DerivedProposal previous = stored(root, loaded).orElseThrow(() -> new CommandRefusedException(
			"Campaign " + campaign + " has no proposal to revise. Run `start` again to derive one."));
		if (loaded.isConfirmed() && !loaded.revisionRequested())
		{
			apply(root, loaded, List.of(new CampaignEvent.Revise()), lock);
			loaded = require(store, campaign, true);
		}
		return derive(root, store, loaded, fork, previous, feedback, sliceStart);
	}

	@Override
	public CommandResult choose(String campaign, Choice choice, int attempt, int reviewPoint)
		throws CommandRefusedException, IOException, InterruptedException
	{
		Instant sliceStart = scope.clock().instant();
		refuseWithoutWorktrees();
		return withCleanup(notices ->
		{
			Path root = scope.projectRoot();
			require(scope.store(), campaign, true);
			CommandResult result;
			DeliveryResult delivery = null;
			try (CampaignLock lock = lock(campaign))
			{
				Campaign loaded = require(scope.store(), campaign, true);
				if (choice == Choice.PROPOSED_CHANGE && loaded.choices().contains(choice) &&
					loaded.pending() instanceof PendingDecision.TestRevisionBlocked blocked)
				{
					// The contract author derives the revised contract from the change, which the user then confirms
					return reviseBody(campaign, blocked.proposedChange(), lock, sliceStart);
				}
				boolean retriesPilot = choice == Choice.RETRY && loaded.pending() instanceof PendingDecision.PilotFailure &&
					loaded.choices().contains(choice);
				if (retriesPilot)
				{
					refuseUnlaunchable();
					PilotFile file = pilotFile(loaded);
					// Keep the pending choice until the renewed budget is durable. Repeating the reset is harmless.
					file.write(GuardPilot.retryPaused(file.read()));
				}
				result = apply(root, loaded, List.of(new CampaignEvent.Choose(choice, attempt, reviewPoint)), lock);
				Campaign chosen = require(scope.store(), campaign, true);
				// The retry runs the pilot's remaining trials and presents the proposal. A confirmed campaign's test
				// revision pilot runs again with its test revision, when the user runs the campaign.
				if (retriesPilot && !(chosen.isConfirmed() && !chosen.revisionRequested()))
					return resumeHeld(root, scope.store(), chosen, sliceStart);
				if (background && chosen.lifecycle() == Lifecycle.ACTIVE && chosen.isConfirmed())
				{
					refuseUnlaunchable();
					result = runHeld(root, campaign, lock, sliceStart);
					delivery = Optional.ofNullable(result.delivery()).map(DeliveryReport::result).orElse(null);
				}
				else if (chosen.lifecycle() == Lifecycle.FINISHED && (chosen.delivery() != null || chosen.isIncumbentKept()))
				{
					// A choice that ends the campaign delivers the selected candidate, or the kept incumbent's prompt tests
					SliceResult slice = SliceRunner.run(context(root, chosen, true), campaign, List.of(), lock.lend());
					delivery = Optional.ofNullable(slice.delivery()).map(DeliveryReport::result).orElse(null);
					result = new CommandResult(slice.status(), scope.harness(), null, slice.delivery(), null);
				}
			}
			// The cleanup takes the lock itself, so the command releases it first
			if (background)
				backgroundDeliveries.put(campaign, delivery);
			else
				noticeOf(cleanup().end(require(scope.store(), campaign, true), delivery), notices);
			return result;
		});
	}

	@Override
	public CommandResult resolveConflict(String campaign, int resolution)
		throws CommandRefusedException, IOException, InterruptedException
	{
		Instant sliceStart = scope.clock().instant();
		return withCleanup(_ ->
		{
			require(scope.store(), campaign, true);
			try (CampaignLock lock = lock(campaign))
			{
				return resolveConflictBody(campaign, resolution, lock, sliceStart);
			}
		});
	}

	/**
	 * Records a resolution of the current conflict, revising the proposal after the final conflict's choice.
	 *
	 * @param campaign the campaign's identifier
	 * @param resolution the 1-based position among the current conflict's resolutions
	 * @param lock the campaign's write lock, which the caller holds
	 * @param sliceStart when the command started, which bounds the guard pilot that the command runs
	 * @return the result
	 * @throws CommandRefusedException if the campaign offers no such resolution now
	 * @throws IOException if the store, the workspace or the session fails
	 * @throws InterruptedException if the thread is interrupted while it waits for the session
	 */
	private CommandResult resolveConflictBody(String campaign, int resolution, CampaignLock lock,
		Instant sliceStart)
		throws CommandRefusedException, IOException, InterruptedException
	{
		Path root = scope.projectRoot();
		Campaign loaded = require(scope.store(), campaign, true);
		if (loaded.lifecycle() != Lifecycle.ACTIVE ||
			!(loaded.pending() instanceof PendingDecision.ConflictFound conflicts))
		{
			throw new CommandRefusedException("Campaign " + campaign + " does not offer a conflict resolution now.");
		}
		List<Integer> chosen;
		try
		{
			chosen = conflicts.choose(resolution);
		}
		catch (IllegalArgumentException e)
		{
			throw new CommandRefusedException("Campaign " + campaign + " does not offer resolution_" +
				resolution + ": " + e.getMessage(), e);
		}
		if (chosen.size() == conflicts.conflicts().size())
			return reviseBody(campaign, conflicts.feedback(resolution), lock, sliceStart);
		return apply(root, loaded, List.of(new CampaignEvent.ResolveConflict(resolution)), lock);
	}

	@Override
	public CommandResult cancel(String campaign) throws CommandRefusedException, IOException
	{
		refuseWithoutWorktrees();
		return withCleanup(_ ->
		{
			Path root = scope.projectRoot();
			require(scope.store(), campaign, true);
			Optional<BackgroundDriver.Owner> owner =
				BackgroundDriver.owner(lockFile(campaign), scope.fileLocks(), scope.json());
			if (owner.isPresent())
			{
				BackgroundDriver.requestStop(lockFile(campaign), owner.get(), scope.fileReplacer());
				Instant end = scope.clock().instant().plus(scope.sliceSettings().grace()).plusSeconds(30);
				while (true)
				{
					Optional<CampaignLock> acquired = CampaignLock.tryAcquire(scope.fileLocks(), lockFile(campaign));
					if (acquired.isPresent())
					{
						try (CampaignLock lock = acquired.get())
						{
							Campaign loaded = require(scope.store(), campaign, true);
							if (loaded.lifecycle() != Lifecycle.ACTIVE)
								return report(root, loaded, null, null);
							return apply(root, loaded, List.of(new CampaignEvent.Cancel()), lock);
						}
					}
					if (!scope.clock().instant().isBefore(end))
						return report(root, require(scope.store(), campaign, true), null,
							"The stop request stands; the background driver has not yet ended. Use `status`.");
					scope.waiting().every(scope.sliceSettings().pollInterval()).pause();
				}
			}
			try (CampaignLock lock = lock(campaign))
			{
				Campaign loaded = require(scope.store(), campaign, true);
				return apply(root, loaded, List.of(new CampaignEvent.Cancel()), lock);
			}
		});
	}

	/**
	 * Returns the cleanup of the project's campaigns.
	 *
	 * @return the cleanup
	 * @throws CommandRefusedException if the working directory is not inside a Git repository whose root may be a
	 *   CAT project
	 * @throws IOException if the project root cannot be resolved
	 */
	private Cleanup cleanup() throws CommandRefusedException, IOException
	{
		return new Cleanup(scope.fileLocks(), scope.projectRoot(), scope.sessionDirectory().campaigns(), scope.forks(),
			scope.sessions().resultsFolders(), scope.json(), scope.fileReplacer(), _ -> {}, scope.sessions(), id ->
				new PilotFile(heldOutDirectory(id), scope.json(), scope.fileReplacer()).read().values().stream().
					flatMap(pilot -> pilot.pendingTrials().values().stream()).
					flatMap(recovery -> recovery.requestId().stream()).toList());
	}

	/**
	 * Applies the user's events through the campaign's driver, without running a slice.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param events the events
	 * @param lock the campaign's write lock, which the caller holds and releases
	 * @return the campaign's status after the events
	 * @throws CommandRefusedException if the campaign does not accept an event
	 * @throws IOException if the store or the workspace fails
	 */
	private CommandResult apply(Path root, Campaign campaign, List<CampaignEvent> events, CampaignLock lock)
		throws CommandRefusedException, IOException
	{
		String id = campaign.header().id();
		DriverContext context = context(root, campaign, false);
		try
		{
			Driver.applyEvents(context, id, lock.lend(), events);
		}
		catch (IllegalStateException | IllegalArgumentException e)
		{
			throw new CommandRefusedException("Campaign " + id + " does not accept this command now: " +
				e.getMessage(), e);
		}
		Campaign applied = require(scope.store(), id, true);
		// A campaign that no longer awaits its test revision has no use for the revised contract that it stored
		if (applied.lifecycle() != Lifecycle.ACTIVE || !applied.awaitsTestRevision() || applied.revisionRequested())
			discardRevision(id);
		return report(root, applied, null, null);
	}

	/**
	 * Runs the contract author and presents its proposal.
	 * <p>
	 * The caller holds the campaign's write lock, so no other process writes the campaign meanwhile.
	 *
	 * @param root the project root
	 * @param store persists the campaign's observed usage
	 * @param campaign the campaign
	 * @param fork the campaign's fork
	 * @param previous the proposal that the user gave feedback on, or {@code null} for the first derivation
	 * @param feedback the user's feedback, or {@code null} for the first derivation
	 * @param sliceStart when the command started, which bounds the guard pilot that the command runs
	 * @return the result. If the contract author fails, the result carries the reason instead of a proposal.
	 * @throws IOException if the store, the workspace or the session fails
	 * @throws InterruptedException if the thread is interrupted while it waits for the session
	 */
	private CommandResult derive(Path root, CampaignStore store, Campaign campaign, ForkView fork,
		DerivedProposal previous, String feedback, Instant sliceStart) throws IOException, InterruptedException
	{
		Path directory = campaignDirectory(campaign.header().id());
		Overlay incumbent = new FileCandidateOverlays(directory, fork.inventory(), fork::version, scope.json(),
			scope.fileReplacer()).
			require(campaign.incumbent().snapshot());
		// The contract author reads the regression scenarios, so nothing is left out
		Path staged = fork.workspace().stage(incumbent, Set.of());
		ProfileEvidence profiles = profileEvidence(staged);
		String unsupported = unsupportedBindings(profiles);
		if (!unsupported.isEmpty())
			return report(root, campaign, null, unsupported);
		Map<Harness, EvaluatorMetadata> evaluators = Map.of();
		Set<Harness> selected = campaign.header().evaluationHarnesses();
		if (!selected.equals(Set.of(scope.harness())))
		{
			try
			{
				evaluators = evaluatorReports(root, staged, relativeTarget(campaign), selected,
					evaluatorBases(evaluatorPlugins(campaign), fork.workspace()));
			}
			catch (CommandRefusedException e)
			{
				return report(root, campaign, null, e.getMessage());
			}
			Map<Harness, ProfileEvidence> observations = new EnumMap<>(Harness.class);
			evaluators.forEach((harness, metadata) -> observations.put(harness, metadata.profiles()));
			profiles = new ProfileEvidence(profiles.inventory(), profiles.ranking(), profiles.bindings(), observations);
		}
		ContractAuthorProtocol protocol = contractAuthor(root, campaign, evaluators);
		List<PluginBase> bases = bases(campaign.pluginsFor(scope.harness()).orElse(List.of()), fork.workspace());
		List<HookRegistration> hooks = List.of();
		if (selected.contains(scope.harness()))
			hooks = scope.sessions().hookRegistrations(staged, bases);
		Map<Harness, List<HookRegistration>> scopedHooks = scopedHooks(evaluators, hooks);
		List<DerivedSource> loaded = proposalSources(root, bases, evaluators);
		Optional<String> failedHooks = checkHooks(root, campaign, fork, incumbent, staged, loaded, evaluators);
		if (failedHooks.isPresent())
			return report(root, campaign, null, failedHooks.get());
		SessionRequest request;
		long salt;
		String invalidRetry;
		if (previous == null)
		{
			salt = scope.random().nextLong();
			if (scopedHooks.isEmpty())
				request = protocol.request(staged, profiles, hooks, loaded, salt);
			else
				request = protocol.request(staged, profiles, scopedHooks, loaded, salt);
			invalidRetry = "Run `start` again to retry.";
		}
		else
		{
			// Revision keeps the salt, so the test cases that the user reviewed keep their parts
			salt = previous.contract().salt();
			Map<String, PilotResult> pilots = pilotFile(campaign).read();
			if (scopedHooks.isEmpty())
				request = protocol.request(staged, profiles, hooks, loaded, previous, feedback, pilots);
			else
				request = protocol.request(staged, profiles, scopedHooks, loaded, previous, feedback, pilots);
			// The proposal that the user gave feedback on is still stored, so revise can regenerate it again
			invalidRetry = "Run `revise` with feedback, or `start`, to retry.";
		}
		SessionResult result = await(sessions(campaign).start(request));
		SessionResult.Completed completed;
		switch (result)
		{
			case SessionResult.Completed ended -> completed = ended;
			case SessionResult.Failed failed ->
			{
				return report(root, campaign, null, "The contract author session did not complete: " + failed.reason() +
					". Run `start` again to retry.");
			}
			case SessionResult.LaunchRefused refused ->
			{
				return report(root, campaign, null, "The contract author session did not start. " + refused.reason());
			}
			case SessionResult.Refused refused ->
			{
				Instant end = refused.hold().endsAt();
				boolean held = end.isAfter(scope.clock().instant());
				String retry = "Run `start` again later.";
				if (held)
					retry = "Run `start` again after " + end + ".";
				if (refused.afterLongestHold())
				{
					return report(root, campaign, null, "The provider refused " + scope.harness().commandName() +
						" requests after a 30-minute hold. " + retry);
				}
				if (held)
				{
					return report(root, campaign, null, "The provider refused the contract author session until " +
						end + ". Run `start` again after that time.");
				}
				return report(root, campaign, null, "The provider refused the contract author session. " + retry);
			}
			case SessionResult.Interrupted _ ->
			{
				return report(root, campaign, null, "The contract author session was interrupted. " +
					"Run `start` again to retry.");
			}
			case SessionResult.RateLimited limited ->
			{
				String until = "";
				String retry = "Run `start` again later.";
				if (limited.hold().endsAt().isAfter(scope.clock().instant()))
				{
					until = " until " + limited.hold().endsAt();
					retry = "Run `start` again after that time.";
				}
				return report(root, campaign, null,
					"The provider rejected the contract author session because of a rate limit" + until + ". " + retry);
			}
		}

		if (completed.usage() != null)
		{
			campaign = Transitions.apply(campaign, new CampaignEvent.ObserveTokenUsage(SessionRole.CONTRACT_AUTHOR,
				completed.usage()));
			store.replaceSnapshot(campaign);
		}

		DerivedProposal derived;
		try
		{
			derived = protocol.proposal(completed, staged, profiles, salt);
		}
		catch (InvalidProposalException e)
		{
			return report(root, campaign, null, "The contract author's proposal is invalid: " +
				String.join("; ", e.problems()) + ". " + invalidRetry);
		}
		derived = derived.withDerivedSources(proposalSources(root, bases, evaluators));
		// The draft's values files hold the expected outcomes of both parts, so it moves into the held-out directory
		Path heldOut = heldOutDirectory(campaign.header().id());
		derived = derived.withDraft(DraftDirectory.store(derived.draft(), heldOut), derived.changes());
		// A new proposal replaces the revised contract of a test revision, whose draft directory the write removes
		revisionFile(campaign.header().id()).delete();
		new ProposalFile(heldOut, scope.json(), scope.fileReplacer()).write(derived, profiles, evaluators);
		return present(root, store, campaign, fork, derived, sliceStart);
	}

	/**
	 * Returns the native hook declarations for the proposal's explicitly scoped evaluators.
	 *
	 * @param evaluators the native evaluator reports
	 * @param ownerHooks the owner's declarations when its report omitted the optional observations
	 * @return the hook declarations by evaluator, empty for a legacy singleton
	 */
	private Map<Harness, List<HookRegistration>> scopedHooks(Map<Harness, EvaluatorMetadata> evaluators,
		List<HookRegistration> ownerHooks)
	{
		Map<Harness, List<HookRegistration>> hooks = new EnumMap<>(Harness.class);
		for (EvaluatorMetadata evaluator : evaluators.values())
		{
			List<HookRegistration> fallback = List.of();
			if (evaluator.harness() == scope.harness())
				fallback = ownerHooks;
			hooks.put(evaluator.harness(),
				evaluator.observations().map(EvaluatorObservations::hookRegistrations).orElse(fallback));
		}
		return hooks;
	}

	/**
	 * Reports the files each evaluator loads, labeled where a proposal covers multiple harnesses.
	 *
	 * @param root the project root
	 * @param bases the owner's trial plugin bases
	 * @param evaluators the observations of explicitly assigned evaluators
	 * @return the loaded sources of the proposal
	 * @throws IOException if the owner's loading settings cannot be read
	 */
	private List<DerivedSource> proposalSources(Path root, List<PluginBase> bases,
		Map<Harness, EvaluatorMetadata> evaluators) throws IOException
	{
		if (evaluators.isEmpty())
			return scope.sessions().loadedSources(root, bases).trials();
		List<DerivedSource> sources = new ArrayList<>();
		for (EvaluatorMetadata evaluator : new EnumMap<>(evaluators).values())
		{
			for (DerivedSource source : evaluator.loadedSources())
			{
				sources.add(new DerivedSource(source.path(),
					evaluator.harness().productName() + " loads this source. " + source.rule()));
			}
		}
		return sources;
	}

	/**
	 * Runs the hooks that run before the model acts, once as a trial runs them and once as the user's sessions run
	 * them, and stores which hooks print different output.
	 *
	 * @param root      the project root
	 * @param campaign  the campaign
	 * @param fork      the campaign's fork
	 * @param incumbent the incumbent's overlay on the fork
	 * @param staged    a staged source that holds every file of the fork
	 * @param loaded    the prompt files that load in trials
	 * @param evaluators the current native observations for explicitly selected evaluators
	 * @return the reason to refuse the campaign if a hook succeeds in the user's sessions and fails in trials
	 * @throws IOException          if a source cannot be staged or copied, a hook cannot be started or the result
	 *                              cannot be stored
	 * @throws InterruptedException if the thread is interrupted while it waits for a hook
	 */
	private Optional<String> checkHooks(Path root, Campaign campaign, ForkView fork, Overlay incumbent, Path staged,
		List<DerivedSource> loaded, Map<Harness, EvaluatorMetadata> evaluators) throws IOException, InterruptedException
	{
		Path directory = campaignDirectory(campaign.header().id());
		HookReportFile file = new HookReportFile(directory, scope.json(), scope.fileReplacer());
		Set<Harness> selected = campaign.header().evaluationHarnesses();
		Map<Harness, List<PluginBase>> plugins = evaluatorBases(evaluatorPlugins(campaign), fork.workspace());
		Map<Harness, HookReport> reports = new EnumMap<>(Harness.class);
		Map<Harness, List<String>> unapproved = new EnumMap<>(Harness.class);
		List<String> failures = new ArrayList<>();
		Path trialSource = null;
		for (Harness evaluator : new TreeSet<>(selected))
		{
			List<PluginBase> bases = plugins.getOrDefault(evaluator, List.of());
			HookCheck.InvocationSource hooks = hookInvocations(evaluator, root, bases);
			Optional<AbstractOptimizerCommandScope.HookPairResolver> nativeComparison =
				scope.nativeHookComparison(evaluator, root, HookCheck.PROMPT);
			Optional<HookCheck.PeerComparator> peerComparison = scope.peerHookComparison(evaluator);
			boolean inspectPlugins = (nativeComparison.isPresent() || peerComparison.isPresent()) && !bases.isEmpty();
			if (!inspectPlugins && hooks.read(staged, Optional.empty()).isEmpty())
			{
				reports.put(evaluator, HookReport.NONE);
				continue;
			}
			if (nativeComparison.isEmpty() && peerComparison.isEmpty())
			{
				Optional<String> refused = scope.hookComparisonRefusal(evaluator);
				if (refused.isPresent())
					return refused;
			}
			if (trialSource == null)
				trialSource = fork.workspace().stage(incumbent, Set.of(),
					hookSourceSelection(root, campaign, loaded, evaluators));
			List<TrialPluginInput> inputs = new ArrayList<>();
			if (inspectPlugins)
			{
				for (PluginBase base : bases)
				{
					TrialPlugin plugin = base.plugin();
					Optional<String> id = Optional.empty();
					if (plugin.source() instanceof TrialPlugin.Installed)
						id = Optional.of(plugin.name());
					inputs.add(new TrialPluginInput(
						fork.workspace().pluginCopy(plugin, trialSource, PluginRemovals.NONE), id));
				}
			}
			HookCheck.Result result;
			if (peerComparison.isPresent())
				result = HookCheck.runPeer(peerComparison.orElseThrow(), trialSource, staged, inputs, directory);
			else
			{
				AbstractOptimizerCommandScope.HookPairResolver pairs =
					nativeComparison.orElseGet(() -> HookCheck.matchingDeclarations(hooks));
				result = HookCheck.run(pairs, scope::startHookProcess, trialSource, staged, inputs, directory);
			}
			reports.put(evaluator, result.report());
			result.unapproved().ifPresent(values -> unapproved.put(evaluator, values));
			for (String failure : result.failures())
			{
				if (evaluators.isEmpty())
					failures.add(failure);
				else
					failures.add(evaluator.productName() + ": " + failure);
			}
		}
		if (evaluators.isEmpty())
			file.write(reports.get(scope.harness()), Optional.ofNullable(unapproved.get(scope.harness())));
		else
			file.writeByEvaluator(reports, unapproved);
		if (failures.isEmpty())
			return Optional.empty();
		return Optional.of("These hooks succeed in your sessions but fail in a trial's workspace, which holds only " +
			"the files that trials receive: " + String.join("; ", failures) + ". Change each hook so that " +
			"it reads only files that trials receive, such as the prompt system's files, then run `start` again.");
	}

	/**
	 * Resolves hook invocations in the selected evaluator's native configuration.
	 *
	 * @param evaluator the selected harness
	 * @param root the original project
	 * @param plugins the selected evaluator's frozen plugin bases
	 * @return a reader for the two copies of the project
	 */
	private HookCheck.InvocationSource hookInvocations(Harness evaluator, Path root, List<PluginBase> plugins)
	{
		if (evaluator == scope.harness())
			return (project, home) -> scope.sessions().earlyHooks(project, plugins, home, HookCheck.PROMPT);
		return (project, home) -> PeerEvaluator.earlyHooks(scope, evaluator, root, project, plugins, home);
	}

	/**
	 * Returns the common set of trial inputs available before the contract author runs.
	 *
	 * @param root the original project
	 * @param campaign the campaign being prepared
	 * @param loaded the prompt files observed by the selected evaluators
	 * @param evaluators the current native observations for explicitly selected evaluators
	 * @return the trial source selection to compare against the complete staged fork
	 */
	private SourceSelection hookSourceSelection(Path root, Campaign campaign, List<DerivedSource> loaded,
		Map<Harness, EvaluatorMetadata> evaluators)
	{
		Set<Path> sources = new LinkedHashSet<>();
		sources.add(relativeTarget(campaign));
		for (Path path : derivedPaths(root, loaded))
		{
			if (!path.isAbsolute())
				sources.add(path);
		}
		Set<Path> loaders = new HashSet<>(scope.loaderConfiguration());
		for (EvaluatorMetadata evaluator : evaluators.values())
			loaders.addAll(evaluator.loaderFiles());
		return TrialSelection.base(campaign.header().evaluationHarnesses(), sources, loaders, sourcePlugins(campaign));
	}

	/**
	 * Runs the guard pilot of a proposal, reusing the stored pilot of each prompt test that it still describes, then
	 * presents the proposal with the pilot's results. The pilot's control trials install the campaign's trial
	 * plugins. The fork first records the proposal's derived prompt sources, so the pilot's trials see the files that
	 * formal trials will see. Before confirmation, the fork also leaves out those that an earlier proposal needed and
	 * this one does not. After confirmation, nothing leaves the fork, and the campaign stays on its current fork until
	 * the user confirms the revision.
	 * <p>
	 * The caller holds the campaign's write lock, so no other process writes the campaign meanwhile.
	 *
	 * @param root the project root
	 * @param store the store
	 * @param campaign the campaign
	 * @param fork the campaign's fork
	 * @param derived the proposal
	 * @param sliceStart when the command started, which bounds the guard pilot that the command runs
	 * @return the result, which presents no proposal and is {@code running} if the command's slice ended before its
	 *   pilot did
	 * @throws IOException if the host's login, the stored pilot or the workspace cannot be read, or the pilot or the
	 *   campaign cannot be stored
	 * @throws InterruptedException if the thread is interrupted while it waits for a pilot session
	 */
	private CommandResult present(Path root, CampaignStore store, Campaign campaign, ForkView fork,
		DerivedProposal derived, Instant sliceStart)
		throws IOException, InterruptedException
	{
		Set<Path> participants = participants(root, derived);
		if (campaign.isConfirmed())
		{
			// The confirmed campaign keeps its fork until the user confirms the revision that needs a larger one
			if (!unheld(fork, participants).isEmpty())
				fork = fork.extend(root, participants);
		}
		else
		{
			ForkView selected = fork.selectParticipants(root, participants);
			if (!selected.inventory().equals(fork.inventory()))
				campaign = rebind(store, campaign, selected.inventory());
			fork = selected;
		}

		PluginCheck plugin = pluginCheck(root, campaign, fork);
		Map<String, PilotResult> pilots = pilot(root, campaign, fork, derived, plugin,
			new PilotSlice(scope.clock(), runSettings(), runInterrupts(), sliceStart));
		if (background && stopRequested())
		{
			campaign = Transitions.apply(campaign, new CampaignEvent.Cancel());
			store.replaceSnapshot(campaign);
			return report(root, campaign, null,
				"The background driver was cancelled; its completed pilot trials are retained.");
		}
		Optional<String> pause = GuardPilot.pauseReason(pilots);
		campaign = settlePilotPause(store, campaign, pause);
		// A proposal whose pilot did not finish cannot be confirmed, so the user first chooses to retry or to stop
		if (pause.isPresent())
			return report(root, campaign, null, pilotPausedNotice(campaign));
		// The slice ended with trials to run, so the host agent repeats `start`, which runs them
		if (GuardPilot.endedBySlice(pilots))
			return pilotContinues(root, campaign, pilots);
		Proposal proposal = proposal(root, campaign, fork, derived, pilots, plugin);
		String unfinished = null;
		if (pilots.values().stream().anyMatch(pilot -> pilot.stopReason() != null))
		{
			// An interrupted pilot is unfinished: the next `start` runs the trials that it has not decided
			unfinished = "The proposal cannot be confirmed until its guard pilot finishes. Run `start` again to run " +
				"the pilot's remaining trials, which keeps the trials it decided.";
		}
		return report(root, campaign, proposal.render(), unfinished);
	}

	/**
	 * Reports a campaign whose guard pilot the command's slice left with trials to run. The campaign is
	 * {@code running}, the result presents no proposal, and its summary says how many of the pilot's trials are decided
	 * and that running {@code start} again continues the pilot.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param pilots the pilot of each prompt test that has one, by prompt test
	 * @return the result
	 * @throws IOException if the fork or the shared capacity hold cannot be read
	 */
	private CommandResult pilotContinues(Path root, Campaign campaign, Map<String, PilotResult> pilots)
		throws IOException
	{
		String progress = pilotProgress(pilots, "Run `start` again with the target prompt file") +
			" The proposal is shown once the pilot ends.";
		return report(root, campaign, null, null, true).withNotices(List.of(progress));
	}

	/**
	 * Returns how far a guard pilot that a slice left unfinished is, and the command that continues it.
	 *
	 * @param pilots the pilot of each prompt test that has one, by prompt test
	 * @param again the instruction to run the command that continues the pilot, without the final period
	 * @return the notice
	 */
	private static String pilotProgress(Map<String, PilotResult> pilots, String again)
	{
		int decided = 0;
		for (PilotResult pilot : pilots.values())
			decided += pilot.trials().size();
		return "The guard pilot has decided " + decided + " of " + pilots.size() * GuardPilot.TRIALS + " trials. " +
			again + " to continue it, which keeps the trials it decided.";
	}

	/**
	 * Records that the guard pilot paused, or that it no longer pauses. The campaign's earlier pilot pause is answered
	 * by this pilot's result, so it is cleared first.
	 * <p>
	 * The caller holds the campaign's write lock, so no other process writes the campaign meanwhile.
	 *
	 * @param store the store
	 * @param campaign the campaign
	 * @param pause why the pilot paused, or empty if no pilot paused
	 * @return the campaign after the pilot's result
	 * @throws IOException if the campaign cannot be stored
	 */
	private Campaign settlePilotPause(CampaignStore store, Campaign campaign, Optional<String> pause)
		throws IOException
	{
		boolean answered = campaign.pending() instanceof PendingDecision.PilotFailure;
		if (!answered && pause.isEmpty())
			return campaign;
		Campaign settled = campaign;
		if (answered)
			settled = Transitions.apply(settled, new CampaignEvent.Choose(Choice.RETRY, 0, 0));
		if (pause.isPresent())
			settled = Transitions.apply(settled, new CampaignEvent.PilotFailed(pause.get()));
		store.replaceSnapshot(settled);
		return settled;
	}

	/**
	 * Returns the notice that tells the user how to answer a paused guard pilot.
	 *
	 * @param campaign the campaign
	 * @return the notice
	 */
	private static String pilotPausedNotice(Campaign campaign)
	{
		String id = campaign.header().id();
		return "The proposal is not shown, and cannot be confirmed, because its guard pilot paused. To run the " +
			"pilot's remaining trials, which keeps the trials it decided, run `choose " + id + " " +
			new ChoiceItem(Choice.RETRY, 0, false, 0).id() + "`. To stop the campaign, run `choose " + id + " " +
			new ChoiceItem(Choice.STOP, 0, false, 0).id() + "`.";
	}

	/**
	 * Runs the guard pilot of a proposal, reusing the stored pilot of each prompt test that it still describes, and
	 * stores the result after every decided trial. The pilot's control trials install the campaign's trial plugins.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param fork the fork that holds the proposal's derived prompt sources
	 * @param derived the proposal
	 * @param plugin the check of the campaign's trial plugins
	 * @param slice the time bound of the command that runs the pilot, or {@code null} if the pilot runs until every
	 *   trial ends or it stops
	 * @return the pilot of each prompt test, by prompt test, or none when a scope condition identifies an ineligible
	 *   trial effect or control trials cannot remove the dependents
	 * @throws IOException if the stored pilot or the workspace cannot be read, or the pilot cannot be stored
	 * @throws InterruptedException if the thread is interrupted while it waits for a pilot session
	 */
	private Map<String, PilotResult> pilot(Path root, Campaign campaign, ForkView fork, DerivedProposal derived,
		PluginCheck plugin, PilotSlice slice) throws IOException, InterruptedException
	{
		boolean hasIneligibleTrialEffect = derived.scopeConditions().stream().
			anyMatch(condition -> condition.outcome() == Outcome.INELIGIBLE_TRIAL_EFFECT);
		if (hasIneligibleTrialEffect)
			return Map.of();
		// Control trials cannot run without dependents that they cannot remove
		if (!controlRefusals(campaign, fork, derived, plugin).isEmpty())
			return Map.of();
		PilotFile file = pilotFile(campaign);
		Overlay control = pilotControl(campaign, fork, derived);
		GuardPilot pilot = guardPilot(campaign, fork);
		Set<Path> excluded = PromptTestLocations.excluded(derived.contract(), relativeTarget(campaign), root);
		// Each decided trial is stored as soon as it is decided, so that a command that ends before the pilot does
		// loses only the trials in flight
		SourceSelection selection = trialSelection(root, campaign, derived);
		Map<String, PilotResult> pilots;
		if (plugin.byEvaluator().isEmpty())
		{
			pilots = pilot.run(derived.contract(), plugin.plugins(), control, excluded, selection, file.read(),
				scope.random(), file::write, slice);
		}
		else
		{
			pilots = pilot.run(derived.contract(), evaluatorPlugins(campaign), control, excluded, selection,
				file.read(), scope.random(), file::write, slice);
		}
		file.write(pilots);
		return pilots;
	}

	/**
	 * Returns the control overlay of the exact sources the proposal's pilot sees.
	 *
	 * @param campaign the campaign
	 * @param fork the proposal's frozen sources
	 * @param derived the proposal
	 * @return the control overlay
	 * @throws IOException if a frozen source cannot be read
	 */
	private Overlay pilotControl(Campaign campaign, ForkView fork, DerivedProposal derived) throws IOException
	{
		return ControlSource.of(fork.workspace(), fork.inventory(), relativeTarget(campaign),
			derived.contract().projectRemovals(), scope.json());
	}

	/**
	 * Binds the pilot to the owning judge and each selected evaluator's trial port.
	 *
	 * @param campaign the campaign
	 * @param fork the proposal's frozen sources
	 * @return the guard pilot
	 * @throws IOException if the trial ports or judge prompt cannot be read
	 */
	private GuardPilot guardPilot(Campaign campaign, ForkView fork) throws IOException
	{
		SessionPort owner = sessions(campaign);
		return new GuardPilot(owner, trialSessions(campaign, owner), fork.workspace(), relativeTarget(campaign),
			judgePrompt(campaign), schema, scope.json(), campaign.ceiling());
	}

	/**
	 * Returns the files of a candidate that every trial of a proposal receives.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param derived the proposal
	 * @return the selection
	 */
	private SourceSelection trialSelection(Path root, Campaign campaign, DerivedProposal derived)
		throws IOException
	{
		return TrialSelection.base(campaign.header().evaluationHarnesses(), promptSources(root, campaign, derived),
			loaderFiles(campaign), derived.contract(), sourcePlugins(campaign));
	}

	/**
	 * Binds a campaign that waits for its first confirmed contract to the incumbent of its fork's changed files. The
	 * incumbent overlay is saved for the new files before the campaign refers to it.
	 * <p>
	 * The caller holds the campaign's write lock, so no other process writes the campaign meanwhile.
	 *
	 * @param store the store
	 * @param campaign the campaign
	 * @param inventory the fork's files
	 * @return the rebound campaign
	 * @throws IOException if the overlay or the campaign cannot be stored
	 */
	private Campaign rebind(CampaignStore store, Campaign campaign, FileInventory inventory)
		throws IOException
	{
		Overlay incumbent = Overlay.of(inventory);
		new FileCandidateOverlays(campaignDirectory(campaign.header().id()), inventory, scope.json(),
			scope.fileReplacer()).save(incumbent);
		Campaign rebound = campaign.withIncumbent(incumbent.identity());
		store.replaceSnapshot(rebound);
		return rebound;
	}

	/**
	 * Returns the file that holds a campaign's guard pilot.
	 *
	 * @param campaign the campaign
	 * @return the file
	 * @throws IOException if the host session's CAT session directory cannot be opened
	 */
	private PilotFile pilotFile(Campaign campaign) throws IOException
	{
		return new PilotFile(heldOutDirectory(campaign.header().id()), scope.json(), scope.fileReplacer());
	}

	/**
	 * Completes a derived proposal for presentation and confirmation.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param fork the campaign's fork
	 * @param derived the proposal
	 * @param pilots the guard pilot of each prompt test that has one, by prompt test
	 * @param plugin the check of the campaign's trial plugins
	 * @return the proposal
	 * @throws IOException if the host's login or the target's certification record cannot be read
	 */
	private Proposal proposal(Path root, Campaign campaign, ForkView fork, DerivedProposal derived,
		Map<String, PilotResult> pilots, PluginCheck plugin) throws IOException
	{
		FileInventory inventory = fork.inventory();
		Authority authority = authority(root, campaign, inventory);
		LinkReport links = LinkReport.of(inventory, authority);
		CertificationStatus incumbent = null;
		CertificationHistory history = history(root, relativeTarget(campaign));
		if (!history.entries().isEmpty())
		{
			// Compare each harness's latest entry with the fork's files and the campaign's known conditions
			incumbent = certificationStatus(history, inventory::entry, campaign.identity(),
				record -> derived.contract().minimumPassRate());
		}
		SourceSelection selection = trialSelection(root, campaign, derived);
		List<String> trialFiles = new ArrayList<>();
		for (Path file : inventory.entries().keySet())
		{
			if (!PortablePaths.isRooted(file) && selection.selects(file))
				trialFiles.add(PortablePaths.text(file));
		}
		return Proposal.of(derived, campaign, links, relativeTarget(campaign), scope.sessions().hostLogin(),
			scope.calibrator(), schema, incumbent, pilots, plugin, controlRefusals(campaign, fork, derived, plugin),
			trialFiles, new HookReportFile(campaignDirectory(campaign.header().id()), scope.json(),
			scope.fileReplacer()).read(), ReferencedFiles.of(derived.contract().referencedFiles(), inventory,
			authority));
	}

	/**
	 * Checks that control trials can remove the dependents of the target that a proposal lists: from the fork's files,
	 * and from the base of each trial plugin that the project builds.
	 *
	 * @param campaign the campaign
	 * @param fork the campaign's fork
	 * @param derived the proposal
	 * @param plugin the check of the campaign's trial plugins
	 * @return the reasons that block confirmation; empty if control trials can remove every dependent
	 * @throws IOException if a file of the fork or of the plugin's base cannot be read
	 */
	private List<String> controlRefusals(Campaign campaign, ForkView fork, DerivedProposal derived,
		PluginCheck plugin) throws IOException
	{
		Contract contract = derived.contract();
		if (contract.dependents().isEmpty())
			return List.of();
		Path target = relativeTarget(campaign);
		List<String> problems = new ArrayList<>(ControlSource.problems(fork.workspace(), fork.inventory(), target,
			contract.projectRemovals(), scope.json()));
		Map<Harness, PluginCheck> checks = plugin.byEvaluator();
		if (checks.isEmpty())
			checks = Map.of(campaign.header().harness(), plugin);
		for (Map.Entry<Harness, PluginCheck> entry : checks.entrySet())
		{
			for (TrialPlugin trialPlugin : entry.getValue().plugins())
			{
				if (trialPlugin.isProjectBuilt())
				{
					problems.addAll(ControlSource.pluginProblems(trialPlugin,
						fork.workspace().pluginBase(trialPlugin.baseDigest()), target,
						contract.pluginRemovals(trialPlugin, entry.getKey()), scope.json()));
				}
			}
		}
		for (Dependent dependent : contract.dependents())
		{
			if (dependent.location() == Dependent.Location.PLUGIN)
				controlPluginRefusal(dependent, checks).ifPresent(problems::add);
		}
		if (problems.isEmpty())
			return List.of();
		return List.of("Control trials cannot remove the dependents of the target that the proposal lists: " +
			String.join("; ", problems) + ". Run revise with feedback that corrects the dependents.");
	}

	/**
	 * Validates that a packaged dependent identifies the intended project-built trial plugin.
	 *
	 * @param dependent a packaged dependent
	 * @param checks the selected evaluators' plugin checks
	 * @return why the reference cannot be applied to a control trial, if applicable
	 */
	private static Optional<String> controlPluginRefusal(Dependent dependent, Map<Harness, PluginCheck> checks)
	{
		List<TrialPlugin> built = checks.entrySet().stream().
			filter(entry -> dependent.evaluator().isEmpty() || dependent.evaluator().get() == entry.getKey()).
			flatMap(entry -> entry.getValue().plugins().stream()).filter(TrialPlugin::isProjectBuilt).
			filter(plugin -> plugin.name().equals(dependent.plugin())).distinct().toList();
		String subject = "the dependent " + PortablePaths.text(dependent.path()) + " lies in the plugin " +
			dependent.plugin();
		if (dependent.evaluator().isPresent())
			subject += " for " + dependent.evaluator().get().productName();
		if (built.isEmpty())
			return Optional.of(subject + ", which is not a trial plugin that the project builds");
		if (built.size() > 1)
			return Optional.of(subject + ", which has different builds across evaluators. Name its evaluator");
		return Optional.empty();
	}

	/**
	 * Checks a campaign's trial plugins against the fork's files and the plugins' bases: the prompt files that no
	 * mapping covers, the mapped files that differ from the base's, the files that load in production but not in
	 * trials, and the plugins' hooks whose trial approval could not be verified.
	 *
	 * @param root the project root
	 * @param campaign the campaign, whose trial plugins are recorded
	 * @param fork the campaign's fork
	 * @return the check
	 * @throws IOException if a plugin's base, a hook declaration, the stored proposal or a file that loads cannot be
	 *   read
	 */
	private PluginCheck pluginCheck(Path root, Campaign campaign, ForkView fork) throws IOException
	{
		Map<Harness, EvaluatorMetadata> reports = reports(campaign);
		if (reports.isEmpty())
		{
			return pluginCheck(root, campaign, fork, scope.harness(), campaign.plugins().orElseThrow(),
				Optional.empty());
		}
		Map<Harness, PluginCheck> checks = new EnumMap<>(Harness.class);
		for (Map.Entry<Harness, List<TrialPlugin>> entry : evaluatorPlugins(campaign).entrySet())
		{
			Harness evaluator = entry.getKey();
			checks.put(evaluator, pluginCheck(root, campaign, fork, evaluator, entry.getValue(),
				Optional.of(reports.get(evaluator))));
		}
		return PluginCheck.forEvaluators(checks);
	}

	/**
	 * Checks one evaluator's plugin coverage using only its native loading observations and frozen bases.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param fork its source and frozen bases
	 * @param evaluator the harness that uses these plugins
	 * @param plugins the harness's ordered plugins
	 * @param observed the persisted native loader evidence for a shared evaluator
	 * @return the evaluator's coverage
	 * @throws IOException if the recorded source, plugin base or loader data cannot be read
	 */
	private PluginCheck pluginCheck(Path root, Campaign campaign, ForkView fork, Harness evaluator,
		List<TrialPlugin> plugins, Optional<EvaluatorMetadata> observed) throws IOException
	{
		FileInventory inventory = fork.inventory();
		SortedSet<Path> promptFiles = authority(root, campaign, inventory).promptFiles(inventory);
		List<PluginBase> bases = bases(plugins, fork.workspace());
		Optional<EvaluatorObservations> observations = observed.flatMap(EvaluatorMetadata::observations);
		LoadedSources loaded;
		if (observations.isPresent())
		{
			EvaluatorObservations facts = observations.get();
			loaded = new LoadedSources(List.of(), facts.projectSources(), facts.productionOnly(), facts.homeOnly(),
				facts.unreachable());
		}
		else if (evaluator == scope.harness())
			loaded = scope.sessions().loadedSources(root, bases);
		else
			loaded = new LoadedSources(List.of(), observed.orElseThrow().loadedSources(), List.of(), List.of(),
				List.of());
		Map<String, FileInventory> baseFiles = new HashMap<>();
		Optional<List<String>> nativeHooks = new HookReportFile(campaignDirectory(campaign.header().id()), scope.json(),
			scope.fileReplacer()).unapproved(evaluator);
		List<String> hooks = new ArrayList<>(nativeHooks.orElseGet(() ->
			observations.map(EvaluatorObservations::unapprovedHooks).orElse(List.of())));
		Map<String, Path> baseDirectories = new HashMap<>();
		for (PluginBase base : bases)
		{
			String name = base.plugin().name();
			baseDirectories.put(name, base.directory());
			if (base.plugin().isProjectBuilt())
				baseFiles.put(name, InventoryScanner.scan(base.directory(), Set.of()));
			if (nativeHooks.isEmpty() && observations.isEmpty() && evaluator == scope.harness())
			{
				for (String hook : scope.sessions().unapprovedHooks(base.directory()))
					hooks.add(name + " " + hook);
			}
		}
		Set<Path> projectSources;
		if (observations.isEmpty() && evaluator == scope.harness())
			projectSources = loadsFromProject(root, bases, promptFiles);
		else
			projectSources = projectSources(evaluator, loaded.trials(), promptFiles);
		PluginMapping.Coverage coverage = PluginMapping.check(inventory, promptFiles, projectSources, plugins, baseFiles);
		return new PluginCheck(plugins, coverage.uncovered(), coverage.differing(), loaded.productionOnly(),
			loaded.homeOnly(), hooks, baseDirectories);
	}

	/**
	 * Reads the stored proposal.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @return the proposal, or empty if none is stored
	 * @throws IOException if the stored proposal cannot be read or no longer validates against the project
	 */
	private Optional<DerivedProposal> stored(Path root, Campaign campaign) throws IOException
	{
		try
		{
			return validatedProposal(root, campaign);
		}
		catch (InvalidProposalException e)
		{
			throw new IOException("The stored proposal no longer validates against the project: " +
				String.join("; ", e.problems()) + ". Run `start` to derive a new proposal.", e);
		}
	}

	/**
	 * Reads the stored proposal and validates it against the project.
	 *
	 * @param root     the project root
	 * @param campaign the campaign
	 * @return the proposal, or empty if none is stored
	 * @throws IOException              if the stored proposal cannot be read
	 * @throws InvalidProposalException if the stored proposal no longer validates against the project. The exception
	 *                                  lists every problem found.
	 */
	private Optional<DerivedProposal> validatedProposal(Path root, Campaign campaign)
		throws IOException, InvalidProposalException
	{
		Optional<ProposalFile.Stored> stored = storedProposal(campaign);
		if (stored.isEmpty())
			return Optional.empty();
		ProposalFile.Stored proposal = stored.get();
		return Optional.of(proposal.restore(contractAuthor(root, campaign).proposal(proposal.derivation(),
			proposal.draft(), proposal.observedSources(), proposal.profiles(), proposal.salt())));
	}

	/**
	 * Reads the stored proposal without validating it.
	 *
	 * @param campaign the campaign
	 * @return the proposal, or empty if none is stored
	 * @throws IOException if the stored proposal cannot be read
	 */
	private Optional<ProposalFile.Stored> storedProposal(Campaign campaign) throws IOException
	{
		Optional<ProposalFile.Stored> stored = new ProposalFile(heldOutDirectory(campaign.header().id()), scope.json(),
			scope.fileReplacer()).read();
		if (stored.isPresent())
		{
			Set<Harness> selected = campaign.header().evaluationHarnesses();
			Set<Harness> observed = stored.get().evaluators().keySet();
			if (!selected.equals(observed) && !(observed.isEmpty() && selected.equals(Set.of(campaign.header().harness()))))
				throw new IOException("The stored proposal does not hold evidence for " + evaluatorNames(selected));
		}
		return stored;
	}

	/**
	 * Returns the staged observations needed by a shared evaluation.
	 *
	 * @param campaign the campaign
	 * @return the evaluator observations, or none when only the owner evaluates
	 * @throws IOException if the stored evidence cannot be read
	 */
	private Map<Harness, EvaluatorMetadata> reports(Campaign campaign) throws IOException
	{
		if (campaign.header().evaluationHarnesses().equals(Set.of(campaign.header().harness())))
			return Map.of();
		return storedProposal(campaign).map(ProposalFile.Stored::evaluators).orElse(Map.of());
	}

	/**
	 * Verifies the proposed measurement conditions before launching more work.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param fork the existing fork
	 * @throws CommandRefusedException if an evaluator cannot launch or its observations changed
	 * @throws IOException if the stored evidence or staged source cannot be read
	 * @throws InterruptedException if the evaluator probe is interrupted
	 */
	private void verifyEvaluatorConditions(Path root, Campaign campaign, ForkView fork)
		throws CommandRefusedException, IOException, InterruptedException
	{
		Map<Harness, EvaluatorMetadata> pinned = reports(campaign);
		if (pinned.isEmpty())
			return;
		Path directory = campaignDirectory(campaign.header().id());
		Overlay incumbent = new FileCandidateOverlays(directory, fork.inventory(), fork::version, scope.json(),
			scope.fileReplacer()).require(campaign.incumbent().snapshot());
		Path staged = fork.workspace().stage(incumbent, Set.of());
		Map<Harness, EvaluatorMetadata> current = evaluatorReports(root, staged, relativeTarget(campaign), pinned.keySet(),
			evaluatorBases(evaluatorPlugins(campaign), fork.workspace()));
		for (Map.Entry<Harness, EvaluatorMetadata> entry : current.entrySet())
		{
			Harness evaluator = entry.getKey();
			EvaluatorMetadata observed = entry.getValue();
			if (!observed.matchesPinned(pinned.get(evaluator)))
			{
				throw new CommandRefusedException(evaluator.productName() +
					" no longer supplies the loader, profiles or runtime recorded in this proposal. Restore its " +
					"recorded configuration before continuing.", null,
					CommandRefusedException.Reason.EVALUATOR_CONDITIONS_CHANGED);
			}
			if (evaluator != scope.harness())
				requirePeerPort(observed);
		}
	}

	/**
	 * Returns the selected trial ports with the campaign's approved extra permissions.
	 *
	 * @param campaign the campaign
	 * @param owner the owner's session port
	 * @return the evaluator trial ports, or empty for the ordinary owner-only route
	 * @throws IOException if a required peer port cannot be built
	 */
	private Map<Harness, SessionPort> trialSessions(Campaign campaign, SessionPort owner) throws IOException
	{
		Map<Harness, SessionPort> sessions = new EnumMap<>(Harness.class);
		for (EvaluatorMetadata metadata : reports(campaign).values())
		{
			Harness evaluator = metadata.harness();
			SessionPort port = owner;
			if (evaluator != scope.harness())
			{
				port = new ExtraPermissionSessions(scope.peerTrialSessions(evaluator, metadata),
					campaign.header().extraPermissions());
			}
			sessions.put(evaluator, port);
		}
		return sessions;
	}

	/**
	 * Returns the loader files that the campaign requires, including those of each selected evaluator.
	 *
	 * @param campaign the campaign
	 * @return the files that configure loading
	 * @throws IOException if the evaluator evidence cannot be read
	 */
	private Set<Path> loaderFiles(Campaign campaign) throws IOException
	{
		Set<Path> files = new HashSet<>(scope.loaderConfiguration());
		for (EvaluatorMetadata evaluator : reports(campaign).values())
			files.addAll(evaluator.loaderPaths().orElseGet(evaluator::loaderFiles));
		return files;
	}

	/**
	 * Binds the confirmed contract's cell addresses to the evidence that the user reviewed.
	 *
	 * @param proposal the displayed proposal
	 * @param certificationScope the chosen certification scope
	 * @param evaluators the evaluator runtime and loader evidence
	 * @param campaign the campaign with pinned trial plugins
	 * @param workspace the frozen plugin bases
	 * @return the confirmation events
	 * @throws IOException if a plugin manifest cannot be read
	 */
	private List<CampaignEvent> confirmation(Proposal proposal, CertificationScope certificationScope,
		Map<Harness, EvaluatorMetadata> evaluators, Campaign campaign, WorkspacePort workspace) throws IOException
	{
		List<CampaignEvent> events = proposal.confirmation(certificationScope, scope.random().nextLong());
		if (evaluators.isEmpty())
			return events;
		List<CampaignEvent> scoped = new ArrayList<>();
		for (CampaignEvent event : events)
		{
			if (event instanceof CampaignEvent.Confirm confirm)
			{
				scoped.add(new CampaignEvent.Confirm(confirm.contract(), confirm.contractHash(), confirm.seed(),
					evaluatorEnvironment(confirm.contract(), evaluators, evaluatorPlugins(campaign), workspace)));
			}
			else
				scoped.add(event);
		}
		return scoped;
	}

	/**
	 * Waits for a session.
	 *
	 * @param handle the session
	 * @return the session's result
	 * @throws IOException if the session fails abnormally
	 * @throws InterruptedException if the thread is interrupted. The session is interrupted too.
	 */
	private SessionResult await(SessionHandle handle) throws IOException, InterruptedException
	{
		try
		{
			if (!background)
				return handle.completion().toCompletableFuture().get();
			Instant stopAt = null;
			while (true)
			{
				try
				{
					return handle.completion().toCompletableFuture().get(runSettings().pollInterval().toNanos(),
						TimeUnit.NANOSECONDS);
				}
				catch (TimeoutException _)
				{
					if (stopAt == null && stopRequested())
						stopAt = scope.clock().instant().plus(runSettings().grace());
					if (stopAt != null && !scope.clock().instant().isBefore(stopAt))
					{
						handle.interrupt();
						return new SessionResult.Interrupted(List.of());
					}
				}
			}
		}
		catch (ExecutionException e)
		{
			throw new IOException("The session failed: " + e.getCause(), e.getCause());
		}
		catch (InterruptedException e)
		{
			handle.interrupt();
			throw e;
		}
	}

	/**
	 * Builds a status result.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param proposal the rendered proposal, which the result names the file of, or {@code null}
	 * @param message a notice, or {@code null}
	 * @return the result
	 * @throws IOException if the fork or the shared capacity hold cannot be read, or the proposal's file cannot be
	 *   written
	 */
	private CommandResult report(Path root, Campaign campaign, String proposal, String message) throws IOException
	{
		return report(root, campaign, proposal, message, false);
	}

	/**
	 * Builds a status result.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param proposal the rendered proposal, which the result names the file of, or {@code null}
	 * @param message a notice, or {@code null}
	 * @param pilotUnfinished {@code true} if a command left the campaign's guard pilot with trials to run, which makes
	 *   the campaign {@code running}
	 * @return the result
	 * @throws IOException if the fork or the shared capacity hold cannot be read, or the proposal's file cannot be
	 *   written
	 */
	private CommandResult report(Path root, Campaign campaign, String proposal, String message,
		boolean pilotUnfinished) throws IOException
	{
		boolean links = false;
		Path directory = campaignDirectory(campaign.header().id());
		if (scope.forks().exists(directory))
		{
			ForkView fork = scope.forks().open(root, directory);
			FileInventory inventory = fork.inventory();
			links = !LinkReport.of(inventory, authority(root, campaign, inventory)).entries().isEmpty();
		}
		Instant sharedHoldUntil = scope.sessions().sharedHoldUntil(scope.clock().instant()).orElse(null);
		ReportEvidence evidence = new ReportEvidence(List.of(), links, false, null).
			withSharedHoldUntil(sharedHoldUntil).withCertifiedEnvironment(deliveredEnvironment(root, campaign));
		if (pilotUnfinished)
			evidence = evidence.withUnfinishedPilot();
		Path proposalFile = null;
		if (proposal != null)
			proposalFile = new ProposalTextFile(directory, scope.fileReplacer()).write(proposal);
		CommandResult result = new CommandResult(StatusReport.of(campaign, evidence), campaign.header().harness(),
			proposalFile, null, message);
		List<String> notices = new ArrayList<>();
		Optional<BackgroundDriver.Owner> owner =
			BackgroundDriver.owner(lockFile(campaign.header().id()), scope.fileLocks(), scope.json());
		if (!background)
			owner.ifPresent(driver -> notices.add("Background driver holds the campaign; started " +
			driver.started() + ". Use `status` for progress or `cancel` to stop it."));
		if (owner.isEmpty())
		{
			Path ended = lockFile(campaign.header().id()).resolveSibling("background-result.json");
			if (Files.isRegularFile(ended))
			{
				ObjectNode saved = (ObjectNode)
					scope.json().readTree(Files.readString(ended));
				result = new CommandResult(result.status(), null, result.harness(), result.proposalFile(),
					result.delivery(), result.message(), result.notices(), saved);
			}
		}
		if (owner.isEmpty() &&
			Files.isRegularFile(lockFile(campaign.header().id()).resolveSibling("background-driver.json")))
			notices.add("The recorded background driver is no longer alive. Its lock has no live holder; " +
				"use `run` to resume.");
		Path failure = noticeFailureFile(campaign.header().id());
		if (Files.isRegularFile(failure))
			notices.add(Files.readString(failure));
		return result.withNotices(notices);
	}

	/**
	 * Returns the environment that a campaign's delivered certification was measured in, read from the record that
	 * the delivery wrote to the target's certification history.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @return the environment, or {@code null} if the campaign delivered no certified candidate or the history holds
	 *   no record of the delivered attempt
	 * @throws IOException if the history or a prompt file cannot be read
	 */
	private CertifiedEnvironment deliveredEnvironment(Path root, Campaign campaign) throws IOException
	{
		DeliveryRequest delivery = campaign.delivery();
		if (delivery == null || delivery.label() != Outcome.CERTIFIED)
			return null;
		MeasurementIdentity measured = campaign.attempt(delivery.attempt()).identity();
		CertificationRecord recorded = null;
		for (CertificationRecord record : history(root, relativeTarget(campaign)).records())
		{
			if (record.certificationIndex() != delivery.attempt() || !record.identity().equals(measured))
				continue;
			if (record.snapshot().isEmpty())
				recorded = record;
			else if (record.snapshot().get().equals(delivery.candidate().snapshot()))
			{
				recorded = record;
				break;
			}
		}
		if (recorded == null)
			return null;
		Entries files = path -> InventoryScanner.entry(root, path);
		CertificationStatus status = certificationStatus(new CertificationHistory(List.of(recorded)), files,
			environment(files), certified -> certified.parameters().minimumPassRate());
		return CertifiedEnvironment.of(recorded, status.latest().getFirst().applicability().unchecked());
	}

	/**
	 * Loads a campaign.
	 *
	 * @param store the store
	 * @param id the campaign's identifier
	 * @param sameHarness {@code true} to refuse a campaign that the other harness started
	 * @return the campaign
	 * @throws CommandRefusedException if the campaign does not exist or the harness check fails
	 * @throws IOException if the store cannot be read
	 */
	private Campaign require(CampaignStore store, String id, boolean sameHarness)
		throws CommandRefusedException, IOException
	{
		Optional<Campaign> campaign = store.load(id);
		if (campaign.isEmpty())
		{
			Optional<SessionCampaign> other = loadFromOtherSession(id);
			if (other.isPresent())
				throw refused(new Registry.OwnedHeader(other.get().sessionId(), other.get().campaign().header()));
			throw new CommandRefusedException("No campaign has the identifier " + id + ".");
		}
		Header header = campaign.get().header();
		if (sameHarness && header.harness() != scope.harness())
			throw refused(new Registry.OwnedHeader(scope.sessionDirectory().sessionId(), header));
		return campaign.get();
	}

	/**
	 * Opens a campaign's fork.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @return the fork
	 * @throws CommandRefusedException if the campaign holds no fork
	 * @throws IOException if the fork cannot be read
	 */
	private ForkView openFork(Path root, Campaign campaign) throws CommandRefusedException, IOException
	{
		try
		{
			return scope.forks().open(root, campaignDirectory(campaign.header().id()));
		}
		catch (WorkspaceException e)
		{
			throw new CommandRefusedException("Campaign " + campaign.header().id() + " has no usable fork: " +
				e.getMessage(), e);
		}
	}

	/**
	 * Returns the judge protocol of a campaign, which writes each judge's evidence under the campaign's directory.
	 *
	 * @param campaign the campaign
	 * @return the protocol
	 * @throws IOException if the judge's directory cannot be created
	 */
	private JudgePrompt judgePrompt(Campaign campaign) throws IOException
	{
		Path judgeDirectory = campaignDirectory(campaign.header().id()).resolve("judge");
		Files.createDirectories(judgeDirectory);
		return new JudgePrompt(scope.json(), judgeDirectory);
	}

	/**
	 * Builds the driver's context.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param runningSlice whether the driver must open evaluator trial sessions
	 * @return the context
	 * @throws CommandRefusedException if the campaign holds no fork
	 * @throws IOException if the fork cannot be read
	 */
	private DriverContext context(Path root, Campaign campaign, boolean runningSlice)
		throws CommandRefusedException, IOException
	{
		Path directory = campaignDirectory(campaign.header().id());
		ForkView fork = openFork(root, campaign);
		FileInventory inventory = fork.inventory();
		CampaignStore store = scope.store();
		Authority authority = authority(root, campaign, inventory);
		Path triageDirectory = directory.resolve("triage");
		Files.createDirectories(triageDirectory);
		Path valuesDirectory = directory.resolve("values");
		Files.createDirectories(valuesDirectory);
		Set<Path> promptSources = promptSources(root, campaign);
		JudgePrompt judge = judgePrompt(campaign);
		TriagePrompt triage = new TriagePrompt(scope.json(), triageDirectory);
		PromptAuthorPrompt promptAuthor = new PromptAuthorPrompt(scope.json(), schema, promptSources,
			loaderFiles(campaign));
		ValuesPrompt values = new ValuesPrompt(scope.json(), schema, valuesDirectory,
			new SplittableRandom(scope.random().nextLong()));
		MeasurementIdentity known = driverEnvironment(campaign, fork);
		SessionPort owner = sessions(campaign);
		Map<Harness, SessionPort> evaluators = Map.of();
		if (runningSlice)
			evaluators = trialSessions(campaign, owner);
		return new DriverContext(scope.fileLocks(), store, owner, fork.workspace(), runInterrupts(),
			scope.clock(),
			new FileCandidateOverlays(directory, inventory, fork::version, scope.json(), scope.fileReplacer()), judge, triage,
			new ApproachRankingPrompt(scope.json(), triageDirectory), promptAuthor,
			values,
			new Delivery(store, fork.workspace(), scope.forks(), scope.json(), scope.fileReplacer(),
				authority.loaderConfiguration())::deliver,
			scope.calibrator(),
			new ProcessHandleTerminator(), new LeftoverNestedSessions(scope.sessions(), scope.json()), authority,
			promptSources, known, lockFile(campaign.header().id()), scope.random(),
			runSettings(), scope.json(), scope.profiles().candidateJudgeProfiles(), scope.judgeRecords(),
			evaluators,
			scope.temporaryDirectory());
	}

	/**
	 * Returns the driver's environmental conditions. A shared campaign stores each harness's inputs on its cells.
	 *
	 * @param campaign the campaign
	 * @param fork the fork's file inventory and plugin bases
	 * @return the driver environment
	 * @throws IOException if the loader inputs or the stored evaluator evidence cannot be read
	 */
	private MeasurementIdentity driverEnvironment(Campaign campaign, ForkView fork) throws IOException
	{
		if (campaign.header().evaluationHarnesses().equals(Set.of(campaign.header().harness())))
			return environment(fork.inventory()::entry);
		if (!campaign.isConfirmed())
			return campaignEnvironment();
		Map<Harness, EvaluatorMetadata> evaluators = reports(campaign);
		if (evaluators.isEmpty())
			throw new IOException("The confirmed shared campaign has no evaluator evidence");
		return evaluatorEnvironment(campaign.contract(), evaluators, evaluatorPlugins(campaign), fork.workspace());
	}

	/**
	 * Returns conditions that apply equally to each selected evaluator in the same CAT release.
	 *
	 * @return the common conditions
	 */
	private static MeasurementIdentity campaignEnvironment()
	{
		return new MeasurementIdentity(Map.of(MeasurementCondition.RUNNER_VERSION, PluginInfo.CAT_VERSION,
			MeasurementCondition.CAT_VERSION, PluginInfo.CAT_VERSION));
	}

	/**
	 * Projects proposal observations onto every configured and stress cell of the confirmed contract.
	 *
	 * @param contract the confirmed contract
	 * @param evaluators the evaluator observations
	 * @param pluginsByEvaluator the ordered frozen plugins by evaluator
	 * @param workspace the workspace that holds those bases
	 * @return the common conditions and the conditions assigned to each cell
	 * @throws IOException if a plugin manifest cannot be read
	 */
	private MeasurementIdentity evaluatorEnvironment(Contract contract, Map<Harness, EvaluatorMetadata> evaluators,
		Map<Harness, List<TrialPlugin>> pluginsByEvaluator, WorkspacePort workspace) throws IOException
	{
		Map<Harness, Map<MeasurementCondition, String>> conditions = new EnumMap<>(Harness.class);
		for (Map.Entry<Harness, EvaluatorMetadata> entry : evaluators.entrySet())
		{
			Harness evaluator = entry.getKey();
			List<TrialPlugin> plugins = pluginsByEvaluator.get(evaluator);
			Map<String, String> versions = pluginVersions(workspace, evaluator, plugins);
			Map<MeasurementCondition, String> local = new EnumMap<>(evaluatorConditions(entry.getValue()));
			local.putAll(MeasurementIdentity.ofPlugins(plugins, versions).values());
			conditions.put(evaluator, local);
		}
		Map<String, Map<MeasurementCondition, String>> cells = new HashMap<>();
		for (Cell cell : contract.cells())
		{
			Harness evaluator = cell.evaluator().orElseThrow();
			Map<MeasurementCondition, String> local = conditions.get(evaluator);
			cells.put(cell.id(), local);
			for (Cell stress : CellTier.stressCells(contract, scope.harness(), cell.id()))
				cells.put(stress.id(), local);
		}
		Map<MeasurementCondition, String> shared = new EnumMap<>(campaignEnvironment().values());
		shared.put(MeasurementCondition.JUDGE_HARNESS, scope.harness().commandName());
		shared.put(MeasurementCondition.JUDGE, String.valueOf(scope.sessions().judgeVersion()));
		return new MeasurementIdentity(shared, cells);
	}

	/**
	 * Returns the installed evaluator and staged loader conditions that are known before trials run.
	 *
	 * @param report the observation of the evaluator
	 * @return the conditions for that evaluator
	 */
	private static Map<MeasurementCondition, String> evaluatorConditions(EvaluatorMetadata report)
	{
		EvaluatorRuntime runtime = report.runtime();
		StringJoiner files = new StringJoiner("\n").setEmptyValue("none");
		for (Path path : report.loaderFiles())
			files.add(PortablePaths.text(path));
		return Map.of(MeasurementCondition.PROVIDER, report.harness().commandName(),
			MeasurementCondition.HARNESS_VERSION, runtime.harnessVersion(),
			MeasurementCondition.SESSION_ENVIRONMENT_VERSION, String.valueOf(runtime.environmentVersion()),
			MeasurementCondition.SESSION_ENVIRONMENT_RELEASE, runtime.environmentRelease(),
			MeasurementCondition.LOADER_CONFIGURATION, report.loaderIdentity(),
			MeasurementCondition.LOADER_FILES, files.toString());
	}

	/**
	 * Returns the edit authority of a campaign.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param inventory the campaign fork's inventory
	 * @return the authority
	 * @throws IOException if the stored proposal cannot be read
	 */
	private Authority authority(Path root, Campaign campaign, FileInventory inventory) throws IOException
	{
		return new Authority(promptSources(root, campaign), loaderFiles(campaign), inventory);
	}

	/**
	 * Returns the in-project prompt sources of a campaign: the target, every in-project derived source of the stored
	 * proposal and, once CAT is a recorded trial plugin, the project rules directory. External derived sources are
	 * participants but never prompt sources, so they stay read-only.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @return the sources, relative to the project
	 * @throws IOException if the stored proposal cannot be read
	 */
	private Set<Path> promptSources(Path root, Campaign campaign) throws IOException
	{
		// Validating the proposal would not change its derived sources, and skipping it lets cancel and status work on
		// a proposal that no longer validates
		Optional<ProposalFile.Stored> stored = storedProposal(campaign);
		if (stored.isPresent())
			return promptSources(root, campaign, stored.get().derivedSources());
		return promptSources(root, campaign, List.of());
	}

	/**
	 * Returns the in-project prompt sources of a proposal: the campaign's target, every in-project derived source and,
	 * once CAT is a recorded trial plugin, the project rules directory.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param derived the proposal
	 * @return the sources, relative to the project
	 */
	private Set<Path> promptSources(Path root, Campaign campaign, DerivedProposal derived)
	{
		return promptSources(root, campaign, derived.derivedSources());
	}

	/**
	 * Returns a campaign's in-project prompt sources: its target, every in-project derived source and, once CAT is a
	 * recorded trial plugin, the project rules directory.
	 *
	 * @param root     the project root
	 * @param campaign the campaign
	 * @param derived  the derived sources
	 * @return the sources, relative to the project
	 */
	private Set<Path> promptSources(Path root, Campaign campaign, List<DerivedSource> derived)
	{
		Set<Path> sources = new LinkedHashSet<>();
		sources.add(relativeTarget(campaign));
		for (Path path : derivedPaths(root, derived))
		{
			if (!path.isAbsolute())
				sources.add(path);
		}
		return TrialSelection.promptSources(sources, sourcePlugins(campaign));
	}

	/**
	 * Returns a campaign's target relative to its project. The store reads a campaign in the project that it belongs
	 * to, so a session that works in a moved or copied project gets the target's path in that project.
	 *
	 * @param campaign the campaign
	 * @return the relative path
	 */
	private static Path relativeTarget(Campaign campaign)
	{
		Path target = campaign.header().target();
		if (target.isAbsolute())
			return campaign.header().root().relativize(target);
		return target;
	}

	/**
	 * Returns the measurement conditions that the launcher observes before any session runs. The sessions observe
	 * the model, the reasoning configuration and the prompt sources.
	 *
	 * @param files reads the loader configuration files
	 * @return the conditions
	 * @throws IOException if a loader configuration file cannot be read
	 */
	private MeasurementIdentity environment(Entries files) throws IOException
	{
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		values.put(MeasurementCondition.PROVIDER, scope.harness().commandName());
		values.put(MeasurementCondition.HARNESS_VERSION, scope.harnessVersion());
		values.put(MeasurementCondition.SESSION_ENVIRONMENT_VERSION,
			String.valueOf(scope.sessions().sessionEnvironmentVersion()));
		values.put(MeasurementCondition.SESSION_ENVIRONMENT_RELEASE, scope.sessions().sessionEnvironmentRelease());
		values.put(MeasurementCondition.JUDGE, String.valueOf(scope.sessions().judgeVersion()));
		values.put(MeasurementCondition.RUNNER_VERSION, PluginInfo.CAT_VERSION);
		values.put(MeasurementCondition.CAT_VERSION, PluginInfo.CAT_VERSION);
		LoaderIdentity loader = LoaderIdentity.read(scope.loaderConfiguration(), files::entry, scope.userLoaderSettings());
		values.put(MeasurementCondition.LOADER_CONFIGURATION, loader.digest());
		StringJoiner present = new StringJoiner("\n");
		present.setEmptyValue("none");
		for (Path path : loader.files())
			present.add(PortablePaths.text(path));
		values.put(MeasurementCondition.LOADER_FILES, present.toString());
		return new MeasurementIdentity(values);
	}

	/**
	 * Reads the entry of a file, by its path relative to the project.
	 */
	@FunctionalInterface
	private interface Entries
	{
		/**
		 * Returns the entry of a file.
		 *
		 * @param path the file's path relative to the project
		 * @return the entry, or empty if the file does not exist
		 * @throws IOException if the file cannot be read
		 */
		Optional<FileEntry> entry(Path path) throws IOException;
	}
}
