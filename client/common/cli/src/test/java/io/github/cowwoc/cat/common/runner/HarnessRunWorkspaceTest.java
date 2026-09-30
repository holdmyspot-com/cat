package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceReader;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.api.SessionMode;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunRequest;
import io.github.cowwoc.cat.common.optimizer.runner.PrivateGenerationContext;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceContext;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceEntry;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceInputRole;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceKind;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceManifest;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceMaterializer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Checks retained execution paths for copied evaluator projects and private authoring worktrees.
 */
public final class HarnessRunWorkspaceTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessRunWorkspaceTest()
	{
	}

	/**
	 * Verifies a retained evaluator call can identify its copied native project.
	 *
	 * @throws IOException if the test-owned files cannot be created or read
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test
	public void acceptsSterileCopiedProject() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-harness-sterile-result-"));
			SterileFixture fixture = sterileFixture(root);
			requireThat(HarnessRunWorkspace.executionProject(fixture.run(), fixture.request(),
				fixture.evidenceReader()), "executionProject").isEqualTo(fixture.copiedProject());
		}
	}

	/**
	 * Verifies that a changed copied authority file cannot support a harness evaluator result.
	 *
	 * @throws IOException if the test-owned files cannot be created or read
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test
	public void rejectsChangedSterileCopiedInput() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-harness-sterile-changed-"));
			SterileFixture fixture = sterileFixture(root);
			Files.writeString(fixture.copiedProject().resolve("authority/rubric.txt"), "changed rubric");
			try
			{
				HarnessRunWorkspace.executionProject(fixture.run(), fixture.request(),
					fixture.evidenceReader());
				throw new AssertionError("changed copied authority was accepted as a harness result");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "changedInput").contains("hash");
			}
		}
	}

	/**
	 * A sealed generation binding identifies the private worktree used by the native call.
	 *
	 * @throws IOException if test-owned paths or the binding cannot be read
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test
	public void acceptsSealedPrivateGeneration() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-harness-private-result-"));
			GenerationFixture fixture = generationFixture(root);
			new HarnessRunWorkspace(fixture.run().id(), fixture.context(),
				fixture.context().privateProject()).seal(fixture.run());
			requireThat(HarnessRunWorkspace.executionProject(fixture.run(), fixture.request(), ignored ->
				new byte[0]), "executionProject").isEqualTo(fixture.context().privateProject());
		}
	}

	/**
	 * Startup cannot seal a private execution binding after its worktree disappears.
	 *
	 * @throws IOException if the test-owned workspace cannot be created or removed
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*Private harness worktree.*")
	public void rejectsMissingPrivateWorktreeAtSeal() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-harness-private-start-"));
			GenerationFixture fixture = generationFixture(root);
			Files.delete(fixture.context().privateProject());
			new HarnessRunWorkspace(fixture.run().id(), fixture.context(),
				fixture.context().privateProject()).seal(fixture.run());
		}
	}

	/**
	 * A generated result cannot infer private execution from an unsealed run directory.
	 *
	 * @throws IOException if test-owned paths cannot be created
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test
	public void rejectsMissingPrivateGenerationBinding() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-harness-private-missing-"));
			GenerationFixture fixture = generationFixture(root);
			try
			{
				HarnessRunWorkspace.executionProject(fixture.run(), fixture.request(), ignored -> new byte[0]);
				throw new AssertionError("unsealed private execution was accepted");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "missingBinding").contains("binding is missing");
			}
		}
	}

	/**
	 * A binding for another generation request cannot settle the current native call.
	 *
	 * @throws IOException if test-owned paths or the binding cannot be read
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test
	public void rejectsOtherPrivateGenerationRequest() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-harness-private-other-"));
			GenerationFixture fixture = generationFixture(root);
			PrivateGenerationContext other = new PrivateGenerationContext(fixture.context().selectedProject(),
				fixture.context().privateProject(), new EvidenceRef("candidate-generation-requests", "b".repeat(64)),
				fixture.context().readinessRef(), fixture.context().confirmedRootsRef());
			new HarnessRunWorkspace(fixture.run().id(), other, other.privateProject()).seal(fixture.run());
			try
			{
				HarnessRunWorkspace.executionProject(fixture.run(), fixture.request(), ignored -> new byte[0]);
				throw new AssertionError("another generation request was accepted");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "otherRequest").contains("differs from requested run");
			}
		}
	}

	/**
	 * The selected project cannot be sealed as a private generation execution path.
	 *
	 * @throws IOException if test-owned paths cannot be created
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "Private harness execution project differs from generation worktree")
	public void rejectsSelectedPrivateExecutionPath() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-harness-private-selected-"));
			GenerationFixture fixture = generationFixture(root);
			new HarnessRunWorkspace(fixture.run().id(), fixture.context(),
				fixture.context().selectedProject());
		}
	}

	/**
	 * Creates a separate selected project, private worktree and owned native run.
	 *
	 * @param root the test-owned parent directory
	 * @return a private generation request and its run
	 * @throws IOException if a test-owned directory cannot be created
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	private static GenerationFixture generationFixture(Path root) throws IOException, NoSuchAlgorithmException
	{
		Path selected = Files.createDirectory(root.resolve("selected")).toRealPath();
		Path privateProject = Files.createDirectory(root.resolve("private")).toRealPath();
		HarnessRun run = new HarnessRun("harnessa", "run-001", Files.createDirectory(root.resolve("run")));
		byte[] authority = "generation authority".getBytes(StandardCharsets.UTF_8);
		PrivateGenerationContext context = new PrivateGenerationContext(selected, privateProject,
			ref("candidate-generation-requests", authority), ref("generation-attempt-readiness", authority),
			ref("confirmed-editable-roots", authority));
		NestedRunRequest request = new NestedRunRequest("generation-call", EvidencePurpose.DEVELOPMENT,
			context, List.of("improve prompt"), SessionMode.SINGLE_TURN,
			new ModelProfile("gpt-6-sol", Optional.empty(), Map.of()), List.of());
		return new GenerationFixture(run, context, request);
	}

	/**
	 * Carries the test-owned private generation inputs.
	 *
	 * @param run retained native run
	 * @param context selected and private worktree binding
	 * @param request native call made in the private worktree
	 */
	private record GenerationFixture(HarnessRun run, PrivateGenerationContext context, NestedRunRequest request)
	{
	}

	/**
	 * Creates one copied evaluator project and its retained request inputs.
	 *
	 * @param root the test-owned parent directory
	 * @return the native result fixture
	 * @throws IOException if the workspace cannot be materialized
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	private static SterileFixture sterileFixture(Path root) throws IOException, NoSuchAlgorithmException
	{
		Path runDirectory = Files.createDirectory(root.resolve("run"));
		HarnessRun run = new HarnessRun("harnessa", "run-001", runDirectory);
		byte[] authority = "frozen rubric".getBytes(StandardCharsets.UTF_8);
		EvidenceRef authorityRef = ref("sterile-inputs", authority);
		String turn = "evaluate";
		String turnHash = ref("turns", turn.getBytes(StandardCharsets.UTF_8)).evidenceId();
		SterileWorkspaceManifest manifest = new SterileWorkspaceManifest(1,
			SterileWorkspaceKind.EVALUATOR, new EvidenceRef("evaluator-requests", "b".repeat(64)),
			List.of(turnHash), List.of(new SterileWorkspaceEntry(Path.of("authority/rubric.txt"),
				SterileWorkspaceInputRole.AUTHORITY, authorityRef, authorityRef.evidenceId())));
		byte[] manifestBytes = manifest.bytes();
		EvidenceRef manifestRef = ref("sterile-workspace-manifests", manifestBytes);
		SterileWorkspaceContext context = new SterileWorkspaceContext(SterileWorkspaceKind.EVALUATOR,
			manifestRef);
		EvidenceReader evidenceReader = reference ->
		{
			if (reference.equals(manifestRef))
				return manifestBytes;
			if (reference.equals(authorityRef))
				return authority;
			throw new IOException("unrecognized fixture evidence");
		};
		Path copiedProject = runDirectory.resolve("project");
		SterileWorkspaceMaterializer.materialize(context, evidenceReader, copiedProject);
		NestedRunRequest request = new NestedRunRequest("evaluate-call", EvidencePurpose.DEVELOPMENT,
			context, List.of(turn), SessionMode.SINGLE_TURN,
			new ModelProfile("gpt-6-sol", Optional.empty(), Map.of()), List.of());
		return new SterileFixture(run, request, evidenceReader, copiedProject);
	}

	/**
	 * Holds one test-owned native result and its immutable request inputs.
	 *
	 * @param run the retained owned native run
	 * @param request the evaluator's exact native request
	 * @param evidenceReader the source of retained manifest and input bytes
	 * @param copiedProject the copied native project
	 */
	private record SterileFixture(HarnessRun run, NestedRunRequest request,
		EvidenceReader evidenceReader, Path copiedProject)
	{
	}

	/**
	 * Returns the content address for retained fixture bytes.
	 *
	 * @param collection the immutable evidence collection
	 * @param bytes the retained content
	 * @return the content address
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	private static EvidenceRef ref(String collection, byte[] bytes) throws NoSuchAlgorithmException
	{
		return new EvidenceRef(collection,
			HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
	}
}
