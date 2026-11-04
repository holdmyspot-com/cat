package com.holdmyspot.cat.common.updater.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.updater.activation.ActivationStep;
import com.holdmyspot.cat.common.updater.activation.AddDirectory;
import com.holdmyspot.cat.common.updater.activation.PluginActivation;
import com.holdmyspot.cat.common.updater.activation.ReplaceDirectory;
import com.holdmyspot.cat.common.updater.activation.ReplaceOwnedFiles;
import com.holdmyspot.cat.common.updater.engine.ActivationJournal.Document;
import com.holdmyspot.cat.common.updater.engine.ActivationJournal.Entry;
import com.holdmyspot.cat.common.updater.engine.ActivationJournal.FileChange;
import com.holdmyspot.cat.common.updater.engine.ActivationJournal.Kind;
import com.holdmyspot.cat.common.updater.engine.ActivationJournal.Operation;
import com.holdmyspot.cat.common.updater.engine.ActivationJournal.StepState;
import com.holdmyspot.cat.common.updater.engine.ActivationJournal.Undo;
import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Activates provider-approved paths and restores interrupted changes. The engine invokes mutating methods only while
 * it holds the harness operation lock. Other processes must not replace activation destinations during the
 * operation.
 */
final class ActivationTransaction
{
	/**
	 * Staging directories are outside a harness's scanned directory when its provider requires that placement.
	 */
	private static final String STAGING_PREFIX = ".cat-staging-";
	/**
	 * The retained installation slot for a replaced live directory.
	 */
	private static final String PREVIOUS_PREFIX = ".cat-previous-";
	/**
	 * An earlier rollback slot is preserved until the new activation has a durable successful record.
	 */
	private static final String OLDER_PREFIX = ".cat-older-";
	/**
	 * New live content is moved here before undo restores the previous installation.
	 */
	private static final String DISCARD_PREFIX = ".cat-discard-";
	/**
	 * The migration marker's before-image inside one transaction's private backup root.
	 */
	private static final String MIGRATION_BACKUP = "migration";
	/**
	 * A tree and ownership manifest share the extractor's maximum of 100,000 entries.
	 */
	private static final int MAXIMUM_FILES = 100_000;
	private final UpdaterState state;
	private final PluginActivation provider;
	private final Path home;
	/**
	 * The current journal, or null before an operation reads or prepares one.
	 */
	private Document current;
	/**
	 * A cleanup failure after committing, or null when cleanup completed.
	 */
	private String warning;

	/**
	 * Creates a transaction bound to the policy's harness home and provider.
	 *
	 * @param state    the protected updater state
	 * @param provider the provider that authorizes activation destinations
	 * @param home     the absolute harness home from policy
	 */
	ActivationTransaction(UpdaterState state, PluginActivation provider, Path home)
	{
		requireThat(state, "state").isNotNull();
		requireThat(provider, "provider").isNotNull();
		requireThat(home, "home").isAbsolute().isEqualTo(home.normalize());
		this.state = state;
		this.provider = provider;
		this.home = home;
	}

	/**
	 * Activates a validated tree and retains one rollback. Every replacement is copied and every before-image is saved
	 * before any live installation changes. A failed live change is reversed before the failure is returned.
	 *
	 * @param tree            the validated release tree
	 * @param plan            provider-approved changes in execution order
	 * @param migrationFile   the provider's migration marker
	 * @param previousVersion the active version before activation
	 * @param newVersion      the authenticated release version
	 * @param completion      the work that must succeed for the activation to be kept, such as registering the
	 *                        plugin with the harness; its failure restores the previous installation
	 * @throws IOException    if staging, activation or {@code completion} fails; ActivationFailure reports the
	 *                        recovery outcome
	 * @throws TrustException if either version is malformed
	 */
	void activate(Path tree, List<ActivationStep> plan, Path migrationFile, String previousVersion,
		String newVersion, Completion completion) throws IOException, TrustException
	{
		requireThat(completion, "completion").isNotNull();
		ReleaseVersion.parse(previousVersion);
		if (!provider.plan(home, newVersion).equals(plan))
			throw new Refused("The activation destinations differ from the provider's plan");
		run(tree, plan, migrationFile, Operation.UPDATE, previousVersion, newVersion, completion);
	}

	/**
	 * Adds a validated tree where the harness has no plugin, then runs {@code completion} before the activation is
	 * committed. When a step or {@code completion} fails, every added location is deleted again, so the harness
	 * returns to having no plugin. An interrupted install is undone the same way by the next {@link #recover()}.
	 *
	 * @param tree          the validated release tree
	 * @param plan          the provider's install-from-absence plan, in execution order
	 * @param migrationFile the provider's migration marker
	 * @param newVersion    the authenticated release version
	 * @param completion    the work that must succeed for the install to be kept, such as registering the plugin
	 *                      with the harness
	 * @throws IOException    if staging, activation or {@code completion} fails; ActivationFailure reports the
	 *                        recovery outcome
	 * @throws TrustException if the version is malformed
	 */
	void install(Path tree, List<ActivationStep> plan, Path migrationFile, String newVersion, Completion completion)
		throws IOException, TrustException
	{
		requireThat(completion, "completion").isNotNull();
		if (!provider.installPlan(home, newVersion).equals(plan))
			throw new Refused("The activation destinations differ from the provider's install plan");
		run(tree, plan, migrationFile, Operation.INSTALL, "", newVersion, completion);
	}

