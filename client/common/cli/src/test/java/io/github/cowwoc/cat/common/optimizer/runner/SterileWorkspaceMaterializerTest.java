package io.github.cowwoc.cat.common.optimizer.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests creation of a native evaluator workspace from retained input bytes.
 */
public final class SterileWorkspaceMaterializerTest
{
	/**
	 * Creates the test fixture.
	 */
	public SterileWorkspaceMaterializerTest()
	{
	}

	/**
	 * Verifies that the evaluator receives exact authority and inert evidence in separate files.
	 *
	 * @throws IOException if fixture or workspace I/O fails
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test
	public void materializesExactEvaluatorInputs() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sterile-files-"));
			byte[] authority = "frozen rubric".getBytes(StandardCharsets.UTF_8);
			byte[] evidence = "trial response".getBytes(StandardCharsets.UTF_8);
			EvidenceRef authorityRef = ref("sterile-inputs", authority);
			EvidenceRef evidenceRef = ref("sterile-inputs", evidence);
			SterileWorkspaceManifest manifest = manifest(authorityRef, evidenceRef);
			byte[] manifestBytes = manifest.bytes();
			EvidenceRef manifestRef = ref("sterile-workspace-manifests", manifestBytes);
			Path workspace = root.resolve("evaluator");

			SterileWorkspaceManifest created = SterileWorkspaceMaterializer.materialize(
				new SterileWorkspaceContext(SterileWorkspaceKind.EVALUATOR, manifestRef), reference ->
				{
					if (reference.equals(manifestRef))
						return manifestBytes;
					if (reference.equals(authorityRef))
						return authority;
					if (reference.equals(evidenceRef))
						return evidence;
					throw new IOException("unrecognized fixture evidence");
				}, workspace);

			requireThat(created, "createdManifest").isEqualTo(manifest);
			requireThat(Files.readAllBytes(workspace.resolve("authority/rubric.txt")), "authorityBytes").
				isEqualTo(authority);
			requireThat(Files.readAllBytes(workspace.resolve("evidence/response.txt")), "evidenceBytes").
				isEqualTo(evidence);
			SterileWorkspaceMaterializer.verify(
				new SterileWorkspaceContext(SterileWorkspaceKind.EVALUATOR, manifestRef), reference ->
				{
					if (reference.equals(manifestRef))
						return manifestBytes;
					if (reference.equals(authorityRef))
						return authority;
					if (reference.equals(evidenceRef))
						return evidence;
					throw new IOException("unrecognized fixture evidence");
				}, workspace);
		}
	}

	/**
	 * Verifies that missing input prevents publication of a partial evaluator workspace.
	 *
	 * @throws IOException if fixture I/O fails
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test
	public void missingInputLeavesNoWorkspace() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sterile-missing-"));
			byte[] authority = "frozen rubric".getBytes(StandardCharsets.UTF_8);
			byte[] evidence = "trial response".getBytes(StandardCharsets.UTF_8);
			EvidenceRef authorityRef = ref("sterile-inputs", authority);
			EvidenceRef evidenceRef = ref("sterile-inputs", evidence);
			byte[] manifestBytes = manifest(authorityRef, evidenceRef).bytes();
			EvidenceRef manifestRef = ref("sterile-workspace-manifests", manifestBytes);
			Path workspace = root.resolve("evaluator");

			try
			{
				SterileWorkspaceMaterializer.materialize(
					new SterileWorkspaceContext(SterileWorkspaceKind.EVALUATOR, manifestRef), reference ->
					{
						if (reference.equals(manifestRef))
							return manifestBytes;
						if (reference.equals(authorityRef))
							return authority;
						throw new IOException("trial evidence is missing");
					}, workspace);
				throw new AssertionError("missing evaluator input was accepted");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "missingInput").contains("missing");
			}
			requireThat(Files.exists(workspace), "partialWorkspace").isFalse();
		}
	}

	/**
	 * Verifies that changed retained bytes cannot become native evaluator authority.
	 *
	 * @throws IOException if fixture I/O fails
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test
	public void changedInputLeavesNoWorkspace() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sterile-changed-"));
			byte[] authority = "frozen rubric".getBytes(StandardCharsets.UTF_8);
			byte[] evidence = "trial response".getBytes(StandardCharsets.UTF_8);
			EvidenceRef authorityRef = ref("sterile-inputs", authority);
			EvidenceRef evidenceRef = ref("sterile-inputs", evidence);
			byte[] manifestBytes = manifest(authorityRef, evidenceRef).bytes();
			EvidenceRef manifestRef = ref("sterile-workspace-manifests", manifestBytes);
			Path workspace = root.resolve("evaluator");

			try
			{
				SterileWorkspaceMaterializer.materialize(
					new SterileWorkspaceContext(SterileWorkspaceKind.EVALUATOR, manifestRef), reference ->
					{
						if (reference.equals(manifestRef))
							return manifestBytes;
						if (reference.equals(authorityRef))
							return "changed rubric".getBytes(StandardCharsets.UTF_8);
						return evidence;
					}, workspace);
				throw new AssertionError("changed evaluator authority was accepted");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "changedInput").contains("hash");
			}
			requireThat(Files.exists(workspace), "partialWorkspace").isFalse();
		}
	}

	/**
	 * Verifies a changed file in the copied native workspace cannot support result adoption.
	 *
	 * @throws IOException if fixture or workspace I/O fails
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test
	public void rejectsChangedCopiedInput() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sterile-copy-"));
			byte[] authority = "frozen rubric".getBytes(StandardCharsets.UTF_8);
			byte[] evidence = "trial response".getBytes(StandardCharsets.UTF_8);
			EvidenceRef authorityRef = ref("sterile-inputs", authority);
			EvidenceRef evidenceRef = ref("sterile-inputs", evidence);
			byte[] manifestBytes = manifest(authorityRef, evidenceRef).bytes();
			EvidenceRef manifestRef = ref("sterile-workspace-manifests", manifestBytes);
			SterileWorkspaceContext context = new SterileWorkspaceContext(SterileWorkspaceKind.EVALUATOR,
				manifestRef);
			Path workspace = root.resolve("evaluator");
			SterileWorkspaceMaterializer.materialize(context, reference ->
			{
				if (reference.equals(manifestRef))
					return manifestBytes;
				if (reference.equals(authorityRef))
					return authority;
				return evidence;
			}, workspace);
			Files.writeString(workspace.resolve("authority/rubric.txt"), "changed rubric");

			try
			{
				SterileWorkspaceMaterializer.verify(context, reference ->
				{
					if (reference.equals(manifestRef))
						return manifestBytes;
					if (reference.equals(authorityRef))
						return authority;
					return evidence;
				}, workspace);
				throw new AssertionError("changed copied evaluator authority was accepted");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "changedCopy").contains("hash");
			}
		}
	}

	/**
	 * Creates a two-file evaluator manifest with sorted paths.
	 *
	 * @param authorityRef the frozen evaluator authority bytes
	 * @param evidenceRef the inert trial response bytes
	 * @return the manifest for the test-owned native workspace
	 */
	private static SterileWorkspaceManifest manifest(EvidenceRef authorityRef, EvidenceRef evidenceRef)
	{
		return new SterileWorkspaceManifest(1, SterileWorkspaceKind.EVALUATOR,
			new EvidenceRef("evaluator-requests", "b".repeat(64)), List.of("a".repeat(64)), List.of(
				new SterileWorkspaceEntry(Path.of("authority/rubric.txt"), SterileWorkspaceInputRole.AUTHORITY,
					authorityRef, authorityRef.evidenceId()),
				new SterileWorkspaceEntry(Path.of("evidence/response.txt"), SterileWorkspaceInputRole.INERT_EVIDENCE,
					evidenceRef, evidenceRef.evidenceId())));
	}

	/**
	 * Returns the content address of fixture bytes.
	 *
	 * @param collection the immutable evidence collection
	 * @param bytes the exact retained content
	 * @return the content address
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	private static EvidenceRef ref(String collection, byte[] bytes) throws NoSuchAlgorithmException
	{
		return new EvidenceRef(collection,
			HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
	}
}
