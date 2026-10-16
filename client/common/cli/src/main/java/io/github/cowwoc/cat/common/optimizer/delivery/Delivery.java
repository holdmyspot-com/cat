package io.github.cowwoc.cat.common.optimizer.delivery;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.PortableFiles;
import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.git.Git;
import io.github.cowwoc.cat.common.optimizer.campaign.Applicability;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationRecord;
import io.github.cowwoc.cat.common.optimizer.campaign.DeliveryRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.contract.FixtureFile;
import io.github.cowwoc.cat.common.optimizer.contract.FixturePattern;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestFiles;
import io.github.cowwoc.cat.common.optimizer.contract.Scenario;
import io.github.cowwoc.cat.common.optimizer.contract.ScenarioFormatException;
import io.github.cowwoc.cat.common.optimizer.contract.ValuesFile;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult.ConcurrentEdit;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult.Conflicted;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult.Delivered;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult.UnownedChange;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult.VerificationFailed;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.Create;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.Delete;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.Edit;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryPlan;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SpecialFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryDiff;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryScanner;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.PathChange;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.inventory.TestFiles;
import io.github.cowwoc.cat.common.optimizer.port.WorkspacePort;
import io.github.cowwoc.cat.common.optimizer.store.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.store.CertificationRecordJson;
import io.github.cowwoc.cat.common.optimizer.store.DeliveryJournal;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers a selected candidate to the base worktree. Delivery writes only files inside the project; external files
 * are read-only participants.
 * <p>
 * Delivery runs in four steps:
 * <ol>
 *   <li>It checks that the optimization worktree holds only content that CAT recorded, and materializes the selected
 *   candidate there if it is not the working candidate.</li>
 *   <li>It plans the delivery against a fresh scan of the base worktree and the read-only external participants that
 *   the fork recorded, and stops before writing anything if the plan has conflicts. A certified delivery's plan also
 *   creates or replaces the target's certification record, after the candidate's changes. A certified delivery, and
 *   keeping the incumbent, also write each confirmed prompt test's regression scenario and values file, after the
 *   candidate's changes and before the record.</li>
 *   <li>It writes the delivery journal, then applies each change after checking its target's live state, recording
 *   each applied change in the journal. A restarted delivery resumes from the journal and applies each change at most
 *   once.</li>
 *   <li>It checks that every target holds the candidate's state and reports the Git diff of the base worktree, the
 *   paths that changed outside the candidate since the fork, and whether the result is current or historical.</li>
 * </ol>
 * Delivery never commits, stages or moves a branch in the base worktree; the user reviews the uncommitted changes.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe. One process at a time may deliver a campaign.
 */
public final class Delivery
{
	private static final String TEMPORARY_SUFFIX = ".cat-delivery";
	private final CampaignStore store;
	private final WorkspacePort workspace;
	private final JsonMapper json;
	private final CertificationRecordJson recordCodec;
	private final Consumer<DeliveryChange> afterWrite;