	/**
	 * Replaces the installed release with a validated tree of the same release, then runs {@code completion} before
	 * the activation is committed. The rollback that the last activation retained stays as it was, so that rollback
	 * still restores the installation that preceded the last update. When a step or {@code completion} fails, the
	 * replaced files are restored. An interrupted repair is undone the same way by the next {@link #recover()}.
	 *
	 * @param tree          the validated release tree
	 * @param plan          the provider's plan for the installed version, in execution order
	 * @param migrationFile the provider's migration marker
	 * @param version       the installed and authenticated release version
	 * @param completion    the work that must succeed for the repair to be kept, such as registering the plugin with
	 *                      the harness
	 * @throws IOException    if staging, activation or {@code completion} fails; ActivationFailure reports the
	 *                        recovery outcome
	 * @throws TrustException if the version is malformed
	 */
	void repair(Path tree, List<ActivationStep> plan, Path migrationFile, String version, Completion completion)
		throws IOException, TrustException
	{
		requireThat(completion, "completion").isNotNull();
		if (!provider.plan(home, version).equals(plan))
			throw new Refused("The activation destinations differ from the provider's plan");
		run(tree, plan, migrationFile, Operation.REPAIR, "", version, completion);
	}

	/**
	 * Runs one journaled activation.
	 *
	 * @param tree            the validated release tree
	 * @param plan            provider-approved changes in execution order
	 * @param migrationFile   the provider's migration marker
	 * @param operation       whether the activation replaces an installation, adds one or repairs one
	 * @param previousVersion the active version before activation, or empty for an install or a repair
	 * @param newVersion      the authenticated release version
	 * @param completion      the work that must succeed before the activation is committed
	 * @throws IOException    if staging, activation or {@code completion} fails; ActivationFailure reports the
	 *                        recovery outcome
	 * @throws TrustException if the new version is malformed
	 */
	private void run(Path tree, List<ActivationStep> plan, Path migrationFile, Operation operation,
		String previousVersion, String newVersion, Completion completion) throws IOException, TrustException
	{
		requireThat(tree, "tree").isDirectory();
		ReleaseVersion.parse(newVersion);
		if (!provider.migrationVersionFile(home).equals(migrationFile))
			throw new Refused("The activation destinations differ from the provider's plan");
		if (Files.exists(state.activation(), NOFOLLOW_LINKS))
			throw new Refused("An interrupted activation must be recovered before activating another release");
		preflight(tree, plan, migrationFile);
		Path backupRoot = state.previousFiles().resolve(UUID.randomUUID().toString());
		List<Entry> entries = prepareEntries(tree, plan, backupRoot, operation == Operation.REPAIR);
		current = new Document(operation, previousVersion, newVersion, migrationFile, backupRoot,
			Files.exists(migrationFile, NOFOLLOW_LINKS), false, false, entries);
		validate(current);
		save();
		try
		{
			stage(tree, plan);
			current = new Document(operation, previousVersion, newVersion, migrationFile, backupRoot,
				current.migrationExisted(), true, false, current.entries());
			save();
			for (int index = 0; index < entries.size(); ++index)
				execute(index);
			StateFiles.write(migrationFile, (StagedRelease.migrationVersion(newVersion) + "\n").getBytes(UTF_8));
			completion.run();
			// A repair keeps the last activation's record, so that rollback still restores what it replaced
			if (operation == Operation.REPAIR)
				Files.delete(state.activation());
			else
				Files.move(state.activation(), state.lastActivation(), ATOMIC_MOVE, REPLACE_EXISTING);
			PortableFiles.forceDirectory(state.directory());
		}
		catch (IOException failure)
		{
			try
			{
				if (!Files.exists(state.activation(), NOFOLLOW_LINKS))
				{
					// A failure after committing must leave a recoverable reversal before it changes live paths again.
					// Reversing a committed update also consumes its record; a repair never replaced the record.
					current = new Document(current.operation(), current.previousVersion(), current.newVersion(),
						current.migrationFile(), current.backupRoot(), current.migrationExisted(), current.prepared(),
						current.operation() != Operation.REPAIR, current.entries());
					save();
				}
				undo();
			}
			catch (IOException recovery)
			{
				failure.addSuppressed(recovery);
				throw new ActivationFailure(failure, false);
			}
			throw new ActivationFailure(failure, true);
		}
		try
		{
			if (operation == Operation.REPAIR)
				cleanupReplaced();
			else
				cleanupOlder();
		}
		catch (IOException failure)
		{
			warning = "The release was activated, but older rollback files could not be removed: " + failure.getMessage();
		}
	}

	/**
	 * Returns a cleanup warning from a completed activation.
	 *
	 * @return the warning, or an empty optional when cleanup completed
	 */
	Optional<String> warning()
	{
		return Optional.ofNullable(warning);
	}

	/**
	 * Refuses collisions and links before creating staging or changing an installation.
	 *
	 * @param tree          the validated release tree
	 * @param plan          provider-approved steps
	 * @param migrationFile the migration marker
	 * @throws IOException if a destination is invalid or cannot be inspected
	 */
	private void preflight(Path tree, List<ActivationStep> plan, Path migrationFile) throws IOException
	{
		StateFiles.requireNoLinks(migrationFile);
		Set<Path> destinations = new HashSet<>();
		for (ActivationStep step : plan)
		{
			Path destination = destination(step);
			StateFiles.requireNoLinks(destination);
			if (!destinations.add(destination))
				throw new Refused("The activation plan repeats a destination: " + destination);
			switch (step)
			{
				case ReplaceDirectory replace ->
				{
					StateFiles.requireNoLinks(replace.slotParent());
					StateFiles.requireNoLinks(previousPath(replace));
					if (!Files.isDirectory(replace.live(), NOFOLLOW_LINKS))
						throw new Refused("The directory to replace is absent or is not a directory: " + replace.live());
				}
				case AddDirectory add ->
				{
					StateFiles.requireNoLinks(add.stagingParent());
					if (Files.exists(add.target(), NOFOLLOW_LINKS))
						throw new Refused("The activation target already exists: " + add.target());
				}
				case ReplaceOwnedFiles owned -> ownedFiles(tree, owned, state.previousFiles());
			}
		}
	}

