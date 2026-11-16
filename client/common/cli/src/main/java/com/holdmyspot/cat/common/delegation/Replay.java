package com.holdmyspot.cat.common.delegation;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies candidate source changes with retained preimages and conditional rollback.
 */
public final class Replay
{
	private static final LinkOption NO_FOLLOW = LinkOption.NOFOLLOW_LINKS;
	private static final int BUFFER_SIZE = 8192;
	/**
	 * Prevents construction of the transaction service.
	 */
	private Replay()
	{
	}

	/**
	 * Replays exact candidate paths after checking their snapshot preimages.
	 *
	 * @param project current parent project
	 * @param candidate audited candidate project
	 * @param snapshot immutable source preimages
	 * @param changedPaths exact candidate paths
	 * @param transaction existing transaction-owned storage directory on the project's file store
	 * @param excludedRoots reviewed project-relative regions excluded from source replay
	 * @param outputRoots project-relative roots that verification may write
	 * @param gate combined verification callback
	 * @return integration outcome
	 * @throws IOException if a path is stale, transaction storage is unsafe, or required metadata cannot be retained
	 * @throws IllegalArgumentException if a changed, excluded, or output path is not canonical and project-relative
	 * @throws NullPointerException if a required argument is null
	 */
	public static Outcome integrate(Path project, Path candidate, SourceSnapshot snapshot, List<String> changedPaths,
		Path transaction, List<String> excludedRoots, List<String> outputRoots, Gate gate) throws IOException
	{
		Objects.requireNonNull(project, "project may not be null");
		Objects.requireNonNull(candidate, "candidate may not be null");
		Objects.requireNonNull(snapshot, "snapshot may not be null");
		Objects.requireNonNull(transaction, "transaction may not be null");
		List<String> replayPaths = List.copyOf(Objects.requireNonNull(changedPaths, "changedPaths may not be null"));
		List<String> sourceExclusions = List.copyOf(Objects.requireNonNull(excludedRoots,
			"excludedRoots may not be null"));
		List<String> verificationOutputs = List.copyOf(Objects.requireNonNull(outputRoots,
			"outputRoots may not be null"));
		Objects.requireNonNull(gate, "gate may not be null");
		Path projectRoot = project.toRealPath();
		Path candidateRoot = candidate.toRealPath();
		Path transactionRoot = validateTransactionStorage(projectRoot, candidateRoot, snapshot, replayPaths,
			transaction, sourceExclusions, verificationOutputs);
		if (!Files.getFileStore(projectRoot).equals(Files.getFileStore(transactionRoot)))
			throw new IOException("Transaction storage is on a different file store from the project");
		if (Files.exists(transactionRoot.resolve("journal.jsonl")))
		{
			Outcome reconciled = reconcile(JsonMapper.builder().build(), projectRoot, candidateRoot, snapshot,
				replayPaths, transactionRoot, sourceExclusions, verificationOutputs);
			throw new IOException("Existing replay journal was reconciled; a fresh audited transaction is required: " +
				transactionRoot + " (accepted=" + reconciled.accepted() + ")");
		}
		metadataRoundTrip(transactionRoot);
		snapshot.verifyDirectories(projectRoot);
		Set<String> uniquePaths = new HashSet<>(replayPaths);
		if (uniquePaths.size() != replayPaths.size())
			throw new IllegalArgumentException("Changed paths must be unique");
		List<TransactionEntry> entries = new ArrayList<>();
		for (String relative : replayPaths.stream().sorted().toList())
		{
			Path parentPath = safePath(projectRoot, relative);
			Path candidatePath = safePath(candidateRoot, relative);
			SourceSnapshot.Entry expected = snapshot.entry(relative);
			SourceSnapshot.Entry actual = observe(projectRoot, relative);
			if (!sameImage(expected, actual))
				throw new IOException("Stale parent input: " + relative);
			if (actual.present() && !actual.kind().equals("symlink"))
				metadata(parentPath);
			SourceSnapshot.Entry postimage = observe(candidateRoot, relative);
			if (postimage.present() && !postimage.kind().equals("symlink"))
				metadata(candidatePath);
			validateLink(projectRoot, relative, postimage);
			entries.add(new TransactionEntry(relative, parentPath, candidatePath, expected, postimage));
		}
		Path preimages = transactionRoot.resolve("preimages");
		Files.createDirectories(preimages);
		Map<Path, FileTime> directoryTimes = directoryTimes(projectRoot, entries);
		Set<Path> absentDirectories = absentDirectories(projectRoot, entries);
		Set<Path> createdDirectories = new HashSet<>();
		Path journal = transactionRoot.resolve("journal.jsonl");
		Files.writeString(journal, journalHeader(projectRoot, directoryTimes, absentDirectories));
		forceFile(journal);
		List<TransactionEntry> installed = new ArrayList<>();
		try
		{
			for (TransactionEntry entry : entries)
			{
				Path original = preimages.resolve(entry.path()).normalize();
				Files.createDirectories(original.getParent());
				TransactionEntry retained = entry.withRetainedOriginal(original);
				appendJournal(journal, retained, "retain_intent");
				if (entry.preimage().present())
					Files.move(entry.parentPath(), original, StandardCopyOption.ATOMIC_MOVE);
				installed.add(retained);
				appendJournal(journal, retained, "preimage_retained");
				ensureParentDirectories(entry.parentPath().getParent(), projectRoot, createdDirectories);
				if (entry.postimage().present())
					copyPostimage(entry.candidatePath(), entry.parentPath());
				SourceSnapshot.Entry actualPostimage = observe(projectRoot, entry.path());
				if (!sameImage(entry.postimage(), actualPostimage))
					throw new IOException("Installed postimage differs from candidate: " + entry.path());
				TransactionEntry installedEntry = retained.withPostimageInstalled();
				installed.set(installed.size() - 1, installedEntry);
				appendJournal(journal, installedEntry, "installed");
			}
			boolean accepted = gate.verify(projectRoot);
			if (accepted)
			{
				appendTerminal(journal, "integrated");
				return new Outcome(true, List.of(), List.of());
			}
			RollbackResult rollback = rollback(installed, journal, createdDirectories, directoryTimes);
			return new Outcome(false, rollback.restoredPaths(), rollback.conflictPaths());
		}
		catch (IOException | RuntimeException e)
		{
			try
			{
				rollback(installed, journal, createdDirectories, directoryTimes);
			}
			catch (IOException rollbackFailure)
			{
				e.addSuppressed(rollbackFailure);
			}
			throw e;
		}
	}

