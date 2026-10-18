package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.PluginInfo;
import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.SessionDirectory;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationHistory;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationRecord;
import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.campaign.Header;
import io.github.cowwoc.cat.common.optimizer.campaign.Registry;
import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.TestRevision;
import io.github.cowwoc.cat.common.optimizer.campaign.TestRevisionRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialFiles;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.InvalidProposalException;
import io.github.cowwoc.cat.common.optimizer.contract.FixtureFile;
import io.github.cowwoc.cat.common.optimizer.contract.Dependent;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileCatalog;
import io.github.cowwoc.cat.common.optimizer.contract.PromptConfiguration;
import io.github.cowwoc.cat.common.optimizer.contract.PromptConfigurationException;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTest;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestLocations;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestFiles;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.contract.TrialPlugin;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.delivery.Delivery;
import io.github.cowwoc.cat.common.optimizer.evidence.EvidenceSection;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult;
import io.github.cowwoc.cat.common.optimizer.execution.Cleanup;
import io.github.cowwoc.cat.common.optimizer.execution.DeliveryReport;
import io.github.cowwoc.cat.common.optimizer.execution.Driver;
import io.github.cowwoc.cat.common.optimizer.execution.DriverContext;
import io.github.cowwoc.cat.common.optimizer.execution.GuardPilot;
import io.github.cowwoc.cat.common.optimizer.execution.LeftoverRuns;
import io.github.cowwoc.cat.common.optimizer.execution.PilotResult;
import io.github.cowwoc.cat.common.optimizer.execution.ProcessHandleTerminator;
import io.github.cowwoc.cat.common.optimizer.execution.SliceResult;
import io.github.cowwoc.cat.common.optimizer.execution.SliceRunner;
import io.github.cowwoc.cat.common.optimizer.execution.TrialSelection;
import io.github.cowwoc.cat.common.optimizer.execution.prompt.PromptAuthorPrompt;
import io.github.cowwoc.cat.common.optimizer.execution.prompt.JudgePrompt;
import io.github.cowwoc.cat.common.optimizer.execution.prompt.TriagePrompt;
import io.github.cowwoc.cat.common.optimizer.execution.prompt.ValuesPrompt;
import io.github.cowwoc.cat.common.optimizer.inventory.Authority;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryScanner;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.HookRegistration;
import io.github.cowwoc.cat.common.optimizer.port.LoadedSources;
import io.github.cowwoc.cat.common.optimizer.port.PluginBase;
import io.github.cowwoc.cat.common.optimizer.port.ProfileInventory;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.WorkspacePort;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.port.SourceSelection;
import io.github.cowwoc.cat.common.optimizer.proposal.DerivedProposal;
import io.github.cowwoc.cat.common.optimizer.proposal.DraftDirectory;
import io.github.cowwoc.cat.common.optimizer.contract.DerivedSource;
import io.github.cowwoc.cat.common.optimizer.proposal.ContractAuthorProtocol;
import io.github.cowwoc.cat.common.optimizer.proposal.JudgeTemplate;
import io.github.cowwoc.cat.common.optimizer.proposal.ProfileEvidence;
import io.github.cowwoc.cat.common.optimizer.proposal.RegressionScenarios;
import io.github.cowwoc.cat.common.optimizer.proposal.PluginCheck;
import io.github.cowwoc.cat.common.optimizer.proposal.PluginMapping;
import io.github.cowwoc.cat.common.optimizer.proposal.Proposal;
import io.github.cowwoc.cat.common.optimizer.proposal.TestRevisionProtocol;
import io.github.cowwoc.cat.common.optimizer.report.CertificationStatus;
import io.github.cowwoc.cat.common.optimizer.report.ReportEvidence;
import io.github.cowwoc.cat.common.optimizer.report.StatusReport;
import io.github.cowwoc.cat.common.optimizer.store.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.store.CertificationHistoryJson;
import io.github.cowwoc.cat.common.optimizer.store.FileCampaignStore;
import io.github.cowwoc.cat.common.optimizer.store.FileCandidateOverlays;
import io.github.cowwoc.cat.common.runner.PluginManifest;
import io.github.cowwoc.cat.common.optimizer.workspace.ControlSource;
import io.github.cowwoc.cat.common.optimizer.workspace.ForkView;
import io.github.cowwoc.cat.common.optimizer.workspace.LinkReport;
import io.github.cowwoc.cat.common.optimizer.workspace.PluginBuild;
import io.github.cowwoc.cat.common.optimizer.workspace.WorkspaceException;