	/**
	 * Creates a delivery executor.
	 *
	 * @param store the store that holds the campaign's delivery journal
	 * @param workspace the optimization worktree
	 * @param json the JSON mapper that writes certification records
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Delivery(CampaignStore store, WorkspacePort workspace, JsonMapper json)
	{
		this(store, workspace, json, _ ->
		{
		});
	}

	/**
	 * Creates a delivery executor that reports each written change.
	 *
	 * @param store the store that holds the campaign's delivery journal
	 * @param workspace the optimization worktree
	 * @param json the JSON mapper that writes certification records
	 * @param afterWrite receives each change after its target is written and before the journal records it; an
	 *   exception that it throws stops the delivery at that point, as a crash would
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Delivery(CampaignStore store, WorkspacePort workspace, JsonMapper json,
		Consumer<DeliveryChange> afterWrite)
	{
		requireThat(store, "store").isNotNull();
		requireThat(workspace, "workspace").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(afterWrite, "afterWrite").isNotNull();
		this.store = store;
		this.workspace = workspace;
		this.json = json;
		this.recordCodec = new CertificationRecordJson(json);
		this.afterWrite = afterWrite;
	}

	/**
	 * Delivers a candidate, or resumes its interrupted delivery. When the user kept the incumbent, delivers its prompt
	 * tests alone.
	 *
	 * @param campaignId the campaign's identity
	 * @param request the user's delivery request, which names the selected candidate and the label it carries, or empty
	 *   if the user kept the incumbent
	 * @param selected the selected candidate's overlay, or the incumbent's if the user kept it; its fork's root is the
	 *   base worktree
	 * @param working the working candidate's overlay, which the optimization worktree holds unless the selected
	 *   candidate was already materialized there
	 * @param promptSources the participating prompt sources: paths relative to the base worktree, or absolute paths of
	 *   read-only external participants; a change outside the candidate at or below one of them makes the result
	 *   historical
	 * @param record the certification record to write to the base worktree, present exactly when the request is
	 *   labelled {@code certified}
	 * @param tests the confirmed prompt tests to write, empty unless the request is labelled {@code certified} or the
	 *   user kept the incumbent
	 * @return the result; a {@link Delivered} result carries no label when the user kept the incumbent
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the request's label never delivers a candidate, if {@code selected} changes
	 *   an external file, if the request names a different candidate than {@code selected}, if {@code selected} and
	 *   {@code working} have different forks, if {@code record} is absent from a certified request or present in
	 *   another, if {@code tests} accompany a request that is not labelled {@code certified}, if a kept incumbent
	 *   changes a file, or if a prompt test cannot be written as a regression scenario
	 * @throws IllegalStateException if the campaign already journals the delivery of another candidate
	 * @throws IOException if a file cannot be read or written, or if Git fails
	 */
	public DeliveryResult deliver(String campaignId, Optional<DeliveryRequest> request, Overlay selected,
		Overlay working, Set<Path> promptSources, Optional<CertificationRecord> record, List<DeliveredTest> tests)
		throws IOException
	{
		requireThat(campaignId, "campaignId").isNotNull();
		requireThat(request, "request").isNotNull();
		requireThat(selected, "selected").isNotNull();
		requireThat(working, "working").isNotNull();
		requireThat(promptSources, "promptSources").isNotNull();
		requireThat(record, "record").isNotNull();
		requireThat(tests, "tests").isNotNull();
		Outcome label = null;
		if (request.isPresent())
		{
			label = request.get().label();
			requireDeliverable(label);
			requireThat(request.get().candidate().snapshot(), "request.candidate().snapshot()").
				isEqualTo(selected.identity(), "selected.identity()");
			if (label != Outcome.CERTIFIED && !tests.isEmpty())
			{
				throw new IllegalArgumentException("A delivery labelled \"" + label.label() + "\" may not write " +
					"prompt tests.");
			}
		}
		else if (!selected.entries().isEmpty())
		{
			throw new IllegalArgumentException("The kept incumbent changes " + selected.entries().keySet() + ", but " +
				"keeping the incumbent delivers its prompt tests alone.");
		}
		requireRecord(label, record);
		requireThat(working.fork(), "working.fork()").isEqualTo(selected.fork(), "selected.fork()");
		DeliveryPlan.requireProjectChanges(selected);

		Optional<DeliveryJournal> recorded = store.loadJournal(campaignId);
		if (recorded.isPresent() && !recorded.get().candidate().equals(selected.identity()))
		{
			throw new IllegalStateException("Campaign " + campaignId + " already journals the delivery of candidate " +
				recorded.get().candidate().hex() + ", not of " + selected.identity().hex() + ".");
		}

		Optional<UnownedChange> unowned = prepareWorktree(selected, working);
		if (unowned.isPresent())
			return unowned.get();

		Path base = selected.fork().root();
		Set<Path> externalPaths = externalPaths(selected);
		DeliveryJournal journal;
		if (recorded.isPresent())
			journal = recorded.get();
		else
		{
			Map<Path, FileContent> additionalFiles = new HashMap<>();
			if (record.isPresent())
			{
				additionalFiles.put(CertificationRecord.location(record.get().target()),
					new FileContent(recordCodec.write(record.get())));
			}
			DeliveryPlan plan = DeliveryPlan.of(selected, InventoryScanner.scan(base, externalPaths),
				testFiles(selected, tests), additionalFiles);
			if (!plan.conflicts().isEmpty())
				return new Conflicted(plan.conflicts());
			journal = new DeliveryJournal(selected.identity(), plan.changes(), 0);
			store.writeJournal(campaignId, journal);
		}

		for (int i = journal.applied(); i < journal.changes().size(); ++i)
		{
			DeliveryChange change = journal.changes().get(i);
			Path file = base.resolve(change.target());
			Optional<FileEntry> live = liveState(file);
			if (!live.equals(change.postimage().map(FileEntry.class::cast)))
			{
				if (!live.equals(change.preimage().map(FileEntry.class::cast)))
					return new ConcurrentEdit(change, i);
				apply(change, file);
				afterWrite.accept(change);
			}
			journal = journal.withApplied(i + 1);
			store.writeJournal(campaignId, journal);
		}
		return verify(label, selected, journal, externalPaths, promptSources);
	}