	/**
	 * Reconciles an interrupted transaction using the retained snapshot and audited candidate images.
	 *
	 * @param mapper configured JSON reader
	 * @param project current project root
	 * @param candidate retained audited candidate
	 * @param snapshot immutable source preimages
	 * @param changedPaths exact audited source path set
	 * @param transaction retained transaction root
	 * @param excludedRoots reviewed project-relative regions excluded from source replay
	 * @param outputRoots project-relative roots that verification may write
	 * @return terminal integration or conditional rollback outcome
	 * @throws IOException if journal, snapshot, or preimage evidence is corrupt or incomplete, or storage is unsafe
	 * @throws IllegalArgumentException if a changed, excluded, or output path is not canonical and project-relative
	 * @throws NullPointerException if a required argument is null
	 */
	public static Outcome reconcile(JsonMapper mapper, Path project, Path candidate, SourceSnapshot snapshot,
		List<String> changedPaths, Path transaction, List<String> excludedRoots, List<String> outputRoots)
		throws IOException
	{
		Objects.requireNonNull(mapper, "mapper may not be null");
		Objects.requireNonNull(project, "project may not be null");
		Objects.requireNonNull(candidate, "candidate may not be null");
		Objects.requireNonNull(snapshot, "snapshot may not be null");
		Objects.requireNonNull(transaction, "transaction may not be null");
		List<String> replayPaths = List.copyOf(Objects.requireNonNull(changedPaths, "changedPaths may not be null"));
		List<String> sourceExclusions = List.copyOf(Objects.requireNonNull(excludedRoots,
			"excludedRoots may not be null"));
		List<String> verificationOutputs = List.copyOf(Objects.requireNonNull(outputRoots,
			"outputRoots may not be null"));
		Path projectRoot = project.toRealPath();
		Path candidateRoot = candidate.toRealPath();
		Path transactionRoot = validateTransactionStorage(projectRoot, candidateRoot, snapshot, replayPaths,
			transaction, sourceExclusions, verificationOutputs);
		if (!Files.getFileStore(projectRoot).equals(Files.getFileStore(transactionRoot)))
			throw new IOException("Transaction storage is on a different file store from the project");
		Path journal = transactionRoot.resolve("journal.jsonl");
		if (!Files.isRegularFile(journal))
		{
			for (String path : replayPaths)
				if (!sameImage(snapshot.entry(path), observe(projectRoot, path)))
					throw new IOException("Missing replay recovery journal for changed parent path: " + path);
			return new Outcome(false, List.of(), List.of());
		}
		String terminal = "";
		Map<Path, FileTime> directoryPreimages = new HashMap<>();
		Set<Path> absentDirectoryPreimages = new HashSet<>();
		try
		{
			try (BufferedReader journalReader = Files.newBufferedReader(journal))
			{
				String headerLine = journalReader.readLine();
				if (headerLine == null)
					throw new IOException("Empty replay recovery journal: " + journal);
				JsonNode header = mapper.readTree(headerLine);
				if (header == null || !header.isObject() || !header.has("state") ||
					!header.get("state").stringValue().equals("prepared"))
					throw new IOException("Invalid replay recovery journal header: " + journal);
				for (Map.Entry<String, JsonNode> entry : header.get("directory_times").properties())
				{
					Path directory = projectRoot;
					if (!entry.getKey().equals("."))
						directory = safePath(projectRoot, entry.getKey());
					directoryPreimages.put(directory,
						FileTime.from(java.time.Instant.parse(entry.getValue().stringValue())));
				}
				for (JsonNode directory : header.get("absent_directories"))
					absentDirectoryPreimages.add(safePath(projectRoot, directory.stringValue()));
				Set<String> allowed = Set.copyOf(replayPaths);
				int lineNumber = 1;
				for (String line = journalReader.readLine(); line != null; line = journalReader.readLine())
				{
					lineNumber += 1;
					JsonNode record = mapper.readTree(line);
					if (record == null || !record.isObject() || !record.has("state"))
						throw new IOException("Invalid replay recovery journal record at " + journal + ":" + lineNumber);
					String state = record.get("state").stringValue();
					if (record.has("path") && !allowed.contains(record.get("path").stringValue()))
						throw new IOException("Unexpected path in replay recovery journal at " + journal + ":" + lineNumber);
					if (state.equals("retain_intent") && (!record.has("preimage") || !record.has("postimage")))
						throw new IOException("Incomplete replay write-ahead record at " + journal + ":" + lineNumber);
					if (state.equals("integrated") || state.equals("rolled_back") || state.equals("recovery_required"))
						terminal = state;
				}
			}
		}
		catch (JacksonException e)
		{
			throw new IOException("Malformed replay recovery journal: " + journal, e);
		}
		if (terminal.equals("integrated"))
			return new Outcome(true, List.of(), List.of());
		if (terminal.equals("rolled_back"))
			return new Outcome(false, List.of(), List.of());
		List<String> restored = new ArrayList<>();
		List<String> conflicts = new ArrayList<>();
		Path preimages = transactionRoot.resolve("preimages");
		for (String relative : replayPaths.stream().sorted(Collections.reverseOrder()).toList())
		{
			Path parentPath = safePath(projectRoot, relative);
			Path candidatePath = safePath(candidateRoot, relative);
			Path retained = safePath(preimages, relative);
			SourceSnapshot.Entry preimage = snapshot.entry(relative);
			SourceSnapshot.Entry postimage = observe(candidateRoot, relative);
			SourceSnapshot.Entry current = observe(projectRoot, relative);
			if (preimage.present() && Files.exists(retained, NO_FOLLOW))
			{
				SourceSnapshot.Entry retainedImage = observe(preimages, relative);
				if (!sameImage(preimage, retainedImage))
					throw new IOException("Retained replay preimage is corrupt: " + retained);
				if (!sameImage(postimage, current) && current.present())
				{
					conflicts.add(relative);
					continue;
				}
				Files.deleteIfExists(parentPath);
				Files.createDirectories(parentPath.getParent());
				Files.move(retained, parentPath, StandardCopyOption.ATOMIC_MOVE);
				restored.add(relative);
			}
			else if (preimage.present())
			{
				if (!sameImage(preimage, current))
					throw new IOException("Missing replay preimage evidence for changed parent path: " + relative);
			}
			else if (sameImage(postimage, current))
			{
				Files.deleteIfExists(parentPath);
				restored.add(relative);
			}
			else if (current.present())
				conflicts.add(relative);
			String pathState = "restored";
			if (conflicts.contains(relative))
				pathState = "conflict";
			appendJournal(journal, new TransactionEntry(relative, parentPath, candidatePath, preimage, postimage,
				retained, false), pathState);
		}
		if (conflicts.isEmpty())
		{
			for (Path directory : absentDirectoryPreimages.stream().sorted((first, second) ->
				Integer.compare(second.getNameCount(), first.getNameCount())).toList())
				if (Files.isDirectory(directory, NO_FOLLOW) && isEmpty(directory))
					Files.delete(directory);
				else if (Files.exists(directory, NO_FOLLOW))
					conflicts.add(projectRoot.relativize(directory).toString());
		}
		if (conflicts.isEmpty())
		{
			for (Map.Entry<Path, FileTime> time : directoryPreimages.entrySet())
				if (Files.isDirectory(time.getKey(), NO_FOLLOW))
					Files.setLastModifiedTime(time.getKey(), time.getValue());
			appendTerminal(journal, "rolled_back");
		}
		else
			appendTerminal(journal, "recovery_required");
		return new Outcome(false, restored, conflicts);
	}

