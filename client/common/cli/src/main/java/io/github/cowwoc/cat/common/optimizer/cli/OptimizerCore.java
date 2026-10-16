package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.cat.common.optimizer.store.FileCampaignStore.directory;
import static io.github.cowwoc.cat.common.optimizer.store.FileCampaignStore.heldOutDirectory;
import static io.github.cowwoc.cat.common.optimizer.store.FileCampaignStore.lockFile;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.PluginInfo;
import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationRecord;
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
import io.github.cowwoc.cat.common.optimizer.contract.PluginUnderTest;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileCatalog;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTest;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestLocations;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
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
import io.github.cowwoc.cat.common.optimizer.port.ProfileInventory;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.proposal.DerivedProposal;
import io.github.cowwoc.cat.common.optimizer.contract.DerivedSource;
import io.github.cowwoc.cat.common.optimizer.proposal.ContractAuthorProtocol;
import io.github.cowwoc.cat.common.optimizer.proposal.JudgeTemplate;
import io.github.cowwoc.cat.common.optimizer.proposal.ProfileEvidence;
import io.github.cowwoc.cat.common.optimizer.proposal.RegressionScenarios;
import io.github.cowwoc.cat.common.optimizer.proposal.PluginCheck;
import io.github.cowwoc.cat.common.optimizer.proposal.Proposal;
import io.github.cowwoc.cat.common.optimizer.proposal.TestRevisionProtocol;
import io.github.cowwoc.cat.common.optimizer.report.CertificationStatus;
import io.github.cowwoc.cat.common.optimizer.report.ReportEvidence;
import io.github.cowwoc.cat.common.optimizer.report.StatusReport;
import io.github.cowwoc.cat.common.optimizer.store.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.store.CertificationRecordJson;
import io.github.cowwoc.cat.common.optimizer.store.FileCandidateOverlays;
import io.github.cowwoc.cat.common.optimizer.workspace.ControlSource;
import io.github.cowwoc.cat.common.optimizer.workspace.ForkView;
import io.github.cowwoc.cat.common.optimizer.workspace.LinkReport;
import io.github.cowwoc.cat.common.optimizer.workspace.WorkspaceException;