	/**
	 * Computes journal paths without creating them.
	 *
	 * @param tree          the validated release tree
	 * @param plan          provider-approved steps
	 * @param backupRoot    the private backup root
	 * @param keepsRollback true if the activation keeps the last activation's rollback slots, so that each replaced
	 *                      directory moves to a scratch slot instead of the rollback slot
	 * @return every step's concrete paths
	 * @throws IOException if owned-file inputs are invalid
	 */
	private List<Entry> prepareEntries(Path tree, List<ActivationStep> plan, Path backupRoot, boolean keepsRollback)
		throws IOException
	{
		List<Entry> entries = new ArrayList<>();
		for (int index = 0; index < plan.size(); ++index)
		{
			ActivationStep step = plan.get(index);
			Path live = destination(step);
			Path parent = switch (step)
			{
				case ReplaceDirectory replace -> replace.slotParent();
				case AddDirectory add -> add.stagingParent();
				case ReplaceOwnedFiles _ -> backupRoot;
			};
			String suffix = UUID.randomUUID().toString();
			Path staging = parent.resolve(STAGING_PREFIX + suffix);
			Path older = parent.resolve(OLDER_PREFIX + suffix);
			Path previous = parent.resolve(PREVIOUS_PREFIX + live.getFileName());
			if (keepsRollback)
				previous = older;
			Kind kind;
			List<FileChange> files = List.of();
			boolean hadPrevious = false;
			switch (step)
			{
				case ReplaceDirectory _ ->
				{
					kind = Kind.REPLACE_DIRECTORY;
					hadPrevious = !keepsRollback && Files.exists(previous, NOFOLLOW_LINKS);
				}
				case AddDirectory _ -> kind = Kind.ADD_DIRECTORY;
				case ReplaceOwnedFiles owned ->
				{
					kind = Kind.REPLACE_OWNED_FILES;
					files = ownedFiles(tree, owned, backupRoot.resolve(Integer.toString(index)));
				}
			}
			entries.add(new Entry(kind, live, staging, previous, older, parent.resolve(DISCARD_PREFIX + suffix),
				StepState.PENDING, Undo.LIVE, hadPrevious, files));
		}
		return entries;
	}

	/**
	 * Finds the live destination of a provider step.
	 *
	 * @param step the provider step
	 * @return its destination
	 */
	private static Path destination(ActivationStep step)
	{
		return switch (step)
		{
			case ReplaceDirectory replace -> replace.live();
			case AddDirectory add -> add.target();
			case ReplaceOwnedFiles owned -> owned.directory();
		};
	}

	/**
	 * Locates a directory's retained previous installation.
	 *
	 * @param replace the provider step
	 * @return its previous slot
	 */
	private static Path previousPath(ReplaceDirectory replace)
	{
		return replace.slotParent().resolve(PREVIOUS_PREFIX + replace.live().getFileName());
	}

	/**
	 * Plans owned-file changes and refuses hostile names or unowned collisions.
	 *
	 * @param tree       the validated release
	 * @param owned      the provider's ownership contract
	 * @param backupRoot the private backup root for this step
	 * @return the old and new file union followed by the ownership manifest
	 * @throws IOException if a name or destination is unsafe
	 */
	private static List<FileChange> ownedFiles(Path tree, ReplaceOwnedFiles owned, Path backupRoot)
		throws IOException
	{
		StateFiles.requireNoLinks(owned.directory());
		StateFiles.requireNoLinks(owned.ownershipManifest());
		Set<String> oldNames = readNames(owned);
		Set<String> newNames = new LinkedHashSet<>();
		Path source = sourceDirectory(tree, owned);
		try (DirectoryStream<Path> sources = Files.newDirectoryStream(source, owned.sourceGlob()))
		{
			for (Path file : sources)
			{
				if (!Files.isRegularFile(file, NOFOLLOW_LINKS))
					throw new Refused("The owned-file source is not a regular file: " + file);
				String name = owned.prefix() + file.getFileName();
				requireOwnedName(name, owned.prefix());
				Path target = owned.directory().resolve(name);
				if (Files.exists(target, NOFOLLOW_LINKS) && !oldNames.contains(name))
					throw new Refused("The updater refuses to overwrite an unowned file: " + target);
				newNames.add(name);
				if (newNames.size() > MAXIMUM_FILES)
					throw new Refused("The release contains too many owned files: " + source);
			}
		}
		Set<String> allNames = new LinkedHashSet<>(oldNames);
		allNames.addAll(newNames);
		List<FileChange> files = new ArrayList<>();
		for (String name : allNames)
		{
			Path live = owned.directory().resolve(name);
			StateFiles.requireNoLinks(live);
			requireRegularOrAbsent(live);
			requireVacantTemporary(live);
			files.add(new FileChange(live, backupRoot.resolve("old").resolve(name),
				backupRoot.resolve("new").resolve(name), Files.exists(live, NOFOLLOW_LINKS)));
		}
		requireRegularOrAbsent(owned.ownershipManifest());
		requireVacantTemporary(owned.ownershipManifest());
		files.add(new FileChange(owned.ownershipManifest(), backupRoot.resolve("manifest.old"),
			backupRoot.resolve("manifest.new"), Files.exists(owned.ownershipManifest(), NOFOLLOW_LINKS)));
		return files;
	}