	/**
	 * Checks that transaction storage is separate from source, replay, Git and verification output paths.
	 *
	 * @param project real parent project root
	 * @param candidate real audited candidate root
	 * @param snapshot immutable source path evidence
	 * @param changedPaths exact candidate paths
	 * @param transaction existing transaction directory
	 * @param excludedRoots reviewed project-relative exclusions
	 * @param outputRoots project-relative verification output roots
	 * @return real transaction directory
	 * @throws IOException if transaction storage overlaps a protected path or cannot be resolved
	 */
	private static Path validateTransactionStorage(Path project, Path candidate, SourceSnapshot snapshot,
		List<String> changedPaths, Path transaction, List<String> excludedRoots, List<String> outputRoots)
		throws IOException
	{
		Objects.requireNonNull(project);
		Objects.requireNonNull(candidate);
		Objects.requireNonNull(snapshot);
		Objects.requireNonNull(changedPaths);
		Objects.requireNonNull(transaction);
		Path suppliedTransaction = transaction.toAbsolutePath().normalize();
		boolean isRealDirectory = Files.exists(suppliedTransaction, NO_FOLLOW) &&
			!Files.isSymbolicLink(suppliedTransaction) && Files.isDirectory(suppliedTransaction, NO_FOLLOW);
		if (!isRealDirectory)
			throw unsafeStorage(transaction, "transaction must be an existing real directory", transaction);
		Path transactionRoot = suppliedTransaction.toRealPath();
		boolean transactionIsInProject = transactionRoot.startsWith(project);
		boolean suppliedPathIsInProject = suppliedTransaction.startsWith(project);
		boolean transactionRouteMayBeInProject = transactionIsInProject || suppliedPathIsInProject;
		if (transactionRouteMayBeInProject)
			validateTransactionAncestors(project, suppliedTransaction, transaction);
		boolean transactionContainsProject = project.startsWith(transactionRoot);
		if (transactionContainsProject)
			throw unsafeStorage(transaction, "transaction contains the project", project);
		if (overlaps(transactionRoot, candidate))
			throw unsafeStorage(transaction, "transaction overlaps the candidate", candidate);
		List<Path> exclusions = parsePolicyRoots(excludedRoots, "excludedRoots");
		List<Path> outputs = parsePolicyRoots(outputRoots, "outputRoots");
		if (!transactionIsInProject)
			return transactionRoot;

		Path transactionRelative = project.relativize(transactionRoot);
		if (containsGitDirectory(transactionRelative))
			throw unsafeStorage(transaction, "transaction is inside Git administration", transactionRelative);
		List<Path> changed = parsePolicyRoots(changedPaths, "changedPaths");
		List<Path> eligibleExclusions = exclusions.stream().
			filter(transactionRelative::startsWith).
			sorted((first, second) -> Integer.compare(second.getNameCount(), first.getNameCount())).toList();
		if (eligibleExclusions.isEmpty())
			throw unsafeStorage(transaction, "transaction is not covered by a reviewed exclusion", transactionRelative);
		Path storageExclusion = eligibleExclusions.getFirst();
		for (Path output : outputs)
			if (overlaps(storageExclusion, output))
				throw unsafeStorage(transaction, "storage exclusion overlaps a verification output root", output);
		for (SourceSnapshot.Entry entry : snapshot.entries())
		{
			if (entry.kind().equals("directory"))
				continue;
			Path entryPath = parsePolicyPath(entry.path(), "snapshot path");
			if (overlaps(transactionRelative, entryPath))
				throw unsafeStorage(transaction, "transaction overlaps a source or replay path", entryPath);
			if (overlaps(storageExclusion, entryPath))
				throw unsafeStorage(transaction, "storage exclusion overlaps a source or replay path", entryPath);
		}
		for (Path changedPath : changed)
		{
			if (overlaps(transactionRelative, changedPath))
				throw unsafeStorage(transaction, "transaction overlaps a changed replay path", changedPath);
			if (overlaps(storageExclusion, changedPath))
				throw unsafeStorage(transaction, "storage exclusion overlaps a changed replay path", changedPath);
		}
		for (Path output : outputs)
			if (overlaps(transactionRelative, output))
				throw unsafeStorage(transaction, "transaction overlaps a verification output root", output);
		return transactionRoot;
	}

