package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/** Focused gateway mutation, denial, and replay checks. */
public final class GenerationEditSessionTest
{
	/** A create is receipted, and equal replay never reapplies it. */
	@Test
	public void createsAndReplaysExactEdit() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			try (GenerationEditSession session = fixture.session())
			{
			EvidenceRef call = fixture.call(session);
			Path path = Path.of("prompts/new.md");
			GenerationEditOperation operation = new GenerationEditOperation("edit-1",
				GenerationEditOperation.Kind.CREATE, path, Optional.empty(), GenerationExpectedState.absent(),
				Optional.empty(), Optional.of(Base64.getEncoder().encodeToString(
					"new".getBytes(StandardCharsets.UTF_8))));
			GenerationEditDisposition accepted = session.apply(operation, call);
			requireThat(accepted instanceof GenerationEditDisposition.Accepted, "accepted").isTrue();
			requireThat(Files.readString(fixture.project.resolve(path)), "newBytes").isEqualTo("new");
			requireThat(session.apply(operation, call), "replay").isEqualTo(accepted);
			try (GenerationEditSession reopened = fixture.session())
			{
				requireThat(reopened.apply(operation, call), "restartReplay").isEqualTo(accepted);
			}
			}
		}
	}

	/** A denied out-of-root endpoint remains untouched and retains its raw path. */
	@Test
	public void rejectsOutsideConfirmedRoot() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			try (GenerationEditSession session = fixture.session())
			{
			EvidenceRef call = fixture.call(session);
			Path outside = Path.of("other.md");
			GenerationEditOperation operation = new GenerationEditOperation("edit-2",
				GenerationEditOperation.Kind.CREATE, outside, Optional.empty(), GenerationExpectedState.absent(),
				Optional.empty(), Optional.of(Base64.getEncoder().encodeToString(
					"bad".getBytes(StandardCharsets.UTF_8))));
			GenerationEditDisposition disposition = session.apply(operation, call);
			requireThat(disposition instanceof GenerationEditDisposition.Rejected, "rejected").isTrue();
			requireThat(Files.exists(fixture.project.resolve(outside)), "outsideExists").isFalse();
			}
		}
	}

	/** A crash immediately after predecessor capture resumes without replacing a visible target. */
	@Test
	public void resumesAfterCapturedPredecessor() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path path = Path.of("prompts/rule.md");
			Files.writeString(fixture.project.resolve(path), "before");
			GenerationEditOperation operation = new GenerationEditOperation("replace-1",
				GenerationEditOperation.Kind.REPLACE, path, Optional.empty(),
				GenerationExpectedState.regular(PromptSnapshot.sha256("before".getBytes(StandardCharsets.UTF_8))),
				Optional.empty(), Optional.of(Base64.getEncoder().encodeToString(
					"after".getBytes(StandardCharsets.UTF_8))));
			EvidenceRef call;
			try (GenerationEditSession interrupted = fixture.session(new FailAfterCapture()))
			{
				call = fixture.call(interrupted);
				try
				{
					interrupted.apply(operation, call);
					throw new AssertionError("Expected injected capture fault");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "fault").isEqualTo("injected after capture");
				}
			}
			requireThat(Files.exists(fixture.project.resolve(path)), "capturedTargetAbsent").isFalse();
			try (GenerationEditSession resumed = fixture.session())
			{
				GenerationEditDisposition result = resumed.apply(operation, call);
				requireThat(result instanceof GenerationEditDisposition.Accepted, "recovered").isTrue();
			}
			requireThat(Files.readString(fixture.project.resolve(path)), "recoveredBytes").isEqualTo("after");
		}
	}

	/** Recovery preserves a newer visible target that won the absent-target publication race. */
	@Test
	public void preservesConcurrentTargetDuringRecovery() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path path = Path.of("prompts/rule.md");
			Files.writeString(fixture.project.resolve(path), "before");
			GenerationEditOperation operation = new GenerationEditOperation("replace-race",
				GenerationEditOperation.Kind.REPLACE, path, Optional.empty(),
				GenerationExpectedState.regular(PromptSnapshot.sha256("before".getBytes(StandardCharsets.UTF_8))),
				Optional.empty(), Optional.of(Base64.getEncoder().encodeToString(
					"after".getBytes(StandardCharsets.UTF_8))));
			EvidenceRef call;
			try (GenerationEditSession interrupted = fixture.session(new FailAfterCapture()))
			{
				call = fixture.call(interrupted);
				try
				{
					interrupted.apply(operation, call);
					throw new AssertionError("Expected injected capture fault");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "fault").isEqualTo("injected after capture");
				}
			}
			Files.writeString(fixture.project.resolve(path), "external");
			try (GenerationEditSession resumed = fixture.session())
			{
				try
				{
					resumed.apply(operation, call);
					throw new AssertionError("Expected concurrent target rejection");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage().contains("preserving live bytes"), "preserved").isTrue();
				}
			}
			requireThat(Files.readString(fixture.project.resolve(path)), "externalBytes").isEqualTo("external");
		}
	}

	/** A rename captures its source and publishes only to the absent authorized destination. */
	@Test
	public void renamesWithinConfirmedRoot() throws IOException
	{
		try (Fixture fixture = new Fixture(); GenerationEditSession session = fixture.session())
		{
			Path source = Path.of("prompts/old.md");
			Path destination = Path.of("prompts/new.md");
			Files.writeString(fixture.project.resolve(source), "content");
			GenerationEditOperation operation = new GenerationEditOperation("rename-1",
				GenerationEditOperation.Kind.RENAME, source, Optional.of(destination),
				GenerationExpectedState.regular(PromptSnapshot.sha256("content".getBytes(StandardCharsets.UTF_8))),
				Optional.of(GenerationExpectedState.absent()), Optional.empty());
			GenerationEditDisposition result = session.apply(operation, fixture.call(session));
			requireThat(result instanceof GenerationEditDisposition.Accepted, "renamed").isTrue();
			requireThat(Files.exists(fixture.project.resolve(source)), "sourceExists").isFalse();
			requireThat(Files.readString(fixture.project.resolve(destination)), "destinationBytes").isEqualTo("content");
		}
	}

	/** Invalid native pathname text is retained as a typed call-bound rejection. */
	@Test
	public void rejectsRawTraversalPath() throws IOException
	{
		try (Fixture fixture = new Fixture(); GenerationEditSession session = fixture.session())
		{
			EvidenceRef call = fixture.call(session);
			GenerationEditDisposition.Rejected rejected = session.rejectRawOperation("invalid-path",
				GenerationEditOperation.Kind.CREATE, "../outside.md", Optional.empty(), "INVALID_PATH", call);
			requireThat(rejected.rejection().rawSourcePath(), "rawPath").isEqualTo("../outside.md");
			requireThat(session.rejectRawOperation("invalid-path", GenerationEditOperation.Kind.CREATE,
				"../outside.md", Optional.empty(), "INVALID_PATH", call), "replayedRejection").isEqualTo(rejected);
		}
	}

	/** FILE authority supports exact replacement, deletion, and recreation, but no sibling. */
	@Test
	public void fileRootReplaceDeleteAndRecreate() throws IOException
	{
		Path path = Path.of("prompts/rule.md");
		try (Fixture fixture = new Fixture(List.of(new EditableRoot(path, EditableRoot.Kind.FILE)));
			GenerationEditSession session = fixture.session())
		{
			Files.writeString(fixture.project.resolve(path), "before");
			GenerationEditOperation replace = edit("file-replace", GenerationEditOperation.Kind.REPLACE, path,
				GenerationExpectedState.regular(hash("before")), Optional.of("after"));
			requireThat(session.apply(replace, fixture.call(session, "file-call-1")) instanceof
				GenerationEditDisposition.Accepted, "replaced").isTrue();
			GenerationEditOperation delete = edit("file-delete", GenerationEditOperation.Kind.DELETE, path,
				GenerationExpectedState.regular(hash("after")), Optional.empty());
			requireThat(session.apply(delete, fixture.call(session, "file-call-2")) instanceof
				GenerationEditDisposition.Accepted, "deleted").isTrue();
			GenerationEditOperation recreate = edit("file-recreate", GenerationEditOperation.Kind.CREATE, path,
				GenerationExpectedState.absent(), Optional.of("again"));
			requireThat(session.apply(recreate, fixture.call(session, "file-call-3")) instanceof
				GenerationEditDisposition.Accepted, "recreated").isTrue();
			requireThat(Files.readString(fixture.project.resolve(path)), "finalBytes").isEqualTo("again");
			GenerationEditOperation sibling = edit("file-sibling", GenerationEditOperation.Kind.CREATE,
				Path.of("prompts/sibling.md"), GenerationExpectedState.absent(), Optional.of("outside"));
			requireThat(session.apply(sibling, fixture.call(session, "file-call-4")) instanceof
				GenerationEditDisposition.Rejected, "siblingRejected").isTrue();
		}
	}

	/** DIRECTORY authority permits child replacement and deletion. */
	@Test
	public void directoryChildReplaceAndDelete() throws IOException
	{
		try (Fixture fixture = new Fixture(); GenerationEditSession session = fixture.session())
		{
			Path path = Path.of("prompts/rule.md");
			Files.writeString(fixture.project.resolve(path), "before");
			GenerationEditOperation replace = edit("child-replace", GenerationEditOperation.Kind.REPLACE, path,
				GenerationExpectedState.regular(hash("before")), Optional.of("after"));
			requireThat(session.apply(replace, fixture.call(session, "child-call-1")) instanceof
				GenerationEditDisposition.Accepted, "childReplaced").isTrue();
			GenerationEditOperation delete = edit("child-delete", GenerationEditOperation.Kind.DELETE, path,
				GenerationExpectedState.regular(hash("after")), Optional.empty());
			requireThat(session.apply(delete, fixture.call(session, "child-call-2")) instanceof
				GenerationEditDisposition.Accepted, "childDeleted").isTrue();
			requireThat(Files.exists(fixture.project.resolve(path)), "childExists").isFalse();
		}
	}

	/** Rename may cross two separately confirmed editable roots. */
	@Test
	public void renamesAcrossConfirmedRoots() throws IOException
	{
		Path destination = Path.of("other/target.md");
		try (Fixture fixture = new Fixture(List.of(
			new EditableRoot(Path.of("prompts"), EditableRoot.Kind.DIRECTORY),
			new EditableRoot(destination, EditableRoot.Kind.FILE)));
			GenerationEditSession session = fixture.session())
		{
			Files.createDirectory(fixture.project.resolve("other"));
			Path source = Path.of("prompts/source.md");
			Files.writeString(fixture.project.resolve(source), "content");
			GenerationEditOperation rename = new GenerationEditOperation("cross-root",
				GenerationEditOperation.Kind.RENAME, source, Optional.of(destination),
				GenerationExpectedState.regular(hash("content")), Optional.of(GenerationExpectedState.absent()),
				Optional.empty());
			requireThat(session.apply(rename, fixture.call(session, "cross-call")) instanceof
				GenerationEditDisposition.Accepted, "crossRootAccepted").isTrue();
			requireThat(Files.exists(fixture.project.resolve(source)), "sourceExists").isFalse();
			requireThat(Files.readString(fixture.project.resolve(destination)), "destinationBytes").isEqualTo("content");
		}
	}

	/** A stale model hash cannot mutate the current user file. */
	@Test
	public void staleExpectedHashPreservesFile() throws IOException
	{
		try (Fixture fixture = new Fixture(); GenerationEditSession session = fixture.session())
		{
			Path path = Path.of("prompts/rule.md");
			Files.writeString(fixture.project.resolve(path), "actual");
			GenerationEditOperation stale = edit("stale", GenerationEditOperation.Kind.REPLACE, path,
				GenerationExpectedState.regular(hash("stale")), Optional.of("new"));
			try
			{
				session.apply(stale, fixture.call(session, "stale-call"));
				throw new AssertionError("Expected stale prestate failure");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "staleReason").contains("stale prestate");
			}
			requireThat(Files.readString(fixture.project.resolve(path)), "preservedBytes").isEqualTo("actual");
		}
	}

	/** One operation ID cannot be reused for different content. */
	@Test
	public void conflictingOperationIdFails() throws IOException
	{
		try (Fixture fixture = new Fixture(); GenerationEditSession session = fixture.session())
		{
			Path path = Path.of("prompts/rule.md");
			EvidenceRef call = fixture.call(session, "conflict-call");
			session.apply(edit("same-id", GenerationEditOperation.Kind.CREATE, path,
				GenerationExpectedState.absent(), Optional.of("first")), call);
			try
			{
				session.apply(edit("same-id", GenerationEditOperation.Kind.CREATE, path,
					GenerationExpectedState.absent(), Optional.of("second")), call);
				throw new AssertionError("Expected conflicting operation rejection");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "conflictReason").contains("Conflicting");
			}
			requireThat(Files.readString(fixture.project.resolve(path)), "firstBytes").isEqualTo("first");
		}
	}

	/** Symlink components and unsupported endpoint kinds become call-bound denials. */
	@Test
	public void rejectsSymlinkAndUnsupportedKind() throws IOException
	{
		try (Fixture fixture = new Fixture(); GenerationEditSession session = fixture.session())
		{
			Path external = fixture.project.resolve("external.md");
			Files.writeString(external, "external");
			Path link = Path.of("prompts/link.md");
			Files.createSymbolicLink(fixture.project.resolve(link), external);
			GenerationEditOperation symlink = edit("symlink", GenerationEditOperation.Kind.REPLACE, link,
				GenerationExpectedState.regular(hash("external")), Optional.of("changed"));
			requireThat(session.apply(symlink, fixture.call(session, "symlink-call")) instanceof
				GenerationEditDisposition.Rejected, "symlinkRejected").isTrue();
			Path directory = Path.of("prompts/subdir");
			Files.createDirectory(fixture.project.resolve(directory));
			GenerationEditOperation unsupported = edit("unsupported", GenerationEditOperation.Kind.CREATE,
				directory, GenerationExpectedState.absent(), Optional.of("file"));
			requireThat(session.apply(unsupported, fixture.call(session, "unsupported-call")) instanceof
				GenerationEditDisposition.Rejected, "directoryRejected").isTrue();
			requireThat(Files.readString(external), "externalBytes").isEqualTo("external");
			requireThat(Files.isDirectory(fixture.project.resolve(directory)), "directoryPreserved").isTrue();
		}
	}

	/**
	 * Builds an exact source-only generation edit.
	 *
	 * @param id call-local operation identity
	 * @param kind edit kind
	 * @param path project-relative source
	 * @param expected expected source state
	 * @param content optional replacement bytes as text
	 * @return validated edit operation
	 */
	private static GenerationEditOperation edit(String id, GenerationEditOperation.Kind kind, Path path,
		GenerationExpectedState expected, Optional<String> content)
	{
		return new GenerationEditOperation(id, kind, path, Optional.empty(), expected, Optional.empty(),
			content.map(value -> Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))));
	}

	/**
	 * Returns the exact UTF-8 content hash used by model prestates.
	 *
	 * @param value content text
	 * @return SHA-256 content hash
	 */
	private static String hash(String value)
	{
		return PromptSnapshot.sha256(value.getBytes(StandardCharsets.UTF_8));
	}

	/** Injects a failure after the real atomic predecessor capture. */
	private static final class FailAfterCapture implements CandidateFileOperations
	{
		private final CandidateFileOperations delegate = new JdkCandidateFileOperations();

		@Override
		public void writeAndForceNew(Path stage, byte[] bytes) throws IOException
		{
			delegate.writeAndForceNew(stage, bytes);
		}

		@Override
		public PathInspection captureAtomically(Path target, Path sibling) throws IOException
		{
			delegate.captureAtomically(target, sibling);
			throw new IOException("injected after capture");
		}

		@Override
		public LinkPublication linkNew(Path source, Path target, String hash) throws IOException
		{
			return delegate.linkNew(source, target, hash);
		}

		@Override
		public boolean sameFile(Path first, Path second) throws IOException
		{
			return delegate.sameFile(first, second);
		}

		@Override
		public PathInspection inspect(Path path) throws IOException
		{
			return delegate.inspect(path);
		}

		@Override
		public void unlinkPrivate(Path path) throws IOException
		{
			delegate.unlinkPrivate(path);
		}

		@Override
		public void deletePrivate(Path path) throws IOException
		{
			delegate.deletePrivate(path);
		}

		@Override
		public void createDirectoryNew(Path path) throws IOException
		{
			delegate.createDirectoryNew(path);
		}

		@Override
		public void deleteEmptyOwnedDirectory(Path path) throws IOException
		{
			delegate.deleteEmptyOwnedDirectory(path);
		}

		@Override
		public void forceDirectory(Path path) throws IOException
		{
			delegate.forceDirectory(path);
		}
	}

	/** Isolated campaign and project evidence for one generation call. */
	private static final class Fixture implements AutoCloseable
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final Path project;
		private final CampaignStore store;
		private final CandidateEvidenceRepository evidence;
		private final EvidenceRef checkpoint;
		private final EvidenceRef request = new EvidenceRef("run-requests", "1".repeat(64));
		private final EvidenceRef claim = new EvidenceRef("call-slot-claims", "2".repeat(64));

		/** Creates one pinned generation context. */
		private Fixture() throws IOException
		{
			this(List.of(new EditableRoot(Path.of("prompts"), EditableRoot.Kind.DIRECTORY)));
		}

		/**
		 * Creates a context with the specified confirmed root set.
		 *
		 * @param editableRoots confirmed authority
		 */
		private Fixture(List<EditableRoot> editableRoots) throws IOException
		{
			Path parent = cleanup.add(Files.createTempDirectory("generation-gateway-"));
			project = Files.createDirectory(parent.resolve("project")).toRealPath();
			Files.createDirectory(project.resolve("prompts"));
			store = CampaignStore.initialize(parent.resolve("campaign"), "campaign",
				new ModelProfile("test-model", Optional.empty(), Map.of()));
			evidence = new CandidateEvidenceRepository(store.directory(), store.binding());
			ConfirmedEditableRoots roots = new ConfirmedEditableRoots(2, project,
				CandidateTestRoots.contract(store.directory()),
				CandidateTestRoots.baseline(project, store.directory(), editableRoots), editableRoots);
			EvidenceRef rootsRef = evidence.publish("confirmed-editable-roots", roots);
			LiveGenerationCheckpoint value = new LiveGenerationCheckpoint(1, "checkpoint", "logical", project,
				"4".repeat(64), rootsRef, new EvidenceRef("git-file-inventories", "5".repeat(64)),
				Optional.empty());
			checkpoint = evidence.publish("live-generation-checkpoints", value);
		}

		/**
		 * Opens the call-bound edit capability.
		 *
		 * @return pinned generation session
		 */
		private GenerationEditSession session() throws IOException
		{
			return new GenerationEditSession(store.directory(), store.binding(), checkpoint, request, claim,
				"logical", false);
		}

		/**
		 * Opens a fault-injected test session.
		 *
		 * @param operations injected file operations
		 * @return pinned generation session
		 */
		private GenerationEditSession session(CandidateFileOperations operations) throws IOException
		{
			return new GenerationEditSession(store.directory(), store.binding(), checkpoint, request, claim,
				"logical", false, operations);
		}

		/**
		 * Retains one native callback.
		 *
		 * @param session registered generation session
		 * @return immutable tool call reference
		 */
		private EvidenceRef call(GenerationEditSession session) throws IOException
		{
			return call(session, "native-call");
		}

		/**
		 * Retains one specifically named native callback.
		 *
		 * @param session registered generation session
		 * @param nativeCallId unique native callback identity
		 * @return retained tool call reference
		 */
		private EvidenceRef call(GenerationEditSession session, String nativeCallId) throws IOException
		{
			EvidenceRef raw = evidence.publish("native-events", Map.of("arguments", "raw"));
			return session.recordToolCall("native-run", "native-session", "native-turn", nativeCallId,
				raw);
		}

		@Override
		public void close() throws IOException
		{
			cleanup.close();
		}
	}
}