	/**
	 * Resolves source names using the archive's forward-slash convention.
	 *
	 * @param tree  the validated tree
	 * @param owned the source selection
	 * @return its source directory
	 * @throws IOException if the source resolves outside the validated tree
	 */
	private static Path sourceDirectory(Path tree, ReplaceOwnedFiles owned) throws IOException
	{
		Path source = tree;
		for (String name : owned.sourceSubdirectory().split("/"))
			source = source.resolve(name);
		if (!source.toRealPath().startsWith(tree.toRealPath()))
			throw new Refused("The owned-file source resolves outside the validated release: " + source);
		return source;
	}

	/**
	 * Reads a bounded ownership manifest without retaining unrelated content.
	 *
	 * @param owned the ownership contract
	 * @return unique owned names
	 * @throws IOException if the manifest is hostile or oversized
	 */
	private static Set<String> readNames(ReplaceOwnedFiles owned) throws IOException
	{
		Set<String> names = new LinkedHashSet<>();
		Path manifest = owned.ownershipManifest();
		if (!Files.exists(manifest, NOFOLLOW_LINKS))
			return names;
		requireRegularOrAbsent(manifest);
		if (Files.size(manifest) > ActivationJournal.MAXIMUM_BYTES)
			throw new Refused("The ownership manifest exceeds the activation record limit: " + manifest);
		try (BufferedReader reader = Files.newBufferedReader(manifest, UTF_8))
		{
			String name = reader.readLine();
			while (name != null)
			{
				requireOwnedName(name, owned.prefix());
				boolean added = names.add(name);
				if (!added || names.size() > MAXIMUM_FILES)
					throw new Refused("The ownership manifest repeats a name or contains too many names: " + manifest);
				name = reader.readLine();
			}
		}
		return names;
	}

	/**
	 * Refuses a name that can address anything beyond one prefixed owned file.
	 *
	 * @param name   the manifest name
	 * @param prefix the provider's ownership prefix
	 * @throws Refused if the name is hostile
	 */
	private static void requireOwnedName(String name, String prefix) throws Refused
	{
		if (!name.startsWith(prefix) || name.contains("/") || name.contains("\\") || name.contains("..") ||
			name.indexOf('\u0000') >= 0 || !Path.of(name).getFileName().toString().equals(name))
		{
			throw new Refused("The ownership manifest contains an invalid owned filename: " + name);
		}
	}

	/**
	 * Rejects a non-file destination before backup or overwrite.
	 *
	 * @param file the destination
	 * @throws Refused if an existing destination is not regular
	 */
	private static void requireRegularOrAbsent(Path file) throws Refused
	{
		if (Files.exists(file, NOFOLLOW_LINKS) && !Files.isRegularFile(file, NOFOLLOW_LINKS))
			throw new Refused("The activation file is not a regular file: " + file);
	}

	/**
	 * Prevents overwriting an unrelated temporary file beside an owned destination.
	 *
	 * @param file the destination
	 * @throws Refused if its temporary replacement exists
	 */
	private static void requireVacantTemporary(Path file) throws Refused
	{
		Path temporary = temporaryFile(file);
		if (Files.exists(temporary, NOFOLLOW_LINKS))
			throw new Refused("The activation temporary file already exists: " + temporary);
	}

	/**
	 * Locates one owned file's atomic replacement.
	 *
	 * @param file the destination
	 * @return its temporary sibling
	 */
	private static Path temporaryFile(Path file)
	{
		return file.resolveSibling(file.getFileName() + ".new");
	}

	/**
	 * Saves every before-image and prepares every replacement before changing live paths.
	 *
	 * @param tree the validated release
	 * @param plan the provider's ordered steps
	 * @throws IOException if backup or staging fails
	 */
	private void stage(Path tree, List<ActivationStep> plan) throws IOException
	{
		StateFiles.createDirectories(current.backupRoot());
		if (current.migrationExisted())
			copyFile(current.migrationFile(), current.backupRoot().resolve(MIGRATION_BACKUP));
		for (int index = 0; index < current.entries().size(); ++index)
		{
			Entry entry = current.entries().get(index);
			switch (plan.get(index))
			{
				case ReplaceDirectory _, AddDirectory _ -> copyTree(tree, entry.staging());
				case ReplaceOwnedFiles owned -> stageOwned(tree, owned, entry);
			}
		}
	}

	/**
	 * Saves owned files and prepares the next ownership manifest.
	 *
	 * @param tree  the validated release
	 * @param owned the source and ownership contract
	 * @param entry the concrete file changes
	 * @throws IOException if preparing a file fails
	 */
	private static void stageOwned(Path tree, ReplaceOwnedFiles owned, Entry entry) throws IOException
	{
		Path source = sourceDirectory(tree, owned);
		List<String> names = new ArrayList<>();
		for (FileChange file : entry.files())
		{
			if (file.existed())
				copyFile(file.live(), file.backup());
			if (file.live().equals(owned.ownershipManifest()))
				continue;
			String name = file.live().getFileName().toString();
			Path input = source.resolve(name.substring(owned.prefix().length()));
			if (Files.isRegularFile(input, NOFOLLOW_LINKS) && input.getFileSystem().
				getPathMatcher("glob:" + owned.sourceGlob()).matches(input.getFileName()))
			{
				copyFile(input, file.staging());
				names.add(name);
			}
		}
		String content = String.join("\n", names);
		if (!names.isEmpty())
			content += "\n";
		StateFiles.write(entry.files().getLast().staging(), content.getBytes(UTF_8));
	}