	/**
	 * Parses project-relative policy paths and rejects representations with multiple spellings.
	 *
	 * @param values path values from the API
	 * @param name parameter name for invalid-input diagnostics
	 * @return immutable normalized project-relative paths
	 */
	private static List<Path> parsePolicyRoots(List<String> values, String name)
	{
		Objects.requireNonNull(values, name + " may not be null");
		List<Path> result = new ArrayList<>();
		Set<Path> unique = new HashSet<>();
		for (String value : values)
		{
			Path path = parsePolicyPath(value, name);
			if (!unique.add(path))
				throw new IllegalArgumentException(name + " must not contain duplicate paths: " + value);
			result.add(path);
		}
		return List.copyOf(result);
	}

	/**
	 * Parses one canonical project-relative path.
	 *
	 * @param value path value
	 * @param name parameter or evidence name for invalid-input diagnostics
	 * @return normalized relative path
	 */
	private static Path parsePolicyPath(String value, String name)
	{
		Objects.requireNonNull(value, name + " path may not be null");
		boolean hasUnsupportedSpelling = value.isBlank() || value.contains("\\") || value.indexOf('\0') >= 0 ||
			value.matches("^[A-Za-z]:.*");
		if (hasUnsupportedSpelling)
			throw new IllegalArgumentException(name + " must contain canonical relative paths: " + value);
		try
		{
			Path path = Path.of(value);
			String canonical = path.toString().replace(path.getFileSystem().getSeparator(), "/");
			boolean hasCanonicalRelativeForm = !path.isAbsolute() && path.normalize().equals(path) &&
				canonical.equals(value);
			if (!hasCanonicalRelativeForm)
				throw new IllegalArgumentException(name + " must contain canonical relative paths: " + value);
			for (Path segment : path)
				if (segment.toString().isBlank() || segment.toString().equals(".") ||
					segment.toString().equals(".."))
					throw new IllegalArgumentException(name + " contains an ambiguous path segment: " + value);
			return path;
		}
		catch (InvalidPathException e)
		{
			throw new IllegalArgumentException(name + " contains an invalid path: " + value, e);
		}
	}

	/**
	 * Rejects symbolic links in the supplied route to a transaction below the project.
	 *
	 * @param project real project root
	 * @param transaction absolute supplied transaction path
	 * @param displayTransaction caller-supplied transaction for diagnostics
	 * @throws IOException if any route component is a symbolic link
	 */
	private static void validateTransactionAncestors(Path project, Path transaction, Path displayTransaction)
		throws IOException
	{
		Path current = transaction.getRoot();
		for (Path segment : transaction)
		{
			current = current.resolve(segment);
			if (Files.isSymbolicLink(current))
				throw unsafeStorage(displayTransaction, "transaction route contains a symbolic link", current);
		}
		if (!transaction.startsWith(project))
			throw unsafeStorage(displayTransaction, "transaction route does not resolve below the project", transaction);
	}