	/**
	 * Rejects a certification record that does not match the delivery's label.
	 *
	 * @param label the label of the delivery request, or {@code null} if the user kept the incumbent
	 * @param record the certification record to write
	 * @throws IllegalArgumentException if {@code record} is absent from a certified request or present otherwise
	 */
	private static void requireRecord(Outcome label, Optional<CertificationRecord> record)
	{
		boolean certified = label == Outcome.CERTIFIED;
		if (certified && record.isEmpty())
			throw new IllegalArgumentException("A delivery labelled \"certified\" requires a certification record.");
		if (!certified && record.isPresent())
		{
			String writer;
			if (label == null)
				writer = "Keeping the incumbent";
			else
				writer = "A delivery labelled \"" + label.label() + "\"";
			throw new IllegalArgumentException(writer + " may not write a certification record.");
		}
	}

	/**
	 * Returns the files that write the prompt tests: each test's values file, its regression scenario and its fixture
	 * files under {@value PromptTestFiles#FIXTURES}. A test that delivery creates gets a new scenario titled with its
	 * identifier and its own copy of every fixture. A test that edits a recorded scenario keeps that scenario's title,
	 * design goals and fixture patterns; a recorded scenario that does not follow the scenario format is replaced as a
	 * created one would be. Recorded fixture files that the test no longer names are kept.
	 * <p>
	 * For an edited test, a fixture whose file the recorded patterns select from a shared
	 * {@value PromptTestFiles#FIXTURES} directory with the same bytes stays there and is not written. Any other fixture
	 * is written to the test's own directory, and a pattern is appended so that the copy is selected: {@code fixtures/**}
	 * if no pattern of the own directory selects it, or a pattern that names the copy alone if a later pattern would
	 * still replace it.
	 *
	 * @param selected the delivered overlay, whose recorded files the edited scenarios start from
	 * @param tests the prompt tests
	 * @return the test files
	 * @throws IOException if a recorded file cannot be read
	 */
	private TestFiles testFiles(Overlay selected, List<DeliveredTest> tests) throws IOException
	{
		Map<Path, FileContent> files = new HashMap<>();
		Set<Path> createdDirectories = new LinkedHashSet<>();
		for (DeliveredTest test : tests)
		{
			Path directory = test.location().directory();
			StringWriter values = new StringWriter();
			ValuesFile.write(json, test.testCases(), values);
			files.put(directory.resolve(ValuesFile.NAME), new FileContent(values.toString().getBytes(UTF_8)));

			Path scenarioPath = directory.resolve(PromptTestFiles.SCENARIO);
			Optional<String> recorded = Optional.empty();
			List<String> patterns = List.of();
			if (test.location().created())
				createdDirectories.add(directory);
			else
			{
				recorded = workspace.readFile(selected, scenarioPath).map(file -> new String(file.bytes(), UTF_8));
				if (recorded.isPresent())
					patterns = recordedPatterns(recorded.get());
			}

			List<String> appended = new ArrayList<>();
			Path own = directory.resolve(PromptTestFiles.FIXTURES);
			for (FixtureFile fixture : test.fixtures())
			{
				byte[] content = fixture.content().getBytes(UTF_8);
				if (!patterns.isEmpty())
				{
					List<String> current = new ArrayList<>(patterns);
					current.addAll(appended);
					if (isSharedUnchanged(selected, directory, current, fixture.path(), content))
						continue;
					appended.addAll(selectingPatterns(selected, directory, current, fixture.path()));
				}
				files.put(own.resolve(fixture.path()), new FileContent(content));
			}

			String scenario = test.scenarioFile(recorded, appended);
			files.put(scenarioPath, new FileContent(scenario.getBytes(UTF_8)));
		}
		return new TestFiles(files, createdDirectories);
	}

	/**
	 * Returns the fixture patterns of a recorded scenario.
	 *
	 * @param recorded the recorded {@value PromptTestFiles#SCENARIO}
	 * @return the patterns, in order, or an empty list if the scenario's sections cannot be read, so that delivery
	 *   replaces the file with a new scenario
	 */
	private static List<String> recordedPatterns(String recorded)
	{
		try
		{
			return Scenario.fixturePatterns(recorded);
		}
		catch (ScenarioFormatException _)
		{
			return List.of();
		}
	}