import java.io.IOException;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Implements the commands on the real store, fork, driver and sessions of one project.
 * <p>
 * Every command that changes a campaign first checks that the harness that runs it started the campaign, and refuses
 * before it changes anything otherwise. {@code status} is the exception: either harness may report any campaign.
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
	private final OptimizerCommandScope scope;
	private final ProposalSchema schema;
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
	 * harness's role profile and the files that load in production.
	 *
	 * @param root the original project root, where scenarios are recorded
	 * @param campaign the campaign, whose header holds the target and the user's goal
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
			scope.sessions().derivedSources(root, null));
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
		Registry.Opening opening = Registry.open(store.listHeaders(), root, absolute,
			scope.harness());
		return switch (opening)
		{
			case Registry.Opening.Refuse refuse -> throw refused(refuse.owner());
			case Registry.Opening.Resume resume -> resume(root, store, resume.header());
			case Registry.Opening.Create _ -> create(root, store, absolute, ceiling, goal);
		};
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
			case Cleanup.Removal.Removed _ -> notices.add("Removed the campaign's files from .cat/work/.");
			case Cleanup.Removal.Failed failed -> notices.add(failed.warning());
			case Cleanup.Removal.Kept _, Cleanup.Removal.Deferred _ ->
			{
				// The files stay, or another process holds the lock and a later command removes them silently
			}
		}
	}

	/**
	 * Returns the refusal for a campaign that the other harness started.
	 *
	 * @param header the campaign
	 * @return the exception to throw
	 */
	private static CommandRefusedException refused(Header header)
	{
		String owner = header.harness().commandName();
		return new CommandRefusedException("Campaign " + header.id() + " for " + header.target() +
			" was started by " + owner + " and can only be resumed there. Use `status " + header.id() +
			"` to read it from here.");
	}

	/**
	 * Resumes a campaign that this harness started.
	 *
	 * @param root the project root
	 * @param store the store
	 * @param header the campaign's header
	 * @return the result
	 * @throws CommandRefusedException if the campaign cannot be shown
	 * @throws IOException if the store or the workspace fails
	 * @throws InterruptedException if the thread is interrupted while it waits for a session
	 */
	private CommandResult resume(Path root, CampaignStore store, Header header)
		throws CommandRefusedException, IOException, InterruptedException
	{
		Campaign campaign = require(store, header.id(), true);
		if (campaign.isConfirmed() && !campaign.revisionRequested())
			return report(root, campaign, null, null);
		ForkView fork = openFork(root, campaign);
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
	 * @throws CommandRefusedException if the base worktree has uncommitted changes
	 * @throws IOException if the store, the workspace or a session fails
	 * @throws InterruptedException if the thread is interrupted while it waits for a session
	 */
	private CommandResult create(Path root, CampaignStore store, Path target, int ceiling, String goal)
		throws CommandRefusedException, IOException, InterruptedException
	{
		String id = ID_TIME.format(scope.clock().instant()) + "-" +
			HexFormat.of().toHexDigits(scope.random().nextInt()).substring(Integer.BYTES * 2 - RANDOM_ID_DIGITS);
		Path directory = directory(root, id);
		Path relative = root.relativize(target);
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
		CommandResult result = derive(root, store, campaign, fork, null, null);
		if (!fork.excludeAdded())
			return result;
		String message = "Added " + ForkView.EXCLUDE_ENTRY + " to the repository's local info/exclude.";
		if (result.message() != null)
			message += "\n" + result.message();
		return new CommandResult(result.status(), result.harness(), result.proposal(), result.delivery(), message);
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
		ProposalFile.Stored stored = new ProposalFile(heldOutDirectory(root, id), scope.json()).read().
			orElseThrow(() -> new IOException("Campaign " + id + " has no stored confirmed proposal"));
		ContractAuthorProtocol contractAuthor = contractAuthor(root, campaign);
		DerivedProposal current;
		try
		{
			current = stored.restore(contractAuthor.proposal(stored.derivation(), stored.observedSources(),
				stored.profiles(), stored.salt()));
		}
		catch (InvalidProposalException e)
		{
			throw new IOException("The stored proposal is invalid: " + String.join("; ", e.problems()), e);
		}
		TestRevisionProtocol protocol = new TestRevisionProtocol(scope.json(), schema, contractAuthor);
		ForkView fork = openFork(root, campaign);
		Overlay incumbent = new FileCandidateOverlays(directory(root, id), fork.inventory(), fork::version,
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
		PluginCheck plugin = pluginCheck(root, campaign, fork.inventory(), revised);
		Map<String, PilotResult> pilots = pilot(root, campaign, fork, revised, plugin);
		// The proposal previews its confirmation, which needs the campaign that awaits the revised contract
		CampaignEvent.ReviseTests revise = new CampaignEvent.ReviseTests(revisions);
		Proposal proposal = proposal(root, Transitions.apply(campaign, revise), fork, revised, pilots, plugin);
		CertificationScope certificationScope = campaign.contract().profiles().scope();
		if (!proposal.isConfirmable(certificationScope))
			throw new CommandRefusedException(proposal.confirmationRefusal(certificationScope));
		requireParticipantsHeld(root, fork, revised);
		if (proposal.contract().plugin().orElse(null) instanceof PluginUnderTest.Built built)
			fork.workspace().copyPluginBase(built);
		// The stored proposal changes only once nothing blocks confirming it, so it stays the confirmed contract's
		new ProposalFile(heldOutDirectory(root, campaign.header().id()), scope.json()).write(revised,
			stored.profiles());

		List<CampaignEvent> events = new ArrayList<>();
		events.add(revise);
		events.addAll(moveOnto(root, campaign, fork));
		events.addAll(proposal.confirmation(scope.random().nextLong(), certificationScope));
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
		}

		Path absolute = scope.directory().toRealPath().resolve(subject).normalize();
		// A target that no longer exists may still have a campaign or a record
		if (Files.exists(absolute))
			absolute = absolute.toRealPath();
		requireInsideProject(root, absolute);
		Optional<Header> header = Registry.ofTarget(store.listHeaders(), root, absolute);
		if (header.isPresent() && header.get().lifecycle().isResumable())
			return report(root, require(store, header.get().id(), false), null, null);

		// A finished campaign must not hide a certification that a later campaign delivered
		Path relative = root.relativize(absolute);
		Optional<CertificationRecord> record = record(root, relative);
		if (record.isEmpty() && header.isPresent())
			return report(root, require(store, header.get().id(), false), null, null);
		if (record.isEmpty())
		{
			String absent = "no campaign or certification record exists for " + relative + ".";
			if (isId)
				throw new CommandRefusedException("No campaign has the identifier " + subject + ", and " + absent);
			throw new CommandRefusedException("N" + absent.substring(1));
		}
		Map<Path, Sha256> promptFiles = new TreeMap<>();
		for (Path path : record.get().promptFiles().keySet())
		{
			Optional<FileEntry> entry = InventoryScanner.entry(root, path);
			if (entry.isPresent() && entry.get() instanceof FileEntry.RegularFile file)
				promptFiles.put(path, file.contentHash());
		}
		MeasurementIdentity known = environment(path -> InventoryScanner.entry(root, path));
		return CommandResult.of(new CertificationStatus(record.get(),
			record.get().applicability(promptFiles, known, record.get().parameters().minimumPassRate())));
	}

	/**
	 * Reads the certification record of a target.
	 *
	 * @param root the project root
	 * @param target the target, relative to the project
	 * @return the record, or empty if the target has none
	 * @throws IOException if the record exists but cannot be read
	 */
	private Optional<CertificationRecord> record(Path root, Path target) throws IOException
	{
		Path file = root.resolve(CertificationRecord.location(target));
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		return Optional.of(new CertificationRecordJson(scope.json()).read(file));
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
			Proposal proposal = proposal(root, loaded, fork, derived, pilotFile(root, loaded).read(),
				pluginCheck(root, loaded, fork.inventory(), derived));
			if (!proposal.isConfirmable(certificationScope))
				throw new CommandRefusedException(proposal.confirmationRefusal(certificationScope));
			requireParticipantsHeld(root, fork, derived);
			if (proposal.contract().plugin().orElse(null) instanceof PluginUnderTest.Built built)
				fork.workspace().copyPluginBase(built);
			List<CampaignEvent> events = new ArrayList<>(moveOnto(root, loaded, fork));
			events.addAll(proposal.confirmation(scope.random().nextLong(), certificationScope));
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
	 * @throws CommandRefusedException if a fixture does not exist, the fork lacks a file that the proposal needs, or a
	 *   fixture file of a prompt test would replace a file of the fork
	 */
	private static void requireParticipantsHeld(Path root, ForkView fork, DerivedProposal derived)
		throws CommandRefusedException
	{
		Set<String> collisions = fixtureCollisions(fork.inventory(), derived.contract());
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
	 * Returns the fixture files of a contract's prompt tests that would replace a file of the fork, or that need a
	 * directory where the fork holds a file.
	 *
	 * @param inventory the files of the fork
	 * @param contract the contract
	 * @return the colliding paths of every test case, relative to the workspace root
	 */
	private static Set<String> fixtureCollisions(FileInventory inventory, Contract contract)
	{
		Set<String> collisions = new TreeSet<>();
		for (PromptTest test : contract.tests())
		{
			for (TestCase testCase : test.allTestCases())
			{
				for (String target : test.fixtureTargets(testCase).keySet())
				{
					Path path = Path.of(target);
					boolean collides = inventory.holds(path);
					for (Path parent = path.getParent(); parent != null && !collides; parent = parent.getParent())
						collides = inventory.entry(parent).isPresent();
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
	 * @param root the project root
	 * @param campaign the campaign
	 * @param fork the fork that the revised contract runs on
	 * @return the event that moves the campaign, or none if the campaign is not confirmed or is on that fork already
	 * @throws CommandRefusedException if the working candidate's edits do not apply to the larger fork
	 * @throws IOException if the overlays, the fork's selection or the worktree cannot be read or written
	 */
	private List<CampaignEvent> moveOnto(Path root, Campaign campaign, ForkView fork)
		throws CommandRefusedException, IOException
	{
		if (!campaign.isConfirmed())
			return List.of();
		FileInventory larger = fork.inventory();
		FileCandidateOverlays overlays = new FileCandidateOverlays(directory(root, campaign.header().id()), larger,
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
		return new Cleanup(scope.projectRoot(), scope.forks(), scope.sessions().runRoots(), scope.json());
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
		Path directory = directory(root, campaign.header().id());
		Overlay incumbent = new FileCandidateOverlays(directory, fork.inventory(), fork::version, scope.json()).
			require(campaign.incumbent().snapshot());
		// The contract author reads the regression scenarios, so nothing is left out
		Path staged = fork.workspace().stage(incumbent, Set.of());
		ContractAuthorProtocol protocol = contractAuthor(root, campaign);
		ProfileEvidence profiles = profileEvidence(staged);
		String unsupported = unsupportedBindings(profiles);
		if (!unsupported.isEmpty())
			return report(root, campaign, null, unsupported);
		SessionRequest request;
		long salt;
		String invalidRetry;
		if (previous == null)
		{
			salt = scope.random().nextLong();
			request = protocol.request(staged, profiles, salt);
			invalidRetry = "Run `start` again to retry.";
		}
		else
		{
			// Revision keeps the salt, so the test cases that the user reviewed keep their parts
			salt = previous.contract().salt();
			request = protocol.request(staged, profiles, previous, feedback, pilotFile(root, campaign).read());
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
			derived = protocol.proposal(completed, profiles, salt);
		}
		catch (InvalidProposalException e)
		{
			return report(root, campaign, null, "The contract author's proposal is invalid: " +
				String.join("; ", e.problems()) + ". " + invalidRetry);
		}
		derived = derived.withDerivedSources(scope.sessions().derivedSources(root,
			derived.contract().plugin().orElse(null)));
		new ProposalFile(heldOutDirectory(root, campaign.header().id()), scope.json()).write(derived, profiles);
		return present(root, store, campaign, fork, derived);
	}

	/**
	 * Runs the guard pilot of a proposal, reusing the stored pilot of each prompt test that it still describes, then
	 * presents the proposal with the pilot's results. The pilot's control trials install the plugin under test as the
	 * plugin check records it; a proposal whose plugin under test the check refuses runs no pilot. The fork first
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
				campaign = rebind(root, store, campaign, selected.inventory());
			fork = selected;
		}

		PluginCheck plugin = pluginCheck(root, campaign, fork.inventory(), derived);
		Map<String, PilotResult> pilots = pilot(root, campaign, fork, derived, plugin);
		Proposal proposal = proposal(root, campaign, fork, derived, pilots, plugin);
		return report(root, campaign, proposal.render(), null);
	}

	/**
	 * Runs the guard pilot of a proposal, reusing the stored pilot of each prompt test that it still describes, and
	 * stores the result. The pilot's control trials install the plugin under test as the plugin check records it.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param fork the fork that holds the proposal's trial fixtures and derived prompt sources
	 * @param derived the proposal
	 * @param plugin the check of the proposal's plugin under test
	 * @return the pilot of each prompt test, by prompt test, or none if the check refuses the plugin under test
	 * @throws IOException if the stored pilot or the workspace cannot be read, or the pilot cannot be stored
	 * @throws InterruptedException if the thread is interrupted while it waits for a pilot session
	 */
	private Map<String, PilotResult> pilot(Path root, Campaign campaign, ForkView fork, DerivedProposal derived,
		PluginCheck plugin) throws IOException, InterruptedException
	{
		// Control trials cannot install a plugin under test that the check refuses, or run without dependents that
		// they cannot remove
		if (!plugin.refusals().isEmpty() || !controlRefusals(root, campaign, fork, derived, plugin).isEmpty())
			return Map.of();
		FileInventory inventory = fork.inventory();
		PilotFile file = pilotFile(root, campaign);
		Overlay control = ControlSource.of(fork.workspace(), inventory, relativeTarget(root, campaign),
			derived.contract().projectRemovals(), scope.json());
		GuardPilot pilot = new GuardPilot(scope.sessions(), fork.workspace(), judgePrompt(root, campaign), schema,
			scope.json(), campaign.ceiling());
		Set<Path> excluded = PromptTestLocations.excluded(derived.contract(), relativeTarget(root, campaign), root);
		// The control trials install the plugin as the check recorded its base, as formal trials will
		Contract checked = derived.contract().withPlugin(plugin.plugin().orElse(null));
		Map<String, PilotResult> pilots = pilot.run(checked, control, excluded, file.read(), scope.random());
		file.write(pilots);
		return pilots;
	}

	/**
	 * Binds a campaign that waits for its first confirmed contract to the incumbent of its fork's changed files. The
	 * incumbent overlay is saved for the new files before the campaign refers to it.
	 *
	 * @param root the project root
	 * @param store the store
	 * @param campaign the campaign
	 * @param inventory the fork's files
	 * @return the rebound campaign
	 * @throws IOException if the overlay or the campaign cannot be stored
	 */
	private Campaign rebind(Path root, CampaignStore store, Campaign campaign, FileInventory inventory)
		throws IOException
	{
		Overlay incumbent = Overlay.of(inventory);
		new FileCandidateOverlays(directory(root, campaign.header().id()), inventory, scope.json()).save(incumbent);
		Campaign rebound = campaign.withIncumbent(incumbent.identity());
		store.replaceSnapshot(rebound);
		return rebound;
	}

	/**
	 * Returns the file that holds a campaign's guard pilot.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @return the file
	 */
	private PilotFile pilotFile(Path root, Campaign campaign)
	{
		return new PilotFile(heldOutDirectory(root, campaign.header().id()), scope.json());
	}

	/**
	 * Completes a derived proposal for presentation and confirmation.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param fork the campaign's fork
	 * @param derived the proposal
	 * @param pilots the guard pilot of each prompt test that has one, by prompt test
	 * @param plugin the check of the proposal's plugin under test
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
		Optional<CertificationRecord> record = record(root, relativeTarget(root, campaign));
		if (record.isPresent())
		{
			// Compare the record with the fork's files and the conditions that the campaign knows now
			Map<Path, Sha256> promptFiles = new TreeMap<>();
			for (Path path : record.get().promptFiles().keySet())
			{
				Optional<FileEntry> entry = inventory.entry(path);
				if (entry.isPresent() && entry.get() instanceof FileEntry.RegularFile file)
					promptFiles.put(path, file.contentHash());
			}
			incumbent = new CertificationStatus(record.get(), record.get().applicability(promptFiles,
				campaign.identity(), derived.contract().minimumPassRate()));
		}
		return Proposal.of(derived, campaign, links, relativeTarget(root, campaign), scope.sessions().hostLogin(),
			scope.calibrator(), schema, incumbent, pilots, plugin, controlRefusals(root, campaign, fork, derived, plugin));
	}

	/**
	 * Checks that control trials can remove the dependents of the target that a proposal lists: from the fork's files,
	 * and from the base of a built plugin under test that the check does not refuse.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param fork the campaign's fork
	 * @param derived the proposal
	 * @param plugin the check of the proposal's plugin under test
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
		if (plugin.refusals().isEmpty() && plugin.plugin().orElse(null) instanceof PluginUnderTest.Built built)
		{
			problems.addAll(ControlSource.pluginProblems(built, target, contract.withPlugin(built).pluginRemovals(),
				scope.json()));
		}
		if (problems.isEmpty())
			return List.of();
		return List.of("Control trials cannot remove the dependents of the target that the proposal lists: " +
			String.join("; ", problems) + ". Run revise with feedback that corrects the dependents.");
	}

	/**
	 * Checks the plugin under test of a proposal against the fork's files and the plugin's base, and lists the
	 * plugin's hooks that trials do not run because the user has not approved them in the harness.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param inventory the fork's files
	 * @param derived the proposal
	 * @return the check
	 * @throws IOException if the plugin's base or manifest cannot be read
	 */
	private PluginCheck pluginCheck(Path root, Campaign campaign, FileInventory inventory, DerivedProposal derived)
		throws IOException
	{
		SortedSet<Path> promptFiles = authority(root, campaign, inventory).promptFiles(inventory);
		PluginCheck check = PluginCheck.of(derived.contract().plugin().orElse(null), inventory, promptFiles);
		if (check.plugin().isPresent() && check.refusals().isEmpty())
		{
			Path directory = switch (check.plugin().get())
			{
				case PluginUnderTest.Root plugin -> root.resolve(plugin.directory());
				case PluginUnderTest.Built plugin -> plugin.base();
			};
			return check.withUnapprovedHooks(scope.sessions().unapprovedHooks(directory));
		}
		return check;
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
		Optional<ProposalFile.Stored> stored = new ProposalFile(heldOutDirectory(root, campaign.header().id()),
			scope.json()).read();
		if (stored.isEmpty())
			return Optional.empty();
		try
		{
			return Optional.of(stored.get().restore(contractAuthor(root, campaign).proposal(stored.get().derivation(),
				stored.get().observedSources(), stored.get().profiles(), stored.get().salt())));
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
		Path directory = directory(root, campaign.header().id());
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
			throw new CommandRefusedException("No campaign has the identifier " + id + ".");
		if (sameHarness && campaign.get().header().harness() != scope.harness())
			throw refused(campaign.get().header());
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
			return scope.forks().open(root, directory(root, campaign.header().id()));
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
	 * @param root the project root
	 * @param campaign the campaign
	 * @return the protocol
	 * @throws IOException if the judge's directory cannot be created
	 */
	private JudgePrompt judgePrompt(Path root, Campaign campaign) throws IOException
	{
		Path judgeDirectory = directory(root, campaign.header().id()).resolve("judge");
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
		Path directory = directory(root, campaign.header().id());
		ForkView fork = openFork(root, campaign);
		FileInventory inventory = fork.inventory();
		CampaignStore store = scope.store();
		Authority authority = authority(root, campaign, inventory);
		Path triageDirectory = directory.resolve("triage");
		Files.createDirectories(triageDirectory);
		Path valuesDirectory = directory.resolve("values");
		Files.createDirectories(valuesDirectory);
		Set<Path> promptSources = promptSources(root, campaign);
		JudgePrompt judge = judgePrompt(root, campaign);
		TriagePrompt triage = new TriagePrompt(scope.json(), triageDirectory);
		PromptAuthorPrompt promptAuthor = new PromptAuthorPrompt(scope.json(), schema, promptSources,
			Set.copyOf(scope.loaderConfiguration()));
		ValuesPrompt values = new ValuesPrompt(scope.json(), schema, valuesDirectory,
			new SplittableRandom(scope.random().nextLong()));
		return new DriverContext(store, scope.sessions(), fork.workspace(), scope.interrupts(), scope.clock(),
			new FileCandidateOverlays(directory, inventory, fork::version, scope.json()), judge, triage, promptAuthor,
			values,
			new Delivery(store, fork.workspace(), scope.json())::deliver, scope.calibrator(),
			new ProcessHandleTerminator(), new LeftoverRuns(scope.sessions(), scope.json()), authority,
			promptSources, environment(inventory::entry), lockFile(root, campaign.header().id()), scope.random(),
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
		Set<Path> sources = new LinkedHashSet<>();
		sources.add(relativeTarget(root, campaign));
		Optional<DerivedProposal> derived = stored(root, campaign);
		if (derived.isPresent())
		{
			for (Path path : derivedPaths(root, derived.get()))
			{
				if (!path.isAbsolute())
					sources.add(path);
			}
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