	/**
	 * Returns whether a path or any ancestor names Git administrative state.
	 *
	 * @param path project-relative path
	 * @return whether a segment is named {@code .git}, ignoring case
	 */
	private static boolean containsGitDirectory(Path path)
	{
		for (Path segment : path)
			if (segment.toString().equalsIgnoreCase(".git"))
				return true;
		return false;
	}

	/**
	 * Returns whether either path contains the other.
	 *
	 * @param first first relative or absolute path
	 * @param second second path in the same root
	 * @return whether the paths overlap by containment
	 */
	private static boolean overlaps(Path first, Path second)
	{
		return first.startsWith(second) || second.startsWith(first);
	}

	/**
	 * Creates the checked refusal for a transaction that overlaps a protected path.
	 *
	 * @param transaction transaction path supplied by the caller
	 * @param reason failed storage condition
	 * @param conflict conflicting path
	 * @return actionable unsafe-storage refusal
	 */
	private static IOException unsafeStorage(Path transaction, String reason, Path conflict)
	{
		return new IOException("Unsafe transaction storage " + transaction + ": " + reason + " at " + conflict);
	}

	/**
	 * Verifies physical retention and restoration preserve every supported metadata view on this file store.
	 *
	 * @param transaction same-file-store transaction root
	 * @throws IOException if any supported metadata cannot be read or round-tripped
	 */
	private static void metadataRoundTrip(Path transaction) throws IOException
	{
		Path probeRoot = transaction.resolve("metadata-preflight");
		Path parent = Files.createDirectories(probeRoot.resolve("parent"));
		Path original = parent.resolve("original");
		Path retained = Files.createDirectories(probeRoot.resolve("retained")).resolve("original");
		Files.writeString(original, "original metadata preflight");
		Map<String, String> before = metadata(original);
		boolean retainedInPlace = false;
		try
		{
			Files.move(original, retained, StandardCopyOption.ATOMIC_MOVE);
			retainedInPlace = true;
			Files.writeString(original, "replacement metadata preflight");
			Files.delete(original);
			Files.move(retained, original, StandardCopyOption.ATOMIC_MOVE);
			retainedInPlace = false;
			Map<String, String> after = metadata(original);
			if (!before.equals(after))
				throw new IOException("unsupported_metadata: original entry metadata did not round-trip at " + original);
		}
		finally
		{
			if (retainedInPlace && Files.exists(retained, NO_FOLLOW) && Files.notExists(original, NO_FOLLOW))
				Files.move(retained, original, StandardCopyOption.ATOMIC_MOVE);
			Files.deleteIfExists(original);
			Files.deleteIfExists(retained);
			Files.deleteIfExists(parent);
			Files.deleteIfExists(probeRoot.resolve("retained"));
			Files.deleteIfExists(probeRoot);
		}
	}