import java.io.IOException;
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
 * <b>Thread Safety:</b> This class is not thread-safe. One launcher process runs one command.
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
	private final OptimizerCommandScope scope;
	private final ProposalSchema schema;
	/**
	 * The directory that holds each campaign of another session that this command reads, by the campaign's
	 * identifier. Every other campaign is the host session's.
	 */
	private final Map<String, Path> otherSessionCampaigns = new HashMap<>();

	/**
	 * Creates the core.
	 *
	 * @param scope the command's scope
	 * @throws NullPointerException if {@code scope} is null
	 */
	public OptimizerCore(OptimizerCommandScope scope)
	{
		requireThat(scope, "scope").isNotNull();
		this.scope = scope;
		this.schema = new ProposalSchema(scope.json());
	}

	/**
	 * Returns a contract author protocol for a campaign, with the target's current recorded scenarios, the
	 * harness's role profile, the files that load in production, the campaign's trial plugins, the launcher that
	 * provides the contract tools and the harness's tool-output limits.
	 *
	 * @param root the original project root, where scenarios are recorded
	 * @param campaign the campaign, whose header holds the target and the user's goal, and whose trial plugins are
	 *   recorded
	 * @return the protocol
	 * @throws IOException if recorded scenarios, the installed role profile or a file that loads in production cannot
	 *   be read
	 */
	private ContractAuthorProtocol contractAuthor(Path root, Campaign campaign) throws IOException
	{
		Header header = campaign.header();
		return new ContractAuthorProtocol(scope.json(), schema, new JudgeTemplate(scope.json(),
			scope.profiles().roleProfile()), RegressionScenarios.read(scope.json(), root, scope.home()),
			header.target(), header.goal(), scope.sessions().reportsExitStatus(),
			scope.sessions().loadedSources(root, List.of()).production(), campaign.plugins().orElseThrow(),
			scope.launcher(), scope.profiles()::toolOutputBytes);
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
	public CommandResult start(Path target, int ceiling, String goal)
		throws CommandRefusedException, IOException, InterruptedException
	{
		requireThat(target, "target").isNotNull();
		requireThat(ceiling, "ceiling").isBetween(1, true, Campaign.MAXIMUM_CEILING, true);
		if (goal != null)
			requireThat(goal, "goal").isNotBlank();
		return withCleanup(_ -> startBody(target, ceiling, goal));
	}

	/**
	 * Starts or resumes the campaign of a target.
	 *
	 * @param target the target, relative to the working directory
	 * @param ceiling the maximum simultaneous nested sessions of a new campaign
	 * @param goal the user's goal for a new campaign, or {@code null} if the user stated none. A resumed campaign
	 *   keeps the goal it was started with.
	 * @return the result
	 * @throws CommandRefusedException if the command is refused
	 * @throws IOException if the store, the workspace or a session fails
	 * @throws InterruptedException if the thread is interrupted while it waits for a session
	 */
	private CommandResult startBody(Path target, int ceiling, String goal)
		throws CommandRefusedException, IOException, InterruptedException
	{
		Path root = scope.projectRoot();
		Optional<String> exposure = scope.sessions().findHeldOutExposure(root);
		if (exposure.isPresent())
			throw new CommandRefusedException(exposure.get());
		Path absolute = scope.directory().toRealPath().resolve(target).normalize();
		if (!Files.exists(absolute))
			throw new CommandRefusedException("The target does not exist: " + absolute);
		absolute = absolute.toRealPath();
		requireInsideProject(root, absolute);
		if (!Files.isRegularFile(absolute))
		{
			throw new CommandRefusedException("A campaign optimizes one prompt file, so the target must be a file, but " +
				absolute + " is not. Run start with the prompt file to optimize.");
		}
		CampaignStore store = scope.store();
		Registry.Opening opening = Registry.open(headers(), scope.sessionDirectory().sessionId(), root, absolute,
			scope.harness());
		return switch (opening)
		{
			case Registry.Opening.Refuse refuse -> throw refused(refuse.owner());
			case Registry.Opening.Resume resume -> resume(root, store, resume.header());
			case Registry.Opening.Create _ -> create(root, store, absolute, ceiling, goal);
		};
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
			for (Header header : new FileCampaignStore(session.getValue(), scope.json()).listHeaders())
				headers.add(new Registry.OwnedHeader(session.getKey(), header));
		}
		return headers;
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
	 * removal that fails to the body's result or refusal.
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
		catch (CommandRefusedException e)
		{
			if (notices.isEmpty())
				throw e;
			throw new CommandRefusedException(e.getMessage() + "\n" + String.join("\n", notices), e);
		}
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
	 * Resumes a campaign that this harness started.
	 *
	 * @param root the project root
	 * @param store the store
	 * @param header the campaign's header
	 * @return the result
	 * @throws CommandRefusedException if the campaign cannot be shown, or its trial plugins are unrecorded and cannot
	 *   be built, copied or mapped
	 * @throws IOException if the store or the workspace fails
	 * @throws InterruptedException if the thread is interrupted while it waits for a session or a build
	 */
	private CommandResult resume(Path root, CampaignStore store, Header header)
		throws CommandRefusedException, IOException, InterruptedException
	{
		Campaign campaign = require(store, header.id(), true);
		if (campaign.isConfirmed() && !campaign.revisionRequested())
			return report(root, campaign, null, null);
		ForkView fork = openFork(root, campaign);
		// A build that failed left the trial plugins unrecorded, so start builds them again
		if (campaign.plugins().isEmpty())
		{
			campaign = withTrialPlugins(root, store, campaign, fork,
				configuredPlugins(root, relativeTarget(root, campaign)));
		}
		Optional<DerivedProposal> stored = stored(root, campaign);
		if (stored.isPresent())
			return present(root, store, campaign, fork, stored.get());
		return derive(root, store, campaign, fork, null, null);
	}

	/**
	 * Creates a campaign and derives its first proposal.
	 *
	 * @param root the project root
	 * @param store the store
	 * @param target the canonical target
	 * @param ceiling the maximum simultaneous nested sessions of the new campaign
	 * @param goal what the user wants the target to achieve, or {@code null} if the user stated no goal
	 * @return the result
	 * @throws CommandRefusedException if the prompt test configuration is missing or invalid, the base worktree has
	 *   uncommitted changes, or the trial plugins cannot be built, copied or mapped
	 * @throws IOException if the store, the workspace or a session fails
	 * @throws InterruptedException if the thread is interrupted while it waits for a session or a build
	 */
	private CommandResult create(Path root, CampaignStore store, Path target, int ceiling, String goal)
		throws CommandRefusedException, IOException, InterruptedException
	{
		String id = ID_TIME.format(scope.clock().instant()) + "-" +
			HexFormat.of().toHexDigits(scope.random().nextInt()).substring(Integer.BYTES * 2 - RANDOM_ID_DIGITS);
		Path directory = campaignDirectory(id);
		Path relative = root.relativize(target);
		// The configuration is checked before anything is created, so a refusal leaves nothing behind
		List<PromptConfiguration.ConfiguredPlugin> configured = configuredPlugins(root, relative);
		ForkView fork;
		try
		{
			fork = scope.forks().create(root, directory, Set.of(relative));
		}
		catch (WorkspaceException e)
		{
			throw new CommandRefusedException(e.getMessage(), e);
		}
		FileInventory inventory = fork.inventory();
		Overlay incumbent = Overlay.of(inventory);
		new FileCandidateOverlays(directory, inventory, scope.json()).save(incumbent);
		Header header = new Header(id, root, target, scope.harness(), Lifecycle.ACTIVE, goal);
		Campaign campaign = Campaign.start(header, ceiling, incumbent.identity(), environment(inventory::entry));
		store.replaceSnapshot(campaign);
		campaign = withTrialPlugins(root, store, campaign, fork, configured);
		CommandResult result = derive(root, store, campaign, fork, null, null);
		if (!fork.excludeAdded())
			return result;
		String message = "Added " + ForkView.EXCLUDE_ENTRY + " to the repository's local info/exclude.";
		if (result.message() != null)
			message += "\n" + result.message();
		return new CommandResult(result.status(), result.harness(), result.proposal(), result.delivery(), message);
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
		Path file = PromptConfiguration.location(target);
		Path absolute = root.resolve(file);
		String harness = scope.harness().commandName();
		String retry = " Run `start` again once it does.";
		if (!Files.isRegularFile(absolute))
		{
			throw new CommandRefusedException("The prompt test configuration " + PortablePaths.text(file) + " does " +
				"not exist. It lists the plugins that trials install, such as {\"" + harness + "\": {\"plugins\": " +
				"[]}} for trials that install none." + retry);
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
				"the key, with an empty plugin list if trials install none." + retry);
		}
		requireBuiltWhenTracked(root, file, plugins.get());
		return plugins.get();
	}

	/**
	 * Refuses a configured plugin without a build whose name the manifest of a plugin that the project tracks holds,
	 * because trials would install the user's installed copy instead of the project's plugin.
	 *
	 * @param root the project root
	 * @param file the configuration, relative to the project
	 * @param plugins the configured plugins
	 * @throws CommandRefusedException if such a plugin exists
	 * @throws IOException if Git cannot list the tracked files
	 */
	private void requireBuiltWhenTracked(Path root, Path file, List<PromptConfiguration.ConfiguredPlugin> plugins)
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
		Path manifest = scope.harness().pluginManifest();
		for (Path tracked : new TreeSet<>(scope.forks().tracked(root)))
		{
			if (!tracked.endsWith(manifest))
				continue;
			Path directory = root;
			if (tracked.getNameCount() > manifest.getNameCount())
				directory = root.resolve(tracked.subpath(0, tracked.getNameCount() - manifest.getNameCount()));
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
	 * each trial plugin once, maps the prompt files onto each project-built plugin and records the plugins in the
	 * campaign. The worktree returns to the fork's files afterwards, so the build output never takes part in trials.
	 * A failure leaves the plugins unrecorded, so that {@code start} builds them again.
	 *
	 * @param root the project root
	 * @param store the store
	 * @param campaign the campaign, which has no recorded trial plugins
	 * @param fork the campaign's fork
	 * @param configured the plugins that the prompt test configuration lists for the harness
	 * @return the campaign with its trial plugins recorded
	 * @throws CommandRefusedException if a build fails or produces no plugin of its name, an installed plugin does not
	 *   have exactly one installed copy, or the target would not load in trials
	 * @throws IOException if a plugin cannot be copied, the worktree cannot be restored or the campaign cannot be
	 *   stored
	 * @throws InterruptedException if the thread is interrupted while it waits for a build
	 */
	private Campaign withTrialPlugins(Path root, CampaignStore store, Campaign campaign, ForkView fork,
		List<PromptConfiguration.ConfiguredPlugin> configured)
		throws CommandRefusedException, IOException, InterruptedException
	{
		WorkspacePort workspace = fork.workspace();
		List<TrialPlugin> copied = new ArrayList<>();
		try
		{
			for (PromptConfiguration.ConfiguredPlugin plugin : configured)
			{
				if (plugin.build().isPresent())
					copied.add(builtPlugin(workspace, plugin.name(), plugin.build().get()));
				else
					copied.add(installedPlugin(workspace, plugin.name()));
			}
		}
		finally
		{
			// The build output is not one of the fork's files, so it must not reach the trials' staged sources
			workspace.materialize(Overlay.of(fork.inventory()));
		}

		FileInventory inventory = fork.inventory();
		Path target = relativeTarget(root, campaign);
		SortedSet<Path> promptFiles = authority(root, campaign, inventory).promptFiles(inventory);
		Set<Path> fromProject = loadsFromProject(root, bases(copied, workspace), promptFiles);
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
				PluginMapping.map(inventory, promptFiles, fromProject, base)));
		}
		PluginMapping.Coverage coverage = PluginMapping.check(inventory, promptFiles, fromProject, mapped, baseFiles);
		Optional<String> refusal = coverage.targetRefusal(target);
		if (refusal.isPresent())
			throw new CommandRefusedException(refusal.get());
		Campaign built = campaign.withPlugins(mapped);
		store.replaceSnapshot(built);
		return built;
	}

	/**
	 * Builds a trial plugin that the project builds and copies its base.
	 *
	 * @param workspace the campaign's workspace, whose optimization worktree the build runs in
	 * @param name the plugin's name
	 * @param build the plugin's build
	 * @return the plugin, without mappings
	 * @throws CommandRefusedException if the build cannot start, exits with a status other than zero, or its output
	 *   holds no harness manifest or one of another name
	 * @throws IOException if the base cannot be copied
	 * @throws InterruptedException if the thread is interrupted while it waits for the build
	 */
	private TrialPlugin builtPlugin(WorkspacePort workspace, String name, TrialPlugin.Build build)
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
				result = PluginBuild.run(workspace.worktree(), build);
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
		Path manifest = scope.harness().pluginManifest();
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
		return new TrialPlugin(name, workspace.copyPluginBase(output), build, List.of());
	}

	/**
	 * Copies the base of a trial plugin that the project does not build from the user's installed copy.
	 *
	 * @param workspace the campaign's workspace
	 * @param name the plugin's name, {@code <plugin>@<marketplace>}
	 * @return the plugin
	 * @throws CommandRefusedException if the harness's install records do not name exactly one installed copy
	 * @throws IOException if the install records cannot be read or the copy cannot be copied
	 */
	private TrialPlugin installedPlugin(WorkspacePort workspace, String name)
		throws CommandRefusedException, IOException
	{
		String harness = scope.harness().commandName();
		List<Path> copies = scope.sessions().installedPlugins(name);
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
		return new TrialPlugin(name, workspace.copyPluginBase(copies.getFirst()), new TrialPlugin.Installed(),
			List.of());
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
	 * Returns the prompt files that trials load from the staged project itself rather than from a trial plugin.
	 *
	 * @param root the project root
	 * @param bases the trial plugins with their base copies
	 * @param promptFiles the in-project prompt files, relative to the project
	 * @return the prompt files that the harness reads from the project, and the project files that the trial plugins'
	 *   rules give the agent from the project, relative to the project
	 * @throws IOException if a file that loads cannot be read
	 */
	private Set<Path> loadsFromProject(Path root, List<PluginBase> bases, Set<Path> promptFiles) throws IOException
	{
		// Without mappings, a file that loads from a plugin's base is named by its absolute path, never as a project
		// file
		List<PluginBase> unmapped = new ArrayList<>();
		for (PluginBase base : bases)
		{
			TrialPlugin plugin = base.plugin();
			unmapped.add(new PluginBase(new TrialPlugin(plugin.name(), plugin.baseDigest(), plugin.source(), List.of()),
				base.directory()));
		}
		Set<Path> paths = new HashSet<>();
		for (DerivedSource source : scope.sessions().loadedSources(root, unmapped).trials())
		{
			Path path = Path.of(source.path());
			if (!path.isAbsolute())
				paths.add(path);
		}
		for (Path promptFile : promptFiles)
		{
			if (scope.harness().readsFromProject(promptFile))
				paths.add(promptFile);
		}
		return paths;
	}

	@Override
	public CommandResult run(String campaign) throws CommandRefusedException, IOException, InterruptedException
	{
		return withCleanup(notices ->
		{
			Path root = scope.projectRoot();
			Campaign loaded = require(scope.store(), campaign, true);
			if (loaded.lifecycle() == Lifecycle.ACTIVE && loaded.awaitsTestRevision() && !loaded.revisionRequested())
			{
				Optional<String> unfinished = reviseTests(root, loaded);
				loaded = require(scope.store(), campaign, true);
				if (unfinished.isPresent())
					return report(root, loaded, null, unfinished.get());
			}
			SliceResult result = SliceRunner.run(context(root, loaded), campaign, List.of());
			DeliveryResult delivery = Optional.ofNullable(result.delivery()).map(DeliveryReport::result).orElse(null);
			noticeOf(cleanup().end(require(scope.store(), campaign, true), delivery), notices);
			return new CommandResult(result.status(), scope.harness(), null, result.delivery(), null);
		});
	}

	/**
	 * Runs a test revision session for each prompt test whose revision the campaign awaits. A sound test is recorded
	 * at once. Once every session has ended, the corrected tests replace the confirmed tests, their guard pilot runs,
	 * and the revised contract starts a new lineage under the confirmed certification scope; then each test whose
	 * revision cannot proceed pauses the campaign.
	 *
	 * @param root the project root
	 * @param campaign the campaign, which awaits a test revision and no contract revision
	 * @return a notice for the user if a session failed or its result is invalid, so that the remaining requests stay
	 *   pending until {@code run} retries them; otherwise empty
	 * @throws CommandRefusedException if another process runs the campaign, the campaign does not accept a test
	 *   revision, or the fork does not hold the revised contract's files
	 * @throws IOException if the store, the workspace, the stored proposal or a session fails
	 * @throws InterruptedException if the thread is interrupted while it waits for a session
	 */
	private Optional<String> reviseTests(Path root, Campaign campaign)
		throws CommandRefusedException, IOException, InterruptedException
	{
		String id = campaign.header().id();
		ProposalFile.Stored stored = new ProposalFile(heldOutDirectory(id), scope.json()).read().
			orElseThrow(() -> new IOException("Campaign " + id + " has no stored confirmed proposal"));
		ContractAuthorProtocol contractAuthor = contractAuthor(root, campaign);
		DerivedProposal current;
		try
		{
			current = stored.restore(contractAuthor.proposal(stored.derivation(), stored.draft(),
				stored.observedSources(), stored.profiles(), stored.salt()));
		}
		catch (InvalidProposalException e)
		{
			throw new IOException("The stored proposal is invalid: " + String.join("; ", e.problems()), e);
		}
		TestRevisionProtocol protocol = new TestRevisionProtocol(scope.json(), schema, contractAuthor);
		ForkView fork = openFork(root, campaign);
		Overlay incumbent = new FileCandidateOverlays(campaignDirectory(id), fork.inventory(), fork::version,
			scope.json()).require(campaign.incumbent().snapshot());
		Path staged = fork.workspace().stage(incumbent, Set.of());

		List<TestRevision> revisions = new ArrayList<>();
		List<CampaignEvent> blocked = new ArrayList<>();
		for (TestRevisionRequest request : campaign.testRevisions().requests())
		{
			TestRevisionProtocol.Examination examined = new TestRevisionProtocol.Examination(current,
				stored.profiles(), request, evidenceSections(request));
			SessionResult result = await(scope.sessions().start(protocol.request(staged, examined)));
			String session = "The test revision of `" + request.test() + "`";
			if (!(result instanceof SessionResult.Completed completed))
				return Optional.of(session + " " + describe(result) + " Run `run` again to retry.");
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
				return Optional.of(session + " is invalid: " + String.join("; ", e.problems()) +
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
					List.of(new CampaignEvent.ReportTestSound(request.test(), sound.reasons())));
				case TestRevisionProtocol.Result.Blocked block -> blocked.add(new CampaignEvent.BlockTestRevision(
					request.test(), block.reason(), block.reasons(), block.proposedChange()));
			}
		}

		campaign = require(scope.store(), id, true);
		List<CampaignEvent> events = new ArrayList<>();
		if (!revisions.isEmpty())
			events.addAll(revisedContract(root, campaign, fork, current, stored, revisions));
		// Confirming the revised contract clears pending decisions, so a blocked revision pauses the campaign after it
		events.addAll(blocked);
		if (!events.isEmpty())
			apply(root, campaign, events);
		return Optional.empty();
	}

	/**
	 * Stores a contract whose tests a test revision corrected, runs its guard pilot and returns the events that
	 * start its lineage under the confirmed certification scope.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param fork the campaign's fork
	 * @param revised the confirmed proposal with the corrected tests
	 * @param stored the confirmed proposal as it was stored
	 * @param revisions the changes, one per corrected test
	 * @return the events
	 * @throws CommandRefusedException if the fork does not hold the revised contract's files, or something else blocks
	 *   confirming it
	 * @throws IOException if the proposal, the pilot or the workspace cannot be read or written
	 * @throws InterruptedException if the thread is interrupted while it waits for a pilot session
	 */
	private List<CampaignEvent> revisedContract(Path root, Campaign campaign, ForkView fork,
		DerivedProposal revised, ProposalFile.Stored stored, List<TestRevision> revisions)
		throws CommandRefusedException, IOException, InterruptedException
	{
		Participants participants = participants(root, revised);
		if (!unheld(fork, participants).isEmpty())
			fork = fork.extend(root, participants.existing());
		PluginCheck plugin = pluginCheck(root, campaign, fork);
		Map<String, PilotResult> pilots = pilot(root, campaign, fork, revised, plugin);
		// The proposal previews its confirmation, which needs the campaign that awaits the revised contract
		CampaignEvent.ReviseTests revise = new CampaignEvent.ReviseTests(revisions);
		Proposal proposal = proposal(root, Transitions.apply(campaign, revise), fork, revised, pilots, plugin);
		CertificationScope certificationScope = campaign.contract().profiles().scope();
		if (!proposal.isConfirmable(certificationScope))
			throw new CommandRefusedException(proposal.confirmationRefusal(certificationScope));
		requireParticipantsHeld(root, fork, revised, trialSelection(root, campaign, revised, plugin));
		// The stored proposal changes only once nothing blocks confirming it, so it stays the confirmed contract's
		new ProposalFile(heldOutDirectory(campaign.header().id()), scope.json()).write(revised,
			stored.profiles());

		List<CampaignEvent> events = new ArrayList<>();
		events.add(revise);
		events.addAll(moveOnto(campaign, fork));
		events.addAll(proposal.confirmation(certificationScope, scope.random().nextLong()));
		return events;
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
				return report(root, campaign.get(), null, null);
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
		if (history.records().isEmpty() && header.isPresent())
			return report(root, header.get());
		if (history.records().isEmpty())
		{
			String absent = "no campaign or certification record exists for " + relative + ".";
			if (isId)
				throw new CommandRefusedException("No campaign has the identifier " + subject + ", and " + absent);
			throw new CommandRefusedException("N" + absent.substring(1));
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
			Optional<Campaign> campaign = new FileCampaignStore(session.getValue(), scope.json()).load(id);
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
			return report(root, require(scope.store(), id, false), null, null);
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
		return report(root, other.campaign(), null, null).
			withNotices(List.of("Campaign " + owner.header().id() + " " + ownership(owner)));
	}

	/**
	 * Compares the latest certification of each harness with the prompt files and conditions that exist now.
	 *
	 * @param history the target's certification history, which holds at least one record
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
		for (CertificationRecord record : history.latest())
		{
			for (Path path : record.promptFiles().keySet())
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
		if (certificationScope == null)
		{
			throw new CommandRefusedException("Confirmation needs the certification scope that you choose: " +
				CertificationScope.choices() + ".");
		}
		return withCleanup(_ ->
		{
			Path root = scope.projectRoot();
			CampaignStore store = scope.store();
			Campaign loaded = require(store, campaign, true);
			ForkView fork = openFork(root, loaded);
			DerivedProposal derived = stored(root, loaded).orElseThrow(() -> new CommandRefusedException(
				"Campaign " + campaign + " has no proposal to confirm. Run `revise` or `start` to derive one."));
			// A revision after confirmation runs on the files that its presentation recorded beside the fork's
			if (loaded.isConfirmed())
				fork = fork.including(participants(root, derived).existing());
			PluginCheck plugin = pluginCheck(root, loaded, fork);
			Proposal proposal = proposal(root, loaded, fork, derived, pilotFile(loaded).read(), plugin);
			if (!proposal.isConfirmable(certificationScope))
				throw new CommandRefusedException(proposal.confirmationRefusal(certificationScope));
			requireParticipantsHeld(root, fork, derived, trialSelection(root, loaded, derived, plugin));
			List<CampaignEvent> events = new ArrayList<>(moveOnto(loaded, fork));
			events.addAll(proposal.confirmation(certificationScope, scope.random().nextLong()));
			return apply(root, loaded, events);
		});
	}

	/**
	 * Requires that the fork holds the trial fixtures and derived prompt sources of the proposal that the user
	 * confirms, which presenting the proposal recorded, so that trials never read a live file.
	 *
	 * @param root the project root
	 * @param fork the fork that the confirmed contract runs on
	 * @param derived the proposal that the user confirms
	 * @param selection the files of the candidate that every trial receives
	 * @throws CommandRefusedException if a fixture does not exist, the fork lacks a file that the proposal needs, a
	 *   fixture file of a prompt test would replace a file of the fork that every trial receives, or a fixture file is
	 *   a copy of a file that every trial receives
	 */
	private static void requireParticipantsHeld(Path root, ForkView fork, DerivedProposal derived,
		SourceSelection selection) throws CommandRefusedException
	{
		Set<String> promptCopies = promptFileCopies(derived.contract(), selection);
		if (!promptCopies.isEmpty())
		{
			throw new CommandRefusedException("These fixture files of the proposal's prompt tests are copies of files " +
				"that every trial already receives from its candidate, so each copy would stand in for the " +
				"candidate's version: " + String.join("; ", promptCopies) + ". Run `revise` with feedback that " +
				"removes these links from the tests' " + PromptTestFiles.WORKSPACE + "/ directories.");
		}
		Set<String> collisions = fixtureCollisions(fork.inventory(), derived.contract(), selection);
		if (!collisions.isEmpty())
		{
			throw new CommandRefusedException("These fixture files of the proposal's prompt tests collide with " +
				"project files, so the trials could not receive them: " + String.join(", ", collisions) + ". Run " +
				"`revise` with feedback that asks for other paths.");
		}
		Participants participants = participants(root, derived);
		if (!participants.missingFixtures().isEmpty())
		{
			throw new CommandRefusedException("These trial fixtures do not exist in the project, so the trials " +
				"could not see them: " + String.join(", ", participants.missingFixtures()));
		}
		String unheld = unheld(fork, participants);
		if (unheld.isEmpty())
			return;
		throw new CommandRefusedException("These files appeared after CAT presented the proposal, so its guard pilot " +
			"did not see them: " + unheld + ". Run `start` for the same target again so that CAT presents the " +
			"proposal with them.");
	}

	/**
	 * Returns the fixture files of a contract's prompt tests that are copies of a file that every trial receives from
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
			for (FixtureFile fixture : test.fixtures())
			{
				if (fixture.source().isEmpty())
					continue;
				Path source = Path.of(fixture.source()).normalize();
				if (source.isAbsolute() || PromptTestLocations.rootOf(source).isPresent() || !selection.selects(source))
					continue;
				copies.add("`" + test.id() + "` copies `" + fixture.source() + "` to `" + fixture.path() + "`");
			}
		}
		return copies;
	}

	/**
	 * Returns the fixture files of a contract's prompt tests that would replace a file of the fork that every trial
	 * receives, or that need a directory where every trial receives a file.
	 *
	 * @param inventory the files of the fork
	 * @param contract the contract
	 * @param selection the files of the candidate that every trial receives
	 * @return the colliding paths of every test case, relative to the workspace root
	 */
	private static Set<String> fixtureCollisions(FileInventory inventory, Contract contract, SourceSelection selection)
	{
		Set<String> collisions = new TreeSet<>();
		for (PromptTest test : contract.tests())
		{
			for (TestCase testCase : test.allTestCases())
			{
				for (String target : test.fixtureTargets(testCase).keySet())
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
			fork::version, scope.json());
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
	private static String unheld(ForkView fork, Participants participants)
	{
		FileInventory held = fork.inventory();
		return participants.existing().stream().filter(path -> !held.holds(path)).map(Path::toString).
			collect(Collectors.joining(", "));
	}

	/**
	 * The files beyond the prompt system that a proposal's trials need.
	 *
	 * @param existing the existing trial fixtures and derived prompt sources, named as the fork names participants
	 * @param missingFixtures the trial fixtures that do not exist in the project
	 */
	private record Participants(Set<Path> existing, List<String> missingFixtures)
	{
	}

	/**
	 * Returns the trial fixtures and derived prompt sources of a proposal.
	 *
	 * @param root the project root
	 * @param derived the contract author's proposal
	 * @return the files
	 */
	private static Participants participants(Path root, DerivedProposal derived)
	{
		Set<Path> existing = new LinkedHashSet<>();
		List<String> missing = new ArrayList<>();
		for (String fixture : derived.contract().fixtures().ignoredPaths())
		{
			Path path = Path.of(fixture);
			if (InventoryScanner.isOmitted(path) ||
				Files.notExists(root.resolve(path), LinkOption.NOFOLLOW_LINKS))
			{
				missing.add(fixture);
			}
			else
				existing.add(path);
		}
		for (Path source : derivedPaths(root, derived))
		{
			if (Files.exists(root.resolve(source), LinkOption.NOFOLLOW_LINKS))
				existing.add(source);
		}
		return new Participants(existing, missing);
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
		Set<Path> paths = new LinkedHashSet<>();
		for (DerivedSource source : derived.derivedSources())
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
		return withCleanup(_ -> reviseBody(campaign, feedback));
	}

	/**
	 * Derives a revised proposal from the user's feedback.
	 *
	 * @param campaign the campaign's identifier
	 * @param feedback the user's feedback
	 * @return the result
	 * @throws CommandRefusedException if the campaign cannot be revised
	 * @throws IOException if the store, the workspace or the session fails
	 * @throws InterruptedException if the thread is interrupted while it waits for the session
	 */
	private CommandResult reviseBody(String campaign, String feedback)
		throws CommandRefusedException, IOException, InterruptedException
	{
		Path root = scope.projectRoot();
		CampaignStore store = scope.store();
		Campaign loaded = require(store, campaign, true);
		ForkView fork = openFork(root, loaded);
		DerivedProposal previous = stored(root, loaded).orElseThrow(() -> new CommandRefusedException(
			"Campaign " + campaign + " has no proposal to revise. Run `start` again to derive one."));
		if (loaded.isConfirmed() && !loaded.revisionRequested())
		{
			apply(root, loaded, List.of(new CampaignEvent.Revise()));
			loaded = require(store, campaign, true);
		}
		return derive(root, store, loaded, fork, previous, feedback);
	}

	@Override
	public CommandResult choose(String campaign, Choice choice, int attempt, int reviewPoint)
		throws CommandRefusedException, IOException, InterruptedException
	{
		return withCleanup(notices ->
		{
			Path root = scope.projectRoot();
			Campaign loaded = require(scope.store(), campaign, true);
			if (choice == Choice.PROPOSED_CHANGE && loaded.choices().contains(choice) &&
				loaded.pending() instanceof PendingDecision.TestRevisionBlocked blocked)
			{
				// The contract author derives the revised contract from the change, which the user then confirms
				return reviseBody(campaign, blocked.proposedChange());
			}
			CommandResult result = apply(root, loaded, List.of(new CampaignEvent.Choose(choice, attempt, reviewPoint)));
			Campaign chosen = require(scope.store(), campaign, true);
			DeliveryResult delivery = null;
			if (chosen.lifecycle() == Lifecycle.FINISHED && (chosen.delivery() != null || chosen.isIncumbentKept()))
			{
				// A choice that ends the campaign delivers the selected candidate, or the kept incumbent's prompt tests
				SliceResult slice = SliceRunner.run(context(root, chosen), campaign, List.of());
				delivery = Optional.ofNullable(slice.delivery()).map(DeliveryReport::result).orElse(null);
				result = new CommandResult(slice.status(), scope.harness(), null, slice.delivery(), null);
			}
			noticeOf(cleanup().end(require(scope.store(), campaign, true), delivery), notices);
			return result;
		});
	}

	@Override
	public CommandResult resolveConflict(String campaign, int resolution)
		throws CommandRefusedException, IOException, InterruptedException
	{
		return withCleanup(_ -> resolveConflictBody(campaign, resolution));
	}

	/**
	 * Records a resolution of the current conflict, revising the proposal after the final conflict's choice.
	 *
	 * @param campaign the campaign's identifier
	 * @param resolution the 1-based position among the current conflict's resolutions
	 * @return the result
	 * @throws CommandRefusedException if the campaign offers no such resolution now
	 * @throws IOException if the store, the workspace or the session fails
	 * @throws InterruptedException if the thread is interrupted while it waits for the session
	 */
	private CommandResult resolveConflictBody(String campaign, int resolution)
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
			return reviseBody(campaign, conflicts.feedback(resolution));
		return apply(root, loaded, List.of(new CampaignEvent.ResolveConflict(resolution)));
	}

	@Override
	public CommandResult cancel(String campaign) throws CommandRefusedException, IOException
	{
		return withCleanup(_ ->
		{
			Path root = scope.projectRoot();
			Campaign loaded = require(scope.store(), campaign, true);
			return apply(root, loaded, List.of(new CampaignEvent.Cancel()));
		});
	}

	/**
	 * Returns the cleanup of the project's campaigns.
	 *
	 * @return the cleanup
	 * @throws CommandRefusedException if the working directory is not inside a Git repository
	 * @throws IOException if the project root cannot be resolved
	 */
	private Cleanup cleanup() throws CommandRefusedException, IOException
	{
		return new Cleanup(scope.projectRoot(), scope.sessionDirectory().campaigns(), scope.forks(),
			scope.sessions().runRoots(), scope.json());
	}

	/**
	 * Applies the user's events through the campaign's driver, without running a slice.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param events the events
	 * @return the campaign's status after the events
	 * @throws CommandRefusedException if another process runs the campaign or the campaign does not accept an event
	 * @throws IOException if the store or the workspace fails
	 */
	private CommandResult apply(Path root, Campaign campaign, List<CampaignEvent> events)
		throws CommandRefusedException, IOException
	{
		String id = campaign.header().id();
		DriverContext context = context(root, campaign);
		Optional<Driver> opened = Driver.open(context, id);
		if (opened.isEmpty())
		{
			throw new CommandRefusedException("Campaign " + id + " is running in another process. Wait for its " +
				"slice to end, then try again.");
		}
		try (Driver driver = opened.get())
		{
			for (CampaignEvent event : events)
				driver.apply(event);
		}
		catch (IllegalStateException | IllegalArgumentException e)
		{
			throw new CommandRefusedException("Campaign " + id + " does not accept this command now: " +
				e.getMessage(), e);
		}
		return report(root, require(scope.store(), id, true), null, null);
	}

	/**
	 * Runs the contract author and presents its proposal.
	 *
	 * @param root the project root
	 * @param store persists the campaign's observed usage
	 * @param campaign the campaign
	 * @param fork the campaign's fork
	 * @param previous the proposal that the user gave feedback on, or {@code null} for the first derivation
	 * @param feedback the user's feedback, or {@code null} for the first derivation
	 * @return the result. If the contract author fails, the result carries the reason instead of a proposal.
	 * @throws IOException if the store, the workspace or the session fails
	 * @throws InterruptedException if the thread is interrupted while it waits for the session
	 */
	private CommandResult derive(Path root, CampaignStore store, Campaign campaign, ForkView fork,
		DerivedProposal previous, String feedback) throws IOException, InterruptedException
	{
		Path directory = campaignDirectory(campaign.header().id());
		Overlay incumbent = new FileCandidateOverlays(directory, fork.inventory(), fork::version, scope.json()).
			require(campaign.incumbent().snapshot());
		// The contract author reads the regression scenarios, so nothing is left out
		Path staged = fork.workspace().stage(incumbent, Set.of());
		ContractAuthorProtocol protocol = contractAuthor(root, campaign);
		ProfileEvidence profiles = profileEvidence(staged);
		String unsupported = unsupportedBindings(profiles);
		if (!unsupported.isEmpty())
			return report(root, campaign, null, unsupported);
		List<PluginBase> bases = bases(campaign.plugins().orElseThrow(), fork.workspace());
		List<HookRegistration> hooks = scope.sessions().hookRegistrations(staged, bases);
		SessionRequest request;
		long salt;
		String invalidRetry;
		if (previous == null)
		{
			salt = scope.random().nextLong();
			request = protocol.request(staged, profiles, hooks, salt);
			invalidRetry = "Run `start` again to retry.";
		}
		else
		{
			// Revision keeps the salt, so the test cases that the user reviewed keep their parts
			salt = previous.contract().salt();
			request = protocol.request(staged, profiles, hooks, previous, feedback,
				pilotFile(campaign).read());
			// The proposal that the user gave feedback on is still stored, so revise can regenerate it again
			invalidRetry = "Run `revise` with feedback, or `start`, to retry.";
		}
		SessionResult result = await(scope.sessions().start(request));
		SessionResult.Completed completed;
		switch (result)
		{
			case SessionResult.Completed ended -> completed = ended;
			case SessionResult.Failed failed ->
			{
				return report(root, campaign, null, "The contract author session did not complete: " + failed.reason() +
					". Run `start` again to retry.");
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
		derived = derived.withDerivedSources(scope.sessions().loadedSources(root, bases).trials());
		// The draft's values files hold the expected outcomes of both parts, so it moves into the held-out directory
		Path heldOut = heldOutDirectory(campaign.header().id());
		derived = derived.withDraft(DraftDirectory.store(derived.draft(), heldOut), derived.changes());
		new ProposalFile(heldOut, scope.json()).write(derived, profiles);
		return present(root, store, campaign, fork, derived);
	}

	/**
	 * Runs the guard pilot of a proposal, reusing the stored pilot of each prompt test that it still describes, then
	 * presents the proposal with the pilot's results. The pilot's control trials install the campaign's trial
	 * plugins. The fork first
	 * records the proposal's trial fixtures and derived prompt sources, so the pilot's trials see the files that formal
	 * trials will see. Before confirmation, the fork also leaves out those that an earlier proposal needed and this one
	 * does not. After confirmation, nothing leaves the fork, and the campaign stays on its current fork until the user
	 * confirms the revision.
	 *
	 * @param root the project root
	 * @param store the store
	 * @param campaign the campaign
	 * @param fork the campaign's fork
	 * @param derived the proposal
	 * @return the result
	 * @throws IOException if the host's login, the stored pilot or the workspace cannot be read, or the pilot or the
	 *   campaign cannot be stored
	 * @throws InterruptedException if the thread is interrupted while it waits for a pilot session
	 */
	private CommandResult present(Path root, CampaignStore store, Campaign campaign, ForkView fork,
		DerivedProposal derived)
		throws IOException, InterruptedException
	{
		Participants participants = participants(root, derived);
		if (campaign.isConfirmed())
		{
			// The confirmed campaign keeps its fork until the user confirms the revision that needs a larger one
			if (!unheld(fork, participants).isEmpty())
				fork = fork.extend(root, participants.existing());
		}
		else
		{
			ForkView selected = fork.selectParticipants(root, participants.existing());
			if (!selected.inventory().equals(fork.inventory()))
				campaign = rebind(store, campaign, selected.inventory());
			fork = selected;
		}

		PluginCheck plugin = pluginCheck(root, campaign, fork);
		Map<String, PilotResult> pilots = pilot(root, campaign, fork, derived, plugin);
		Proposal proposal = proposal(root, campaign, fork, derived, pilots, plugin);
		return report(root, campaign, proposal.render(), null);
	}

	/**
	 * Runs the guard pilot of a proposal, reusing the stored pilot of each prompt test that it still describes, and
	 * stores the result. The pilot's control trials install the campaign's trial plugins.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param fork the fork that holds the proposal's trial fixtures and derived prompt sources
	 * @param derived the proposal
	 * @param plugin the check of the campaign's trial plugins
	 * @return the pilot of each prompt test, by prompt test, or none if control trials cannot remove the dependents
	 * @throws IOException if the stored pilot or the workspace cannot be read, or the pilot cannot be stored
	 * @throws InterruptedException if the thread is interrupted while it waits for a pilot session
	 */
	private Map<String, PilotResult> pilot(Path root, Campaign campaign, ForkView fork, DerivedProposal derived,
		PluginCheck plugin) throws IOException, InterruptedException
	{
		// Control trials cannot run without dependents that they cannot remove
		if (!controlRefusals(root, campaign, fork, derived, plugin).isEmpty())
			return Map.of();
		FileInventory inventory = fork.inventory();
		PilotFile file = pilotFile(campaign);
		Overlay control = ControlSource.of(fork.workspace(), inventory, relativeTarget(root, campaign),
			derived.contract().projectRemovals(), scope.json());
		GuardPilot pilot = new GuardPilot(scope.sessions(), fork.workspace(), judgePrompt(campaign), schema,
			scope.json(), campaign.ceiling());
		Set<Path> excluded = PromptTestLocations.excluded(derived.contract(), relativeTarget(root, campaign), root);
		Map<String, PilotResult> pilots = pilot.run(derived.contract(), plugin.plugins(), control, excluded,
			trialSelection(root, campaign, derived, plugin), file.read(), scope.random());
		file.write(pilots);
		return pilots;
	}

	/**
	 * Returns the files of a candidate that every trial of a proposal receives.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param derived the proposal
	 * @param plugin the check of the campaign's trial plugins
	 * @return the selection
	 */
	private SourceSelection trialSelection(Path root, Campaign campaign, DerivedProposal derived, PluginCheck plugin)
	{
		return TrialSelection.base(scope.harness(), promptSources(root, campaign, derived),
			Set.copyOf(scope.loaderConfiguration()), derived.contract(), plugin.plugins());
	}

	/**
	 * Binds a campaign that waits for its first confirmed contract to the incumbent of its fork's changed files. The
	 * incumbent overlay is saved for the new files before the campaign refers to it.
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
		new FileCandidateOverlays(campaignDirectory(campaign.header().id()), inventory, scope.json()).save(incumbent);
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
		return new PilotFile(heldOutDirectory(campaign.header().id()), scope.json());
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
		CertificationHistory history = history(root, relativeTarget(root, campaign));
		if (!history.records().isEmpty())
		{
			// Compare each harness's latest record with the fork's files and the campaign's known conditions
			incumbent = certificationStatus(history, inventory::entry, campaign.identity(),
				record -> derived.contract().minimumPassRate());
		}
		return Proposal.of(derived, campaign, links, relativeTarget(root, campaign), scope.sessions().hostLogin(),
			scope.calibrator(), schema, incumbent, pilots, plugin, controlRefusals(root, campaign, fork, derived, plugin));
	}

	/**
	 * Checks that control trials can remove the dependents of the target that a proposal lists: from the fork's files,
	 * and from the base of each trial plugin that the project builds.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param fork the campaign's fork
	 * @param derived the proposal
	 * @param plugin the check of the campaign's trial plugins
	 * @return the reasons that block confirmation; empty if control trials can remove every dependent
	 * @throws IOException if a file of the fork or of the plugin's base cannot be read
	 */
	private List<String> controlRefusals(Path root, Campaign campaign, ForkView fork, DerivedProposal derived,
		PluginCheck plugin) throws IOException
	{
		Contract contract = derived.contract();
		if (contract.dependents().isEmpty())
			return List.of();
		Path target = relativeTarget(root, campaign);
		List<String> problems = new ArrayList<>(ControlSource.problems(fork.workspace(), fork.inventory(), target,
			contract.projectRemovals(), scope.json()));
		Set<String> projectBuilt = new HashSet<>();
		for (TrialPlugin trialPlugin : plugin.plugins())
		{
			if (!trialPlugin.isProjectBuilt())
				continue;
			projectBuilt.add(trialPlugin.name());
			problems.addAll(ControlSource.pluginProblems(trialPlugin,
				fork.workspace().pluginBase(trialPlugin.baseDigest()), target, contract.pluginRemovals(trialPlugin),
				scope.json()));
		}
		for (Dependent dependent : contract.dependents())
		{
			// Trials install the user's copy of any other plugin as it is, so they cannot remove a part of it
			if (dependent.location() == Dependent.Location.PLUGIN && !projectBuilt.contains(dependent.plugin()))
			{
				problems.add("the dependent " + PortablePaths.text(dependent.path()) + " lies in the plugin " +
					dependent.plugin() + ", which is not a trial plugin that the project builds");
			}
		}
		if (problems.isEmpty())
			return List.of();
		return List.of("Control trials cannot remove the dependents of the target that the proposal lists: " +
			String.join("; ", problems) + ". Run revise with feedback that corrects the dependents.");
	}

	/**
	 * Checks a campaign's trial plugins against the fork's files and the plugins' bases: the prompt files that no
	 * mapping covers, the mapped files that differ from the base's, the files that load in production but not in
	 * trials, and the plugins' hooks that trials do not run because the user has not approved them in the harness.
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
		FileInventory inventory = fork.inventory();
		List<TrialPlugin> plugins = campaign.plugins().orElseThrow();
		SortedSet<Path> promptFiles = authority(root, campaign, inventory).promptFiles(inventory);
		List<PluginBase> bases = bases(plugins, fork.workspace());
		LoadedSources loaded = scope.sessions().loadedSources(root, bases);
		Map<String, FileInventory> baseFiles = new HashMap<>();
		List<String> hooks = new ArrayList<>();
		Map<String, Path> baseDirectories = new HashMap<>();
		for (PluginBase base : bases)
		{
			String name = base.plugin().name();
			baseDirectories.put(name, base.directory());
			if (base.plugin().isProjectBuilt())
				baseFiles.put(name, InventoryScanner.scan(base.directory(), Set.of()));
			for (String hook : scope.sessions().unapprovedHooks(base.directory()))
				hooks.add(name + " " + hook);
		}
		PluginMapping.Coverage coverage = PluginMapping.check(inventory, promptFiles,
			loadsFromProject(root, bases, promptFiles), plugins, baseFiles);
		return new PluginCheck(plugins, coverage.uncovered(), coverage.differing(), loaded.productionOnly(), hooks,
			baseDirectories);
	}

	/**
	 * Reads the stored proposal.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @return the proposal, or empty if none is stored
	 * @throws IOException if the stored proposal cannot be read or no longer validates
	 */
	private Optional<DerivedProposal> stored(Path root, Campaign campaign) throws IOException
	{
		Optional<ProposalFile.Stored> stored = new ProposalFile(heldOutDirectory(campaign.header().id()),
			scope.json()).read();
		if (stored.isEmpty())
			return Optional.empty();
		try
		{
			ProposalFile.Stored proposal = stored.get();
			return Optional.of(proposal.restore(contractAuthor(root, campaign).proposal(proposal.derivation(),
				proposal.draft(), proposal.observedSources(), proposal.profiles(), proposal.salt())));
		}
		catch (InvalidProposalException e)
		{
			throw new IOException("The stored proposal is invalid: " + String.join("; ", e.problems()), e);
		}
	}

	/**
	 * Waits for a session.
	 *
	 * @param handle the session
	 * @return the session's result
	 * @throws IOException if the session fails abnormally
	 * @throws InterruptedException if the thread is interrupted. The session is interrupted too.
	 */
	private static SessionResult await(SessionHandle handle) throws IOException, InterruptedException
	{
		try
		{
			return handle.completion().toCompletableFuture().get();
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
	 * @param proposal the rendered proposal, or {@code null}
	 * @param message a notice, or {@code null}
	 * @return the result
	 * @throws IOException if the fork cannot be read
	 */
	private CommandResult report(Path root, Campaign campaign, String proposal, String message) throws IOException
	{
		boolean links = false;
		Path directory = campaignDirectory(campaign.header().id());
		if (scope.forks().exists(directory))
		{
			ForkView fork = scope.forks().open(root, directory);
			FileInventory inventory = fork.inventory();
			links = !LinkReport.of(inventory, authority(root, campaign, inventory)).entries().isEmpty();
		}
		ReportEvidence evidence = new ReportEvidence(List.of(), List.of(), List.of(), links, false, null);
		return new CommandResult(StatusReport.of(campaign, evidence), campaign.header().harness(), proposal, null,
			message);
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
	 * @return the context
	 * @throws CommandRefusedException if the campaign holds no fork
	 * @throws IOException if the fork cannot be read
	 */
	private DriverContext context(Path root, Campaign campaign) throws CommandRefusedException, IOException
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
			Set.copyOf(scope.loaderConfiguration()));
		ValuesPrompt values = new ValuesPrompt(scope.json(), schema, valuesDirectory,
			new SplittableRandom(scope.random().nextLong()));
		return new DriverContext(store, scope.sessions(), fork.workspace(), scope.interrupts(), scope.clock(),
			new FileCandidateOverlays(directory, inventory, fork::version, scope.json()), judge, triage, promptAuthor,
			values,
			new Delivery(store, fork.workspace(), scope.forks(), scope.json())::deliver, scope.calibrator(),
			new ProcessHandleTerminator(), new LeftoverRuns(scope.sessions(), scope.json()), authority,
			promptSources, environment(inventory::entry), lockFile(campaign.header().id()), scope.random(),
			scope.sliceSettings(), scope.json());
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
		return new Authority(promptSources(root, campaign), Set.copyOf(scope.loaderConfiguration()), inventory);
	}

	/**
	 * Returns the in-project prompt sources of a campaign: the target and every in-project derived source of the
	 * stored proposal. External derived sources are participants but never prompt sources, so they stay read-only.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @return the sources, relative to the project
	 * @throws IOException if the stored proposal cannot be read
	 */
	private Set<Path> promptSources(Path root, Campaign campaign) throws IOException
	{
		Optional<DerivedProposal> derived = stored(root, campaign);
		if (derived.isPresent())
			return promptSources(root, campaign, derived.get());
		return Set.of(relativeTarget(root, campaign));
	}

	/**
	 * Returns the in-project prompt sources of a proposal: the campaign's target and every in-project derived source.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param derived the proposal
	 * @return the sources, relative to the project
	 */
	private Set<Path> promptSources(Path root, Campaign campaign, DerivedProposal derived)
	{
		Set<Path> sources = new LinkedHashSet<>();
		sources.add(relativeTarget(root, campaign));
		for (Path path : derivedPaths(root, derived))
		{
			if (!path.isAbsolute())
				sources.add(path);
		}
		return sources;
	}

	/**
	 * Returns a campaign's target relative to the project.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @return the relative path
	 */
	private static Path relativeTarget(Path root, Campaign campaign)
	{
		Path target = campaign.header().target();
		if (target.isAbsolute())
			return root.relativize(target);
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
		values.put(MeasurementCondition.JUDGE, String.valueOf(scope.sessions().judgeVersion()));
		values.put(MeasurementCondition.RUNNER_VERSION, PluginInfo.CAT_VERSION);
		values.put(MeasurementCondition.CAT_VERSION, PluginInfo.CAT_VERSION);
		values.put(MeasurementCondition.LOADER_CONFIGURATION, loaderConfiguration(files));
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

	/**
	 * Returns the identity of the loader configuration files.
	 *
	 * @param files reads the files
	 * @return a digest of the files' paths and contents
	 * @throws IOException if a file cannot be read
	 */
	private String loaderConfiguration(Entries files) throws IOException
	{
		StringBuilder lines = new StringBuilder();
		for (Path path : scope.loaderConfiguration().stream().sorted().toList())
		{
			lines.append(PortablePaths.text(path)).append('=');
			Optional<FileEntry> entry = files.entry(path);
			if (entry.isEmpty())
				lines.append("absent");
			else
			{
				switch (entry.get())
				{
					case FileEntry.RegularFile file -> lines.append(file.contentHash().hex());
					case FileEntry.SymbolicLink link -> lines.append("link:").append(PortablePaths.text(link.target()));
					case FileEntry.SpecialFile _ -> lines.append("special");
				}
			}
			lines.append('\n');
		}
		return Sha256.of(lines.toString().getBytes(StandardCharsets.UTF_8)).hex();
	}
}
