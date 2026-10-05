package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.cat.common.optimizer.store.FileCampaignStore.directory;
import static io.github.cowwoc.cat.common.optimizer.store.FileCampaignStore.heldOutDirectory;
import static io.github.cowwoc.cat.common.optimizer.store.FileCampaignStore.lockFile;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.PluginInfo;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationRecord;
import io.github.cowwoc.cat.common.optimizer.campaign.Header;
import io.github.cowwoc.cat.common.optimizer.campaign.Registry;
import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.InvalidProposalException;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileCatalog;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.delivery.Delivery;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult;
import io.github.cowwoc.cat.common.optimizer.execution.Cleanup;
import io.github.cowwoc.cat.common.optimizer.execution.DeliveryReport;
import io.github.cowwoc.cat.common.optimizer.execution.Driver;
import io.github.cowwoc.cat.common.optimizer.execution.DriverContext;
import io.github.cowwoc.cat.common.optimizer.execution.LeftoverRuns;
import io.github.cowwoc.cat.common.optimizer.execution.ProcessHandleTerminator;
import io.github.cowwoc.cat.common.optimizer.execution.SliceResult;
import io.github.cowwoc.cat.common.optimizer.execution.SliceRunner;
import io.github.cowwoc.cat.common.optimizer.execution.prompt.AuthorPrompt;
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
import io.github.cowwoc.cat.common.optimizer.proposal.DerivedSource;
import io.github.cowwoc.cat.common.optimizer.proposal.DeriverProtocol;
import io.github.cowwoc.cat.common.optimizer.proposal.JudgeTemplate;
import io.github.cowwoc.cat.common.optimizer.proposal.ProfileEvidence;
import io.github.cowwoc.cat.common.optimizer.proposal.RegressionScenarios;
import io.github.cowwoc.cat.common.optimizer.proposal.Proposal;
import io.github.cowwoc.cat.common.optimizer.report.CertificationStatus;
import io.github.cowwoc.cat.common.optimizer.report.ReportEvidence;
import io.github.cowwoc.cat.common.optimizer.report.StatusReport;
import io.github.cowwoc.cat.common.optimizer.store.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.store.CertificationRecordJson;
import io.github.cowwoc.cat.common.optimizer.store.FileCandidateOverlays;
import io.github.cowwoc.cat.common.optimizer.workspace.ForkView;
import io.github.cowwoc.cat.common.optimizer.workspace.LinkReport;
import io.github.cowwoc.cat.common.optimizer.workspace.WorkspaceException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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
	private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").
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
	 * Returns a deriver protocol with the current recorded scenarios and the harness's role profile.
	 *
	 * @param root the original project root, where scenarios are recorded
	 * @return the protocol
	 * @throws IOException if recorded scenarios or the installed role profile cannot be read
	 */
	private DeriverProtocol deriver(Path root) throws IOException
	{
		return new DeriverProtocol(scope.json(), schema, new JudgeTemplate(scope.json(),
			scope.profiles().roleProfile()), RegressionScenarios.read(root, scope.home()));
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
				bindings.put(file.toString(), binding);
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
		return "The deriver session did not start because the harness does not support a bound model: " +
			String.join("; ", unsupported) + ". Change the binding and run `start` again.";
	}

	@Override
	public CommandResult start(Path target, int ceiling) throws CommandRefusedException, IOException, InterruptedException
	{
		requireThat(target, "target").isNotNull();
		requireThat(ceiling, "ceiling").isBetween(1, true, Campaign.MAXIMUM_CEILING, true);
		return withCleanup(_ -> startBody(target, ceiling));
	}

	/**
	 * Starts or resumes the campaign of a target.
	 *
	 * @param target the target, relative to the working directory
	 * @param ceiling the maximum simultaneous nested sessions of a new campaign
	 * @return the result
	 * @throws CommandRefusedException if the command is refused
	 * @throws IOException if the store, the workspace or a session fails
	 * @throws InterruptedException if the thread is interrupted while it waits for a session
	 */
	private CommandResult startBody(Path target, int ceiling)
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
		CampaignStore store = scope.store();
		Registry.Opening opening = Registry.open(store.listHeaders(), root, absolute,
			scope.harness());
		return switch (opening)
		{
			case Registry.Opening.Refuse refuse -> throw refused(refuse.owner());
			case Registry.Opening.Resume resume -> resume(root, store, resume.header());
			case Registry.Opening.Create _ -> create(root, store, absolute, ceiling);
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
			return present(root, campaign, fork, stored.get());
		return derive(root, store, campaign, fork, null, null);
	}

	/**
	 * Creates a campaign and derives its first proposal.
	 *
	 * @param root the project root
	 * @param store the store
	 * @param target the canonical target
	 * @param ceiling the maximum simultaneous nested sessions of the new campaign
	 * @return the result
	 * @throws CommandRefusedException if the base worktree has uncommitted changes
	 * @throws IOException if the store, the workspace or a session fails
	 * @throws InterruptedException if the thread is interrupted while it waits for a session
	 */
	private CommandResult create(Path root, CampaignStore store, Path target, int ceiling)
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
		Header header = new Header(id, root, target, scope.harness(), Lifecycle.ACTIVE);
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
			SliceResult result = SliceRunner.run(context(root, loaded), campaign, List.of());
			Optional<DeliveryResult> delivery = Optional.ofNullable(result.delivery()).map(DeliveryReport::result);
			noticeOf(cleanup().end(require(scope.store(), campaign, true), delivery), notices);
			return new CommandResult(result.status(), scope.harness(), null, result.delivery(), null);
		});
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
			record.get().applicability(promptFiles, known)));
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
			Proposal proposal = proposal(root, loaded, fork, derived);
			if (!proposal.isConfirmable(certificationScope))
				throw new CommandRefusedException(proposal.confirmationRefusal(certificationScope));
			loaded = recordParticipants(root, store, loaded, fork, proposal, derived);
			return apply(root, loaded, proposal.confirmation(scope.random().nextLong(), certificationScope));
		});
	}

	/**
	 * Records the confirmed contract's trial fixtures and the derived prompt sources that the fork does not hold,
	 * before confirmation writes anything, so that trials never read a live file. The incumbent overlay is saved for
	 * the extended fork before the campaign refers to it.
	 *
	 * @param root the project root
	 * @param store the store
	 * @param campaign the campaign
	 * @param fork the campaign's fork
	 * @param proposal the proposal that the user confirms
	 * @param derived the deriver's proposal
	 * @return the campaign, bound to the extended fork
	 * @throws CommandRefusedException if a fixture does not exist, or the fork lacks a participant that a revision
	 *   would need
	 * @throws IOException if the store or the workspace fails
	 */
	private Campaign recordParticipants(Path root, CampaignStore store, Campaign campaign, ForkView fork,
		Proposal proposal, DerivedProposal derived) throws CommandRefusedException, IOException
	{
		FileInventory held = fork.inventory();
		Set<Path> needed = new LinkedHashSet<>();
		List<String> missing = new ArrayList<>();
		for (String fixture : proposal.contract().fixtures().ignoredPaths())
		{
			Path path = Path.of(fixture);
			if (held.holds(path))
				continue;
			if (InventoryScanner.isOmitted(path) ||
				Files.notExists(root.resolve(path), LinkOption.NOFOLLOW_LINKS))
			{
				missing.add(fixture);
			}
			else
				needed.add(path);
		}
		if (!missing.isEmpty())
		{
			throw new CommandRefusedException("These trial fixtures do not exist in the project, so the trials " +
				"could not see them: " + String.join(", ", missing));
		}
		for (Path source : derivedPaths(root, derived))
		{
			Path location = source;
			if (!source.isAbsolute())
				location = root.resolve(source);
			if (!held.holds(source) && Files.exists(location, LinkOption.NOFOLLOW_LINKS))
				needed.add(source);
		}
		if (needed.isEmpty())
			return campaign;
		if (campaign.isConfirmed())
		{
			throw new CommandRefusedException("The revised contract needs files that the fork does not hold: " +
				needed.stream().map(Path::toString).collect(Collectors.joining(", ")) + ". Start a new campaign " +
				"so that the fork records them.");
		}
		ForkView extended = fork.addParticipants(root, needed);
		FileInventory inventory = extended.inventory();
		Overlay incumbent = Overlay.of(inventory);
		new FileCandidateOverlays(directory(root, campaign.header().id()), inventory, scope.json()).save(incumbent);
		Campaign rebound = Campaign.start(campaign.header(), campaign.ceiling(), incumbent.identity(),
			campaign.identity());
		store.replaceSnapshot(rebound);
		return rebound;
	}

	/**
	 * Returns the derived prompt sources as the fork names them.
	 *
	 * @param root the project root
	 * @param derived the deriver's proposal
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
	public CommandResult choose(String campaign, Choice choice, int attempt, int reviewPoint, Path target)
		throws CommandRefusedException, IOException
	{
		return withCleanup(notices ->
		{
			Path root = scope.projectRoot();
			Campaign loaded = require(scope.store(), campaign, true);
			CommandResult result = apply(root, loaded, List.of(new CampaignEvent.Choose(choice, attempt, reviewPoint,
				target)));
			// Retaining the incumbent ends the campaign without a delivery
			noticeOf(cleanup().end(require(scope.store(), campaign, true), Optional.empty()), notices);
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
			throw new CommandRefusedException("Campaign " + campaign + " does not offer resolution-" +
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
	 * Runs the deriver and presents its proposal.
	 *
	 * @param root the project root
	 * @param store persists the campaign's observed usage
	 * @param campaign the campaign
	 * @param fork the campaign's fork
	 * @param previous the proposal that the user gave feedback on, or {@code null} for the first derivation
	 * @param feedback the user's feedback, or {@code null} for the first derivation
	 * @return the result. If the deriver fails, the result carries the reason instead of a proposal.
	 * @throws IOException if the store, the workspace or the session fails
	 * @throws InterruptedException if the thread is interrupted while it waits for the session
	 */
	private CommandResult derive(Path root, CampaignStore store, Campaign campaign, ForkView fork,
		DerivedProposal previous, String feedback) throws IOException, InterruptedException
	{
		Path directory = directory(root, campaign.header().id());
		Overlay incumbent = new FileCandidateOverlays(directory, fork.inventory(), scope.json()).
			require(campaign.incumbent().snapshot());
		Path staged = fork.workspace().stage(incumbent);
		DeriverProtocol protocol = deriver(root);
		ProfileEvidence profiles = profileEvidence(staged);
		String unsupported = unsupportedBindings(profiles);
		if (!unsupported.isEmpty())
			return report(root, campaign, null, unsupported);
		SessionRequest request;
		if (previous == null)
			request = protocol.request(staged, profiles);
		else
			request = protocol.request(staged, profiles, previous, feedback);
		SessionResult result = await(scope.sessions().start(request));
		if (!(result instanceof SessionResult.Completed completed))
		{
			return report(root, campaign, null, "The deriver session did not complete: " + result +
				". Run `start` again to retry.");
		}

		if (completed.usage() != null)
		{
			campaign = Transitions.apply(campaign, new CampaignEvent.ObserveTokenUsage(SessionRole.DERIVER,
				completed.usage()));
			store.replaceSnapshot(campaign);
		}

		// Revision keeps the salt, so the instances that the user reviewed keep their parts
		long salt;
		if (previous == null)
			salt = scope.random().nextLong();
		else
			salt = previous.contract().salt();
		DerivedProposal derived;
		try
		{
			derived = protocol.proposal(completed, profiles, salt);
		}
		catch (InvalidProposalException e)
		{
			return report(root, campaign, null, "The deriver's proposal is invalid: " +
				String.join("; ", e.problems()) + ". Run `revise` with feedback, or `start`, to retry.");
		}
		new ProposalFile(heldOutDirectory(root, campaign.header().id()), scope.json()).write(derived, profiles);
		return present(root, campaign, fork, derived);
	}

	/**
	 * Presents a proposal.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param fork the campaign's fork
	 * @param derived the proposal
	 * @return the result
	 * @throws IOException if the host's login cannot be read
	 */
	private CommandResult present(Path root, Campaign campaign, ForkView fork, DerivedProposal derived)
		throws IOException
	{
		Proposal proposal = proposal(root, campaign, fork, derived);
		return report(root, campaign, proposal.render(), null);
	}

	/**
	 * Completes a derived proposal for presentation and confirmation.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @param fork the campaign's fork
	 * @param derived the proposal
	 * @return the proposal
	 * @throws IOException if the host's login or the target's certification record cannot be read
	 */
	private Proposal proposal(Path root, Campaign campaign, ForkView fork, DerivedProposal derived) throws IOException
	{
		FileInventory inventory = fork.inventory();
		LinkReport links = LinkReport.of(inventory, authority(root, campaign));
		Optional<CertificationStatus> incumbent = Optional.empty();
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
			incumbent = Optional.of(new CertificationStatus(record.get(),
				record.get().applicability(promptFiles, campaign.identity())));
		}
		return Proposal.of(derived, campaign, links, scope.sessions().hostLogin(), scope.calibrator(),
			schema, incumbent);
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
			scope.json()).read(root);
		if (stored.isEmpty())
			return Optional.empty();
		try
		{
			return Optional.of(deriver(root).proposal(stored.get().result(), stored.get().profiles(),
				stored.get().salt()));
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
			links = !LinkReport.of(fork.inventory(), authority(root, campaign)).entries().isEmpty();
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
		Authority authority = authority(root, campaign);
		Path judgeDirectory = directory.resolve("judge");
		Files.createDirectories(judgeDirectory);
		Path triageDirectory = directory.resolve("triage");
		Files.createDirectories(triageDirectory);
		Path valuesDirectory = directory.resolve("values");
		Files.createDirectories(valuesDirectory);
		Set<Path> promptSources = promptSources(root, campaign);
		JudgePrompt judge = new JudgePrompt(scope.json(), judgeDirectory);
		TriagePrompt triage = new TriagePrompt(scope.json(), triageDirectory);
		AuthorPrompt author = new AuthorPrompt(scope.json(), schema, promptSources,
			Set.copyOf(scope.loaderConfiguration()));
		ValuesPrompt values = new ValuesPrompt(scope.json(), schema, valuesDirectory);
		return new DriverContext(store, scope.sessions(), fork.workspace(), scope.interrupts(), scope.clock(),
			new FileCandidateOverlays(directory, inventory, scope.json()), judge, triage, author, values,
			new Delivery(store, fork.workspace(), scope.json())::deliver, scope.calibrator(),
			new ProcessHandleTerminator(), new LeftoverRuns(scope.sessions(), scope.json()), authority,
			promptSources, environment(inventory::entry), lockFile(root, campaign.header().id()), scope.random(),
			scope.sliceSettings());
	}

	/**
	 * Returns the edit authority of a campaign, with the external targets that the user authorized.
	 *
	 * @param root the project root
	 * @param campaign the campaign
	 * @return the authority
	 */
	private Authority authority(Path root, Campaign campaign) throws IOException
	{
		return new Authority(promptSources(root, campaign), Set.copyOf(scope.loaderConfiguration()),
			campaign.authorizedTargets());
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
			lines.append(path).append('=');
			Optional<FileEntry> entry = files.entry(path);
			if (entry.isEmpty())
				lines.append("absent");
			else
			{
				switch (entry.get())
				{
					case FileEntry.RegularFile file -> lines.append(file.contentHash().hex());
					case FileEntry.SymbolicLink link -> lines.append("link:").append(link.target());
					case FileEntry.SpecialFile _ -> lines.append("special");
				}
			}
			lines.append('\n');
		}
		return Sha256.of(lines.toString().getBytes(StandardCharsets.UTF_8)).hex();
	}
}