	/**
	 * Reads stable entry metadata from all supported filesystem views.
	 *
	 * @param path file to inspect
	 * @return metadata identity
	 * @throws IOException if a supported attribute view cannot be read
	 */
	private static Map<String, String> metadata(Path path) throws IOException
	{
		Map<String, String> result = new HashMap<>();
		BasicFileAttributes basic = Files.readAttributes(path, BasicFileAttributes.class, NO_FOLLOW);
		result.put("creation", basic.creationTime().toString());
		result.put("modified", basic.lastModifiedTime().toString());
		result.put("key", String.valueOf(basic.fileKey()));
		FileStore store = Files.getFileStore(path);
		if (store.supportsFileAttributeView("owner"))
		{
			FileOwnerAttributeView owner = Files.getFileAttributeView(path, FileOwnerAttributeView.class, NO_FOLLOW);
			result.put("owner", owner.getOwner().getName());
		}
		if (store.supportsFileAttributeView("posix"))
		{
			PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, NO_FOLLOW);
			PosixFileAttributes attributes = posix.readAttributes();
			result.put("posix_owner", attributes.owner().getName());
			result.put("posix_group", attributes.group().getName());
			result.put("posix_permissions", attributes.permissions().toString());
		}
		if (store.supportsFileAttributeView("acl"))
		{
			AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, NO_FOLLOW);
			result.put("acl", acl.getAcl().toString());
		}
		if (store.supportsFileAttributeView("dos"))
		{
			DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class, NO_FOLLOW);
			DosFileAttributes attributes = dos.readAttributes();
			result.put("dos", attributes.isReadOnly() + ":" + attributes.isHidden() + ":" +
				attributes.isArchive() + ":" + attributes.isSystem());
		}
		if (store.supportsFileAttributeView("user"))
		{
			UserDefinedFileAttributeView user = Files.getFileAttributeView(path,
				UserDefinedFileAttributeView.class, NO_FOLLOW);
			for (String name : user.list())
			{
				int size = user.size(name);
				if (size > 1_048_576)
					throw new IOException("unsupported_metadata: user attribute exceeds supported bound at " + path +
						" (" + name + ")");
				ByteBuffer value = ByteBuffer.allocate(size);
				user.read(name, value);
				result.put("user:" + name, HexFormat.of().formatHex(value.array()));
			}
		}
		return Map.copyOf(result);
	}

	/**
	 * Resolves a safe relative path without following symlink ancestors.
	 *
	 * @param root project root
	 * @param relative project-relative path
	 * @return resolved leaf path
	 * @throws IOException if the path is unsafe
	 */
	private static Path safePath(Path root, String relative) throws IOException
	{
		Path path = Path.of(relative);
		if (relative.isBlank() || path.isAbsolute() || relative.contains("\\") || relative.indexOf('\0') >= 0)
			throw new IOException("Unsafe replay path: " + relative);
		for (Path segment : path)
			if (segment.toString().isBlank() || segment.toString().equals(".") ||
				segment.toString().equals("..") || segment.toString().equalsIgnoreCase(".git"))
				throw new IOException("Unsafe replay path: " + relative);
		Path result = root.resolve(path).normalize();
		if (!result.startsWith(root))
			throw new IOException("Replay path escapes project: " + relative);
		Path ancestor = root;
		for (Path segment : root.relativize(result))
		{
			ancestor = ancestor.resolve(segment);
			if (!ancestor.equals(result) && Files.isSymbolicLink(ancestor))
				throw new IOException("Replay path has a symbolic-link ancestor: " + relative);
		}
		return result;
	}

	/**
	 * Captures the current bytes and semantic metadata of a leaf entry.
	 *
	 * @param root project root
	 * @param relative project-relative path
	 * @return observed entry or explicit absence
	 * @throws IOException if the path cannot be observed safely
	 */
	private static SourceSnapshot.Entry observe(Path root, String relative) throws IOException
	{
		Path path = safePath(root, relative);
		if (Files.notExists(path, NO_FOLLOW))
			return new SourceSnapshot.Entry(relative, false, "absent", "", false, Set.of(), 0, "", "");
		BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NO_FOLLOW);
		if (attributes.isSymbolicLink())
		{
			Path target = Files.readSymbolicLink(path);
			return new SourceSnapshot.Entry(relative, true, "symlink", digest(target.toString().getBytes(UTF_8)), false,
				Set.of(), attributes.lastModifiedTime().toMillis(), target.toString(),
				SourceSnapshot.metadataIdentity(path));
		}
		if (!attributes.isRegularFile())
			throw new IOException("Replay path is not a regular file or symlink: " + relative);
		Set<PosixFilePermission> permissions = permissions(path);
		boolean executable = permissions.contains(PosixFilePermission.OWNER_EXECUTE) || Files.isExecutable(path);
		return new SourceSnapshot.Entry(relative, true, "file", digest(path), executable, permissions,
			attributes.lastModifiedTime().toMillis(), "", SourceSnapshot.metadataIdentity(path));
	}

	/**
	 * Rejects a candidate symbolic link whose destination leaves the project.
	 *
	 * @param root project root
	 * @param relative project-relative link path
	 * @param entry candidate entry
	 * @throws IOException if the target is unsafe
	 */
	private static void validateLink(Path root, String relative, SourceSnapshot.Entry entry) throws IOException
	{
		if (!entry.kind().equals("symlink"))
			return;
		Path target = Path.of(entry.linkText());
		Path absoluteTarget = safePath(root, relative).getParent().resolve(target).normalize();
		if (target.isAbsolute() || !absoluteTarget.startsWith(root))
			throw new IOException("Replay symlink target escapes project: " + relative);
	}

	/**
	 * Copies one candidate leaf without following a symbolic link.
	 *
	 * @param source candidate leaf
	 * @param destination parent leaf
	 * @throws IOException if the leaf cannot be installed
	 */
	private static void copyPostimage(Path source, Path destination) throws IOException
	{
		if (Files.isSymbolicLink(source))
			Files.createSymbolicLink(destination, Files.readSymbolicLink(source));
		else
			Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
	}

	/**
	 * Creates missing parent directories below the project root.
	 *
	 * @param parent required parent directory
	 * @param root project root
	 * @param created directories created by this operation
	 * @throws IOException if a required directory cannot be created
	 */
	private static void ensureParentDirectories(Path parent, Path root, Set<Path> created) throws IOException
	{
		List<Path> missing = new ArrayList<>();
		Path current = parent;
		while (!current.equals(root) && !Files.exists(current, NO_FOLLOW))
		{
			missing.add(current);
			current = current.getParent();
		}
		Collections.reverse(missing);
		for (Path directory : missing)
		{
			Files.createDirectory(directory);
			created.add(directory);
		}
	}

	/**
	 * Captures affected ancestor directory modification times for rollback.
	 *
	 * @param root project root
	 * @param entries replay entries
	 * @return ancestor time preimages
	 * @throws IOException if metadata cannot be read
	 */
	private static Map<Path, FileTime> directoryTimes(Path root, List<TransactionEntry> entries) throws IOException
	{
		Map<Path, FileTime> times = new HashMap<>();
		for (TransactionEntry entry : entries)
		{
			Path parent = entry.parentPath().getParent();
			while (parent.startsWith(root))
			{
				if (Files.isDirectory(parent, NO_FOLLOW))
					times.put(parent, Files.getLastModifiedTime(parent, NO_FOLLOW));
				if (parent.equals(root))
					break;
				parent = parent.getParent();
			}
		}
		return Map.copyOf(times);
	}

	/**
	 * Captures missing parent directories that replay may need to create.
	 *
	 * @param root project root
	 * @param entries replay entries
	 * @return absent ancestor paths
	 */
	private static Set<Path> absentDirectories(Path root, List<TransactionEntry> entries)
	{
		Set<Path> absent = new HashSet<>();
		for (TransactionEntry entry : entries)
		{
			Path parent = entry.parentPath().getParent();
			while (parent.startsWith(root))
			{
				if (Files.notExists(parent, NO_FOLLOW))
					absent.add(parent);
				if (parent.equals(root))
					break;
				parent = parent.getParent();
			}
		}
		return Set.copyOf(absent);
	}

	/**
	 * Serializes replay parent-directory preimages into the initial journal record.
	 *
	 * @param root project root
	 * @param times existing directory times
	 * @param absent absent directory paths
	 * @return complete first journal line
	 */
	private static String journalHeader(Path root, Map<Path, FileTime> times, Set<Path> absent)
	{
		StringBuilder result = new StringBuilder(256);
		result.append("{\"format_version\":1,\"state\":\"prepared\",\"directory_times\":{");
		boolean first = true;
		for (Map.Entry<Path, FileTime> entry : times.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList())
		{
			if (!first)
				result.append(',');
			first = false;
			String relative = ".";
			if (!entry.getKey().equals(root))
				relative = root.relativize(entry.getKey()).toString().replace('\\', '/');
			result.append('"').append(escape(relative)).append("\":\"").append(escape(entry.getValue().toString())).
				append('"');
		}
		result.append("},\"absent_directories\":[");
		first = true;
		for (Path directory : absent.stream().sorted().toList())
		{
			if (!first)
				result.append(',');
			first = false;
			String relative = root.relativize(directory).toString().replace('\\', '/');
			result.append('"').append(escape(relative)).append('"');
		}
		return result.append("]}\n").toString();
	}

	/**
	 * Restores only installed postimages that still match this transaction.
	 *
	 * @param installed entries with retained originals
	 * @param journal transaction journal
	 * @param createdDirectories operation-created directories
	 * @param directoryTimes ancestor metadata preimages
	 * @return restored and conflicting paths
	 * @throws IOException if conditional recovery cannot complete
	 */
	private static RollbackResult rollback(List<TransactionEntry> installed, Path journal, Set<Path> createdDirectories,
		Map<Path, FileTime> directoryTimes) throws IOException
	{
		List<String> restored = new ArrayList<>();
		List<String> conflicts = new ArrayList<>();
		for (int index = installed.size() - 1; index >= 0; index -= 1)
		{
			TransactionEntry entry = installed.get(index);
			SourceSnapshot.Entry current = observe(entry.parentPath().getParent(),
				entry.parentPath().getFileName().toString());
			boolean incompleteInstall = !entry.postimageInstalled() && entry.preimage().present() &&
				Files.exists(entry.retainedOriginal(), NO_FOLLOW) &&
				Files.notExists(entry.parentPath(), NO_FOLLOW);
			if (!sameImage(entry.postimage(), current) && !incompleteInstall)
			{
				conflicts.add(entry.path());
				continue;
			}
			Files.deleteIfExists(entry.parentPath());
			if (entry.preimage().present())
				Files.move(entry.retainedOriginal(), entry.parentPath(), StandardCopyOption.ATOMIC_MOVE);
			restored.add(entry.path());
			appendJournal(journal, entry, "restored");
		}
		for (Path directory : createdDirectories.stream().sorted((first, second) ->
			Integer.compare(second.getNameCount(), first.getNameCount())).toList())
			if (Files.isDirectory(directory, NO_FOLLOW) && isEmpty(directory))
				Files.delete(directory);
		if (conflicts.isEmpty())
			for (Map.Entry<Path, FileTime> time : directoryTimes.entrySet())
				Files.setLastModifiedTime(time.getKey(), time.getValue());
		String terminalState;
		if (conflicts.isEmpty())
			terminalState = "rolled_back";
		else
			terminalState = "recovery_required";
		appendTerminal(journal, terminalState);
		return new RollbackResult(List.copyOf(restored), List.copyOf(conflicts));
	}

	/**
	 * Checks whether an operation-created directory can be removed without touching entries.
	 *
	 * @param directory directory to inspect
	 * @return whether no child exists
	 * @throws IOException if the directory cannot be listed
	 */
	private static boolean isEmpty(Path directory) throws IOException
	{
		try (DirectoryStream<Path> children = Files.newDirectoryStream(directory))
		{
			return !children.iterator().hasNext();
		}
	}

	/**
	 * Appends one completed transaction path state.
	 *
	 * @param journal journal file
	 * @param entry replay entry
	 * @param state completed state
	 * @throws IOException if evidence cannot be written
	 */
	private static void appendJournal(Path journal, TransactionEntry entry, String state) throws IOException
	{
		String record = "{\"path\":\"" + escape(entry.path()) + "\",\"retained\":\"" +
			escape(entry.path()) + "\",\"state\":\"" + state +
			"\",\"preimage\":\"" + entry.preimage().sha256() + "\",\"postimage\":\"" +
			entry.postimage().sha256() + "\"}\n";
		Files.writeString(journal, record, java.nio.file.StandardOpenOption.APPEND);
		forceFile(journal);
	}

	/**
	 * Forces a journal update to stable storage before allowing the next replay mutation.
	 *
	 * @param path journal to force
	 * @throws IOException if the journal cannot be forced
	 */
	private static void forceFile(Path path) throws IOException
	{
		try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(path,
			java.nio.file.StandardOpenOption.WRITE))
		{
			channel.force(true);
		}
	}

	/**
	 * Appends a terminal transaction state.
	 *
	 * @param journal journal file
	 * @param state terminal state
	 * @throws IOException if evidence cannot be written
	 */
	private static void appendTerminal(Path journal, String state) throws IOException
	{
		Files.writeString(journal, "{\"state\":\"" + state + "\"}\n",
			java.nio.file.StandardOpenOption.APPEND);
	}

	/**
	 * Escapes a project path for a JSON string value.
	 *
	 * @param value input string
	 * @return escaped string contents
	 */
	private static String escape(String value)
	{
		return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
	}

	/**
	 * Compares presence, bytes, kind, and supported file metadata.
	 *
	 * @param expected expected image
	 * @param actual observed image
	 * @return whether the images match
	 */
	private static boolean sameImage(SourceSnapshot.Entry expected, SourceSnapshot.Entry actual)
	{
		return expected.present() == actual.present() && expected.kind().equals(actual.kind()) &&
			expected.sha256().equals(actual.sha256()) && expected.executable() == actual.executable() &&
			expected.permissions().equals(actual.permissions()) &&
			expected.lastModifiedMillis() == actual.lastModifiedMillis() && expected.linkText().equals(actual.linkText()) &&
			expected.metadataIdentity().equals(actual.metadataIdentity());
	}

	/**
	 * Reads POSIX permissions where available.
	 *
	 * @param path file path
	 * @return immutable permissions or empty when unsupported
	 */
	private static Set<PosixFilePermission> permissions(Path path)
	{
		try
		{
			return Set.copyOf(Files.getPosixFilePermissions(path, NO_FOLLOW));
		}
		catch (IOException | UnsupportedOperationException _)
		{
			return Set.of();
		}
	}

	/**
	 * Computes file SHA-256 with bounded memory.
	 *
	 * @param path file path
	 * @return lowercase hexadecimal digest
	 * @throws IOException if the file cannot be read
	 */
	private static String digest(Path path) throws IOException
	{
		MessageDigest digest = newDigest();
		try (InputStream input = Files.newInputStream(path))
		{
			byte[] buffer = new byte[BUFFER_SIZE];
			while (true)
			{
				int count = input.read(buffer);
				if (count == -1)
					break;
				digest.update(buffer, 0, count);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Computes SHA-256 for one byte array.
	 *
	 * @param bytes byte array
	 * @return lowercase hexadecimal digest
	 */
	private static String digest(byte[] bytes)
	{
		return HexFormat.of().formatHex(newDigest().digest(bytes));
	}

	/**
	 * Creates a platform SHA-256 digest.
	 *
	 * @return new digest instance
	 */
	private static MessageDigest newDigest()
	{
		try
		{
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("SHA-256 is required by the Java platform", e);
		}
	}

	private record TransactionEntry(String path, Path parentPath, Path candidatePath,
		SourceSnapshot.Entry preimage, SourceSnapshot.Entry postimage, Path retainedOriginal,
		boolean postimageInstalled)
	{
		/**
		 * Creates an entry before its original has been moved.
		 *
		 * @param path project-relative path
		 * @param parentPath parent leaf path
		 * @param candidatePath candidate leaf path
		 * @param preimage captured snapshot entry
		 * @param postimage candidate entry
		 */
		private TransactionEntry(String path, Path parentPath, Path candidatePath, SourceSnapshot.Entry preimage,
			SourceSnapshot.Entry postimage)
		{
			this(path, parentPath, candidatePath, preimage, postimage, null, false);
		}

		/**
		 * Returns this entry with its physically retained original path.
		 *
		 * @param original retained transaction entry
		 * @return updated transaction entry
		 */
		private TransactionEntry withRetainedOriginal(Path original)
		{
			return new TransactionEntry(path, parentPath, candidatePath, preimage, postimage, original, false);
		}

		/**
		 * Returns this entry after the candidate postimage was observed successfully.
		 *
		 * @return installed transaction entry
		 */
		private TransactionEntry withPostimageInstalled()
		{
			return new TransactionEntry(path, parentPath, candidatePath, preimage, postimage, retainedOriginal, true);
		}
	}

	private record RollbackResult(List<String> restoredPaths, List<String> conflictPaths)
	{
	}

	/**
	 * Runs the combined verification gate against the changed project.
	 */
	@FunctionalInterface
	public interface Gate
	{
		/**
		 * Verifies the project and may perform writes whose effects are observed by rollback.
		 *
		 * @param project changed project root
		 * @return whether verification passed
		 * @throws IOException if verification cannot complete
		 */
		boolean verify(Path project) throws IOException;
	}

	/**
	 * Result of one accepted or rolled-back transaction.
	 *
	 * @param accepted whether verification accepted the replay
	 * @param restoredPaths paths conditionally restored after a failed gate
	 * @param conflictPaths paths whose current postimage differed and was preserved
	 */
	public record Outcome(boolean accepted, List<String> restoredPaths, List<String> conflictPaths)
	{
		/**
		 * Copies transaction path results.
		 *
		 * @param accepted whether verification accepted the replay
		 * @param restoredPaths paths conditionally restored after a failed gate
		 * @param conflictPaths paths whose current postimage differed and was preserved
		 */
		public Outcome
		{
			restoredPaths = List.copyOf(restoredPaths);
			conflictPaths = List.copyOf(conflictPaths);
		}
	}
}