	/**
	 * Copies a validated tree without following its links and checks its complete entry count.
	 *
	 * @param source the validated source tree
	 * @param target the fresh staging directory
	 * @throws IOException if copying fails or a link escapes the source tree
	 */
	static void copyTree(Path source, Path target) throws IOException
	{
		StateFiles.createDirectories(target);
		long[] counts = new long[2];
		Path canonical = source.toRealPath();
		Files.walkFileTree(source, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException
			{
				counts[0] += 1;
				if (counts[0] > MAXIMUM_FILES)
					throw new IOException("The staged tree contains too many entries: " + source);
				StateFiles.createDirectories(target.resolve(source.relativize(directory)));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				counts[0] += 1;
				if (counts[0] > MAXIMUM_FILES)
					throw new IOException("The staged tree contains too many entries: " + source);
				Path output = target.resolve(source.relativize(file));
				if (attributes.isSymbolicLink())
				{
					Path resolved = file.toRealPath();
					if (!resolved.startsWith(canonical))
						throw new IOException("The staged link resolves outside the validated tree: " + file);
					Path corresponding = target.resolve(canonical.relativize(resolved));
					Files.createSymbolicLink(output, output.getParent().relativize(corresponding));
				}
				else if (attributes.isRegularFile())
					copyFile(file, output);
				else
					throw new IOException("The staged entry is neither a regular file nor an internal link: " + file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException
			{
				if (failure != null)
					throw failure;
				PortableFiles.forceDirectory(target.resolve(source.relativize(directory)));
				return FileVisitResult.CONTINUE;
			}
		});
		Files.walkFileTree(target, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
			{
				counts[1] += 1;
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
			{
				counts[1] += 1;
				return FileVisitResult.CONTINUE;
			}
		});
		if (counts[0] != counts[1])
			throw new IOException("The staged copy's entry count differs from the validated tree: " + target);
		PortableFiles.forceDirectory(target);
	}

	/**
	 * Copies and forces a regular file, preserving executable bits.
	 *
	 * @param source the source file
	 * @param target the new file
	 * @throws IOException if copying or forcing fails
	 */
	private static void copyFile(Path source, Path target) throws IOException
	{
		StateFiles.createDirectories(target.getParent());
		StateFiles.requireNoLinks(target);
		try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
		{
			Files.copy(source, Channels.newOutputStream(channel));
			PortableFiles.setMode(target, PortableFiles.mode(source));
			channel.force(true);
		}
		PortableFiles.forceDirectory(target.getParent());
	}

	/**
	 * Executes one prepared step with durable intent before each rename.
	 *
	 * @param index the step's position in execution order
	 * @throws IOException if activation fails
	 */
	private void execute(int index) throws IOException
	{
		Entry entry = current.entries().get(index);
		setProgress(index, StepState.STAGED, Undo.LIVE);
		switch (entry.kind())
		{
			case REPLACE_DIRECTORY ->
			{
				if (entry.hadPrevious())
					move(entry.previous(), entry.older());
				move(entry.live(), entry.previous());
				setProgress(index, StepState.SWAPPED, Undo.LIVE);
				move(entry.staging(), entry.live());
			}
			case ADD_DIRECTORY -> move(entry.staging(), entry.live());
			case REPLACE_OWNED_FILES ->
			{
				for (FileChange file : entry.files())
				{
					if (Files.exists(file.staging(), NOFOLLOW_LINKS))
						replaceFile(file.staging(), file.live());
					else
						Files.deleteIfExists(file.live());
				}
			}
		}
		setProgress(index, StepState.DONE, Undo.LIVE);
	}

	/**
	 * Renames within a filesystem and forces both affected parent directories.
	 *
	 * @param source the existing path
	 * @param target the absent destination
	 * @throws IOException if an atomic rename or force fails
	 */
	private static void move(Path source, Path target) throws IOException
	{
		StateFiles.requireNoLinks(source);
		StateFiles.requireNoLinks(target);
		StateFiles.createDirectories(target.getParent());
		if (Files.exists(target, NOFOLLOW_LINKS))
			throw new IOException("The atomic activation destination already exists: " + target);
		Files.move(source, target, ATOMIC_MOVE);
		PortableFiles.forceDirectory(source.getParent());
		PortableFiles.forceDirectory(target.getParent());
	}

	/**
	 * Atomically replaces one owned file through its journaled temporary sibling.
	 *
	 * @param source the prepared content or saved before-image
	 * @param target the owned destination
	 * @throws IOException if replacing the file fails
	 */
	private static void replaceFile(Path source, Path target) throws IOException
	{
		Path temporary = temporaryFile(target);
		StateFiles.requireNoLinks(target);
		StateFiles.requireNoLinks(temporary);
		Files.deleteIfExists(temporary);
		copyFile(source, temporary);
		Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);
		PortableFiles.forceDirectory(target.getParent());
	}