	/**
	 * Indicates whether the file that a prompt test's patterns select for a fixture lies in a shared
	 * {@value PromptTestFiles#FIXTURES} directory and holds the fixture's bytes.
	 *
	 * @param selected  the delivered overlay
	 * @param directory the prompt test's directory, relative to the project
	 * @param patterns  the prompt test's fixture patterns, in order
	 * @param path      the fixture's workspace path
	 * @param content   the fixture's content
	 * @return {@code true} if the last pattern that selects a recorded file at {@code path} names a directory other
	 *   than the test's own and that file holds {@code content}
	 * @throws IOException if a recorded file cannot be read
	 */
	private boolean isSharedUnchanged(Overlay selected, Path directory, List<String> patterns, String path,
		byte[] content) throws IOException
	{
		Path own = directory.resolve(PromptTestFiles.FIXTURES);
		for (String text : patterns.reversed())
		{
			FixturePattern pattern = FixturePattern.parse(text);
			Optional<FileContent> file = selectedFile(selected, directory, pattern, path);
			if (file.isPresent())
				return !pattern.base(directory).equals(own) && Arrays.equals(file.get().bytes(), content);
		}
		return false;
	}

	/**
	 * Returns the recorded file that a pattern selects at a workspace path.
	 *
	 * @param selected  the delivered overlay
	 * @param directory the prompt test's directory, relative to the project
	 * @param pattern   the pattern
	 * @param path      the workspace path
	 * @return the file's content, or an empty value if the pattern does not match {@code path}, its directory lies
	 *   outside the project, or the file is not recorded
	 * @throws IOException if the file cannot be read
	 */
	private Optional<FileContent> selectedFile(Overlay selected, Path directory, FixturePattern pattern, String path)
		throws IOException
	{
		Path base = pattern.base(directory);
		if (base.startsWith("..") || !pattern.matches(path))
			return Optional.empty();
		return workspace.readFile(selected, base.resolve(path));
	}

	/**
	 * Returns the patterns to append so that a prompt test selects its own copy of a fixture.
	 *
	 * @param selected  the delivered overlay
	 * @param directory the prompt test's directory, relative to the project
	 * @param patterns  the prompt test's fixture patterns, in order
	 * @param path      the fixture's workspace path
	 * @return {@value FixturePattern#DEFAULT} if no pattern of the own directory matches {@code path}, a pattern that
	 *   names the copy alone if a later pattern selects a recorded file at {@code path} from another directory, or an
	 *   empty list otherwise
	 * @throws IOException if a recorded file cannot be read
	 */
	private List<String> selectingPatterns(Overlay selected, Path directory, List<String> patterns, String path)
		throws IOException
	{
		Path own = directory.resolve(PromptTestFiles.FIXTURES);
		int lastOwn = -1;
		for (int i = 0; i < patterns.size(); ++i)
		{
			FixturePattern pattern = FixturePattern.parse(patterns.get(i));
			if (pattern.base(directory).equals(own) && pattern.matches(path))
				lastOwn = i;
		}
		if (lastOwn == -1)
			return List.of(FixturePattern.DEFAULT);
		for (int i = lastOwn + 1; i < patterns.size(); ++i)
		{
			FixturePattern pattern = FixturePattern.parse(patterns.get(i));
			if (!pattern.base(directory).equals(own) && selectedFile(selected, directory, pattern, path).isPresent())
				return List.of(FixturePattern.exact(path));
		}
		return List.of();
	}

	/**
	 * Rejects a label that never accompanies a delivered candidate.
	 *
	 * @param label the label of the delivery request
	 * @throws IllegalArgumentException if {@code label} is not {@code certified}, {@code improved, not certified} or
	 *   {@code not certified}
	 */
	private static void requireDeliverable(Outcome label)
	{
		requireThat(label, "label").isNotNull();
		boolean deliverable = switch (label)
		{
			case CERTIFIED, IMPROVED_NOT_CERTIFIED, NOT_CERTIFIED -> true;
			case BELOW_MINIMUM, BELOW_CONTROL, BELOW_INCUMBENT, INCONCLUSIVE, STOPPED_FOR_CONTRACT_REVISION,
				STOPPED_FOR_TEST_REVISION, TESTING_ENVIRONMENT_CHANGED, UNSUPPORTED_INSTRUCTION_SOURCE,
				INELIGIBLE_TRIAL_EFFECT, UNSUPPORTED_OUTPUT, UNSUPPORTED_TEST_INTERACTION,
				INVALID_STATISTICAL_ASSUMPTIONS -> false;
		};
		if (!deliverable)
		{
			throw new IllegalArgumentException("A delivered candidate is labelled \"certified\", \"improved, not " +
				"certified\" or \"not certified\", but the request is labelled \"" + label.label() + "\".");
		}
	}