	/**
	 * Persists a step's intent before the corresponding filesystem action.
	 *
	 * @param index the step's execution position
	 * @param state its forward progress
	 * @param undo  its reverse progress
	 * @throws IOException if recording the transition fails
	 */
	private void setProgress(int index, StepState state, Undo undo) throws IOException
	{
		List<Entry> entries = new ArrayList<>(current.entries());
		Entry entry = entries.get(index);
		entries.set(index, new Entry(entry.kind(), entry.live(), entry.staging(), entry.previous(), entry.older(),
			entry.discard(), state, undo, entry.hadPrevious(), entry.files()));
		current = new Document(current.operation(), current.previousVersion(), current.newVersion(),
			current.migrationFile(),
			current.backupRoot(), current.migrationExisted(), current.prepared(), current.rollback(), entries);
		save();
	}

	/**
	 * Durably records the entire current transaction.
	 *
	 * @throws IOException if recording fails
	 */
	private void save() throws IOException
	{
		StateFiles.write(state.activation(), ActivationJournal.encode(current));
	}

	/**
	 * Restores an interrupted activation before the caller starts another operation.
	 *
	 * @return true if a pending journal was recovered, false when no journal exists
	 * @throws IOException if recovery fails; the journal remains and ActivationFailure reports false
	 */
	boolean recover() throws IOException
	{
		if (!Files.exists(state.activation(), NOFOLLOW_LINKS))
			return false;
		try
		{
			StateFiles.requireNoLinks(state.activation());
			current = ActivationJournal.read(state.activation());
			validate(current);
			undo();
			return true;
		}
		catch (IOException failure)
		{
			throw new ActivationFailure(failure, false);
		}
	}

	/**
	 * Restores the installation preceding the last successful activation and consumes that rollback record.
	 *
	 * @return the restored version
	 * @throws IOException if rollback fails, or Refused when no activation is recorded
	 * @throws TrustException if a recorded version is malformed
	 */
	String rollback() throws IOException, TrustException
	{
		recover();
		if (!Files.exists(state.lastActivation(), NOFOLLOW_LINKS))
			throw new Refused("No previous installation is recorded for rollback");
		StateFiles.requireNoLinks(state.lastActivation());
		Document last = ActivationJournal.read(state.lastActivation());
		validate(last);
		if (last.operation() == Operation.INSTALL)
			throw new Refused("The last activation installed CAT where no plugin existed; there is no previous " +
				"installation to restore");
		current = new Document(last.operation(), last.previousVersion(), last.newVersion(), last.migrationFile(),
			last.backupRoot(), last.migrationExisted(), true, true, last.entries());
		save();
		try
		{
			undo();
			return last.previousVersion();
		}
		catch (IOException failure)
		{
			throw new ActivationFailure(failure, false);
		}
	}

	/**
	 * Undoes all changed steps in reverse order, preserving durable progress for an interrupted undo.
	 *
	 * @throws IOException if any restoration fails
	 */
	private void undo() throws IOException
	{
		if (current.prepared())
		{
			for (int index = current.entries().size() - 1; index >= 0; --index)
				undoEntry(index);
			if (current.migrationExisted())
				replaceFile(current.backupRoot().resolve(MIGRATION_BACKUP), current.migrationFile());
			else
				Files.deleteIfExists(current.migrationFile());
		}
		for (Entry entry : current.entries())
		{
			StateFiles.delete(entry.staging());
			StateFiles.delete(entry.discard());
		}
		if (current.rollback())
			Files.deleteIfExists(state.lastActivation());
		Files.deleteIfExists(state.activation());
		PortableFiles.forceDirectory(state.directory());
		StateFiles.delete(current.backupRoot());
	}

	/**
	 * Restores one changed step without mistaking a restored older rollback slot for the old live installation.
	 *
	 * @param index the step's position
	 * @throws IOException if restoring its paths fails
	 */
	private void undoEntry(int index) throws IOException
	{
		Entry entry = current.entries().get(index);
		if (entry.undo() == Undo.DONE || entry.state() == StepState.PENDING)
			return;
		if (entry.undo() == Undo.LIVE)
		{
			switch (entry.kind())
			{
				case REPLACE_DIRECTORY -> restoreDirectory(entry);
				case ADD_DIRECTORY ->
				{
					if (!Files.exists(entry.staging(), NOFOLLOW_LINKS))
						StateFiles.delete(entry.live());
				}
				case REPLACE_OWNED_FILES -> restoreFiles(entry);
			}
			setProgress(index, entry.state(), Undo.PREVIOUS);
		}
		if (entry.hadPrevious() && Files.exists(entry.older(), NOFOLLOW_LINKS))
			move(entry.older(), entry.previous());
		setProgress(index, entry.state(), Undo.DONE);
	}

	/**
	 * Restores the old live directory across either forward rename and either reverse rename.
	 *
	 * @param entry the directory step
	 * @throws IOException if restoring the previous directory fails
	 */
	private static void restoreDirectory(Entry entry) throws IOException
	{
		if (Files.exists(entry.previous(), NOFOLLOW_LINKS))
		{
			// An older slot still at its original name means the forward live rename has not happened yet.
			if (entry.hadPrevious() && !Files.exists(entry.older(), NOFOLLOW_LINKS) &&
				Files.exists(entry.staging(), NOFOLLOW_LINKS) && Files.exists(entry.live(), NOFOLLOW_LINKS))
			{
				return;
			}
			if (Files.exists(entry.live(), NOFOLLOW_LINKS))
			{
				if (Files.exists(entry.discard(), NOFOLLOW_LINKS))
					throw new IOException("Both live and discarded activation trees exist: " + entry.live());
				move(entry.live(), entry.discard());
			}
			move(entry.previous(), entry.live());
		}
		else if (!Files.isDirectory(entry.live(), NOFOLLOW_LINKS))
			throw new IOException("Both the live and previous installation are absent: " + entry.live());
	}