	/**
	 * Checks that the optimization worktree holds only recorded content, and materializes the selected candidate there
	 * if the worktree holds the working candidate instead.
	 *
	 * @param selected the selected candidate
	 * @param working the working candidate
	 * @return the unrecorded differences between the working candidate and the worktree, or empty if there are none
	 * @throws IOException if the worktree cannot be read or written
	 */
	private Optional<UnownedChange> prepareWorktree(Overlay selected, Overlay working) throws IOException
	{
		Map<Path, FileEntry> worktree = projectEntries(workspace.scanWorktree());
		if (worktree.equals(projectEntries(selected.snapshot())))
			return Optional.empty();
		Map<Path, FileEntry> expected = projectEntries(working.snapshot());
		if (!worktree.equals(expected))
		{
			Path root = selected.fork().root();
			List<PathChange> changes = InventoryDiff.between(new FileInventory(root, expected),
				new FileInventory(root, worktree)).changes();
			return Optional.of(new UnownedChange(changes));
		}
		workspace.materialize(selected);
		return Optional.empty();
	}

	/**
	 * Returns the entries of an inventory that lie inside the project. The optimization worktree holds only these; the
	 * read-only external participants exist once, outside every worktree.
	 *
	 * @param inventory the inventory
	 * @return the entries keyed by a relative path
	 */
	private static Map<Path, FileEntry> projectEntries(FileInventory inventory)
	{
		Map<Path, FileEntry> entries = new HashMap<>();
		for (Entry<Path, FileEntry> entry : inventory.entries().entrySet())
		{
			if (!entry.getKey().isAbsolute())
				entries.put(entry.getKey(), entry.getValue());
		}
		return entries;
	}

	/**
	 * Returns the external paths that a scan of the base must cover: every read-only external participant that the
	 * fork recorded, so that a change made to one since the fork is reported.
	 *
	 * @param selected the selected candidate
	 * @return the absolute paths
	 */
	private static Set<Path> externalPaths(Overlay selected)
	{
		Set<Path> paths = new TreeSet<>();
		for (Path path : selected.fork().entries().keySet())
		{
			if (path.isAbsolute())
				paths.add(path);
		}
		return paths;
	}

	/**
	 * Returns the live state of a target, read without following symbolic links.
	 *
	 * @param file the target
	 * @return the regular file, a {@link SpecialFile} for any other kind of file, or empty if nothing exists there
	 * @throws IOException if the target cannot be read
	 */
	private static Optional<FileEntry> liveState(Path file) throws IOException
	{
		BasicFileAttributes attributes;
		try
		{
			attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		}
		catch (NoSuchFileException _)
		{
			return Optional.empty();
		}
		if (!attributes.isRegularFile())
			return Optional.of(new SpecialFile());
		return Optional.of(new RegularFile(Sha256.of(Files.readAllBytes(file)), PortableFiles.mode(file)));
	}

	/**
	 * Applies one change to a target whose live state matches the change's preimage.
	 *
	 * @param change the change
	 * @param file the target
	 * @throws IOException if the target cannot be written
	 */
	private static void apply(DeliveryChange change, Path file) throws IOException
	{
		switch (change)
		{
			case Create create ->
			{
				Files.createDirectories(file.getParent());
				replace(file, create.content(), create.permissions());
			}
			case Edit edit -> replace(file, edit.content(), edit.permissions());
			case Delete _ ->
			{
				Files.delete(file);
				PortableFiles.forceDirectory(file.getParent());
			}
		}
	}

	/**
	 * Replaces a file atomically: the content is written to a temporary sibling, forced to the storage device, given
	 * its mode, and renamed over the file. A crash leaves either the old or the new file; the next delivery run
	 * replaces a leftover temporary file.
	 *
	 * @param file the file
	 * @param content the file's new bytes
	 * @param permissions the file's new mode
	 * @throws IOException if the file cannot be written
	 */
	private static void replace(Path file, FileContent content, Set<PosixFilePermission> permissions)
		throws IOException
	{
		Path temporary = file.resolveSibling("." + file.getFileName() + TEMPORARY_SUFFIX);
		Files.write(temporary, content.bytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
			StandardOpenOption.WRITE);
		force(temporary);
		PortableFiles.setMode(temporary, permissions);
		PortableFiles.replace(temporary, file);
		PortableFiles.forceDirectory(file.getParent());
	}