	/**
	 * Restores the before-image of each owned file and removes new files and interrupted temporary replacements.
	 *
	 * @param entry the owned-file step
	 * @throws IOException if a before-image cannot be restored
	 */
	private static void restoreFiles(Entry entry) throws IOException
	{
		for (FileChange file : entry.files())
		{
			if (file.existed())
				replaceFile(file.backup(), file.live());
			else
				Files.deleteIfExists(file.live());
			Files.deleteIfExists(temporaryFile(file.live()));
		}
	}

	/**
	 * Validates every durable destination against the policy's provider before recovery makes any change.
	 *
	 * @param document the journal to authorize
	 * @throws IOException if any path, step, or version differs from the provider's authority
	 */
	private void validate(Document document) throws IOException
	{
		try
		{
			if (document.operation() == Operation.UPDATE)
				ReleaseVersion.parse(document.previousVersion());
			ReleaseVersion.parse(document.newVersion());
		}
		catch (TrustException failure)
		{
			throw new IOException("The activation journal has a malformed version", failure);
		}
		if (!document.migrationFile().equals(provider.migrationVersionFile(home)))
			throw new IOException("The journal's migration marker differs from the provider's marker");
		Path backupRoot = document.backupRoot();
		if (!backupRoot.getParent().equals(state.previousFiles()))
			throw new IOException("The journal's backup root is outside updater state: " + backupRoot);
		StateFiles.requireNoLinks(backupRoot);
		StateFiles.requireNoLinks(document.migrationFile());
		List<ActivationStep> plan = switch (document.operation())
		{
			case INSTALL -> provider.installPlan(home, document.newVersion());
			case REPAIR -> provider.recoveryPlan(home, document.newVersion(), document.newVersion());
			case UPDATE -> provider.recoveryPlan(home, document.previousVersion(), document.newVersion());
		};
		if (plan.size() != document.entries().size())
			throw new IOException("The journal's step count differs from the provider's plan");
		for (int index = 0; index < plan.size(); ++index)
			validateEntry(document, plan.get(index), index);
	}

	/**
	 * Authorizes one recorded step and all its scratch and backup paths.
	 *
	 * @param document the transaction record
	 * @param step     the authoritative provider step
	 * @param index    the step's position
	 * @throws IOException if the journal redirects any destination
	 */
	private static void validateEntry(Document document, ActivationStep step, int index) throws IOException
	{
		Entry entry = document.entries().get(index);
		if (!destination(step).equals(entry.live()))
			throw new IOException("The journal's destination differs from the provider's plan: " + entry.live());
		StateFiles.requireNoLinks(entry.live());
		Path parent;
		Kind kind;
		switch (step)
		{
			case ReplaceDirectory replace ->
			{
				parent = replace.slotParent();
				kind = Kind.REPLACE_DIRECTORY;
			}
			case AddDirectory add ->
			{
				parent = add.stagingParent();
				kind = Kind.ADD_DIRECTORY;
			}
			case ReplaceOwnedFiles owned ->
			{
				parent = document.backupRoot();
				kind = Kind.REPLACE_OWNED_FILES;
				validateOwned(owned, entry, document.backupRoot().resolve(Integer.toString(index)));
			}
		}
		// A repair moves each replaced directory to its scratch slot and leaves the rollback slot alone
		Path previous = parent.resolve(PREVIOUS_PREFIX + entry.live().getFileName());
		if (document.operation() == Operation.REPAIR)
		{
			previous = entry.older();
			if (entry.hadPrevious())
				throw new IOException("A repair activation journal moves a rollback slot");
		}
		if (entry.kind() != kind || !entry.previous().equals(previous))
			throw new IOException("The journal's step kind or previous slot differs from its provider");
		requireScratch(entry.staging(), parent, STAGING_PREFIX);
		requireScratch(entry.older(), parent, OLDER_PREFIX);
		requireScratch(entry.discard(), parent, DISCARD_PREFIX);
		StateFiles.requireNoLinks(entry.previous());
		if (kind != Kind.REPLACE_OWNED_FILES && !entry.files().isEmpty())
			throw new IOException("A directory activation journal contains owned-file changes");
	}

	/**
	 * Checks that a scratch slot is a direct child of its provider-approved parent.
	 *
	 * @param path   the journaled scratch path
	 * @param parent the approved parent
	 * @param prefix the operation-owned filename prefix
	 * @throws IOException if the path redirects a scratch operation
	 */
	private static void requireScratch(Path path, Path parent, String prefix) throws IOException
	{
		if (!path.getParent().equals(parent) || !path.getFileName().toString().startsWith(prefix) ||
			path.getFileName().toString().length() == prefix.length())
		{
			throw new IOException("The journal's scratch path differs from its approved parent: " + path);
		}
		StateFiles.requireNoLinks(path);
	}