	/**
	 * Forces a file to the storage device.
	 *
	 * @param path the file
	 * @throws IOException if it cannot be forced
	 */
	private static void force(Path path) throws IOException
	{
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ))
		{
			channel.force(true);
		}
	}

	/**
	 * Checks that every target holds the candidate's state and reports the delivered result.
	 *
	 * @param label the label that the delivered result carries, or {@code null} if the user kept the incumbent
	 * @param selected the delivered candidate
	 * @param journal the completed journal
	 * @param externalPaths the external paths that a scan of the base covers
	 * @param promptSources the participating prompt sources
	 * @return the delivered result, or {@link VerificationFailed} if a target does not hold the candidate's state
	 * @throws IOException if a file cannot be read or Git fails
	 */
	private static DeliveryResult verify(Outcome label, Overlay selected, DeliveryJournal journal,
		Set<Path> externalPaths, Set<Path> promptSources) throws IOException
	{
		Path base = selected.fork().root();
		List<Path> mismatched = new ArrayList<>();
		for (DeliveryChange change : journal.changes())
		{
			if (!liveState(base.resolve(change.target())).equals(change.postimage().map(FileEntry.class::cast)))
				mismatched.add(change.target());
		}
		if (!mismatched.isEmpty())
			return new VerificationFailed(mismatched);

		FileInventory current = InventoryScanner.scan(base, externalPaths);
		// The journal's targets hold the delivered state, which includes files written beside the candidate
		Set<Path> delivered = new HashSet<>();
		for (DeliveryChange change : journal.changes())
			delivered.add(change.target());
		List<Path> baseOnlyChanges = InventoryDiff.between(selected.snapshot(), current).changes().stream().
			map(PathChange::path).filter(path -> !delivered.contains(path)).sorted().toList();
		List<Path> changedPromptSources = baseOnlyChanges.stream().
			filter(path -> promptSources.stream().anyMatch(path::startsWith)).toList();
		Applicability applicability;
		if (changedPromptSources.isEmpty())
			applicability = new Applicability(Applicability.Status.CURRENT, false, List.of());
		else
			applicability = new Applicability(Applicability.Status.HISTORICAL, true, List.of());
		return new Delivered(label, applicability, changedPromptSources, baseOnlyChanges, journal.changes(),
			diff(base, journal.changes()));
	}

	/**
	 * Returns the Git diff of the delivered project paths against the base worktree's {@code HEAD}, including created
	 * files. A temporary index inside the Git directory marks the created files as intended additions, so the user's
	 * index stays untouched.
	 *
	 * @param base the base worktree
	 * @param changes the delivered changes
	 * @return the diff, or an empty string if no delivered path differs from {@code HEAD}
	 * @throws IOException if Git fails
	 */
	private static String diff(Path base, List<DeliveryChange> changes) throws IOException
	{
		List<String> targets = new ArrayList<>();
		List<String> existing = new ArrayList<>();
		for (DeliveryChange change : changes)
		{
			targets.add(PortablePaths.text(change.target()));
			if (change.postimage().isPresent())
				existing.add(PortablePaths.text(change.target()));
		}
		if (targets.isEmpty())
			return "";
		Git git = new Git(base);
		Path gitDirectory = Path.of(git.run("rev-parse", "--absolute-git-dir").strip());
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path index = cleanup.add(Files.createTempDirectory(gitDirectory, "cat-delivery-")).resolve("index");
			git.lines(index, "read-tree", "HEAD");
			if (!existing.isEmpty())
				git.lines(index, arguments(List.of("add", "--intent-to-add", "--force", "--"), existing));
			List<String> lines = git.lines(index, arguments(List.of("diff", "--no-color", "--no-ext-diff", "HEAD",
				"--"), targets));
			if (lines.isEmpty())
				return "";
			return String.join("\n", lines) + "\n";
		}
	}

	/**
	 * Concatenates a Git command and its paths.
	 *
	 * @param command the subcommand and options
	 * @param paths the paths
	 * @return the arguments
	 */
	private static String[] arguments(List<String> command, List<String> paths)
	{
		List<String> arguments = new ArrayList<>(command);
		arguments.addAll(paths);
		return arguments.toArray(String[]::new);
	}
}