	/**
	 * Authorizes owned destinations and exact before-image locations without trusting the mutable live manifest.
	 *
	 * @param owned      the provider's ownership contract
	 * @param entry      the journal's owned-file step
	 * @param backupRoot the exact backup location for the step
	 * @throws IOException if a filename or backup redirects recovery
	 */
	private static void validateOwned(ReplaceOwnedFiles owned, Entry entry, Path backupRoot) throws IOException
	{
		Set<Path> destinations = new HashSet<>();
		boolean manifest = false;
		for (FileChange file : entry.files())
		{
			boolean isManifest = file.live().equals(owned.ownershipManifest());
			Path backup;
			Path staging;
			if (isManifest)
			{
				manifest = true;
				backup = backupRoot.resolve("manifest.old");
				staging = backupRoot.resolve("manifest.new");
			}
			else
			{
				String name = file.live().getFileName().toString();
				requireOwnedName(name, owned.prefix());
				if (!file.live().getParent().equals(owned.directory()))
					throw new IOException("The journal's owned file is outside its approved directory: " + file.live());
				backup = backupRoot.resolve("old").resolve(name);
				staging = backupRoot.resolve("new").resolve(name);
			}
			if (!file.backup().equals(backup) || !file.staging().equals(staging) || !destinations.add(file.live()))
				throw new IOException("The journal repeats or redirects an owned-file change: " + file.live());
			StateFiles.requireNoLinks(file.live());
			StateFiles.requireNoLinks(file.backup());
			StateFiles.requireNoLinks(file.staging());
			StateFiles.requireNoLinks(temporaryFile(file.live()));
		}
		if (!manifest)
			throw new IOException("The journal does not include the provider's ownership manifest");
	}

	/**
	 * Reads the recorded previous version without creating or modifying state.
	 *
	 * @return the recorded version, or empty when no successful activation is recorded
	 * @throws IOException if the record cannot be read or is outside the provider's authority
	 */
	Optional<String> previousVersion() throws IOException
	{
		if (!Files.exists(state.lastActivation(), NOFOLLOW_LINKS))
			return Optional.empty();
		StateFiles.requireNoLinks(state.lastActivation());
		Document last = ActivationJournal.read(state.lastActivation());
		validate(last);
		if (last.operation() == Operation.INSTALL)
			return Optional.empty();
		return Optional.of(last.previousVersion());
	}

	/**
	 * Returns the rollback and scratch locations that the last successful activation left beside the harness's
	 * plugin locations, which removing CAT from the harness must also delete. Locations inside the updater's state are
	 * excluded because they are deleted with it.
	 *
	 * @return the existing locations, after the last activation's journal is validated against the provider
	 * @throws IOException if the journal cannot be read or does not match the provider's plan
	 */
	List<Path> retainedPaths() throws IOException
	{
		if (!Files.exists(state.lastActivation(), NOFOLLOW_LINKS))
			return List.of();
		StateFiles.requireNoLinks(state.lastActivation());
		Document last = ActivationJournal.read(state.lastActivation());
		validate(last);
		List<Path> paths = new ArrayList<>();
		for (Entry entry : last.entries())
		{
			if (entry.kind() == Kind.REPLACE_OWNED_FILES)
				continue;
			for (Path path : List.of(entry.previous(), entry.older(), entry.staging(), entry.discard()))
			{
				if (Files.exists(path, NOFOLLOW_LINKS))
					paths.add(path);
			}
		}
		return paths;
	}

	/**
	 * Returns the live, staged, previous and saved-file paths for a failure report.
	 *
	 * @return the current transaction's concrete paths, or empty before a journal is read or prepared
	 */
	List<Path> paths()
	{
		if (current == null)
			return List.of();
		Set<Path> paths = new LinkedHashSet<>();
		paths.add(current.migrationFile());
		paths.add(current.backupRoot());
		for (Entry entry : current.entries())
		{
			paths.addAll(List.of(entry.live(), entry.staging(), entry.previous(), entry.older(), entry.discard()));
			for (FileChange file : entry.files())
				paths.addAll(List.of(file.live(), file.backup(), file.staging()));
		}
		return List.copyOf(paths);
	}

	/**
	 * Removes older rollback slots after a durable successful activation.
	 *
	 * @throws IOException if cleanup fails; the successful activation remains recorded
	 */
	private void cleanupOlder() throws IOException
	{
		for (Entry entry : current.entries())
			StateFiles.delete(entry.older());
		try (DirectoryStream<Path> backups = Files.newDirectoryStream(state.previousFiles()))
		{
			for (Path backup : backups)
			{
				if (!backup.equals(current.backupRoot()))
					StateFiles.delete(backup);
			}
		}
	}

	/**
	 * Deletes what a committed repair replaced: each replaced directory and the before-images of this activation. The
	 * last activation's rollback files stay.
	 *
	 * @throws IOException if a deletion fails
	 */
	private void cleanupReplaced() throws IOException
	{
		for (Entry entry : current.entries())
			StateFiles.delete(entry.previous());
		StateFiles.delete(current.backupRoot());
	}

	/**
	 * Work that an install runs after its files are in place and before it is committed.
	 */
	@FunctionalInterface
	interface Completion
	{
		/**
		 * Runs the work.
		 *
		 * @throws IOException if the work fails, which undoes the install
		 */
		void run() throws IOException;
	}

	/**
	 * Refuses an activation before changing an installation.
	 */
	static final class Refused extends IOException
	{
		private static final long serialVersionUID = 1L;

		/**
		 * Creates a refusal.
		 *
		 * @param message the rejected activation condition
		 */
		Refused(String message)
		{
			super(message);
		}
	}

	/**
	 * Reports whether a failed activation restored the preceding installation.
	 */
	static final class ActivationFailure extends IOException
	{
		private static final long serialVersionUID = 1L;
		private final boolean restored;

		/**
		 * Creates a failure with its recovery outcome.
		 *
		 * @param cause    the activation or recovery failure
		 * @param restored indicates whether the preceding installation was fully restored
		 */
		ActivationFailure(IOException cause, boolean restored)
		{
			super(cause.getMessage(), cause);
			this.restored = restored;
		}

		/**
		 * Indicates whether the preceding installation was fully restored.
		 *
		 * @return true when recovery completed
		 */
		boolean restored()
		{
			return restored;
		}
	}
}
