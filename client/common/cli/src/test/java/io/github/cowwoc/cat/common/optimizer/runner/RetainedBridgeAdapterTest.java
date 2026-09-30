package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.api.SessionMode;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.HarnessRunSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Checks terminal completion mapping without dispatch, campaign registration, or a native process. */
public final class RetainedBridgeAdapterTest
{
	/** Creates a test instance. */
	public RetainedBridgeAdapterTest()
	{
	}

	/**
	 * A failed bridge still supplies the responsible reader's settlement and exact retained native evidence.
	 *
	 * @throws IOException if the fixture directory cannot be created
	 */
	@Test
	public void retainsSettlementAfterBridgeFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-bridge-failure-settlement-"));
			TestAdapter adapter = new TestAdapter(root, NativeHarness.CLAUDE, ReadMode.RETURN);
			NestedRunCompletion result = adapter.settleBridgeCompletion(
				CompletableFuture.failedFuture(new IOException("bridge failed")), request(root), "test-1",
				root.resolve("test-1.jsonl"), "a".repeat(64)).toCompletableFuture().join();
			Assert.assertSame(result, adapter.settlement);
			Assert.assertEquals(((NestedRunCompletion.Failed) result).failure().nativeEvidence().size(), 1);
			Assert.expectThrows(NullPointerException.class, () -> new TestAdapter(root, null, ReadMode.RETURN));
		}
	}

	/**
	 * Unreadable evidence preserves transport failure or receipt failure according to the actual bridge result.
	 *
	 * @throws IOException if the fixture directory cannot be created
	 */
	@Test
	public void reportsUnreadableRetainedCompletion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-unreadable-bridge-settlement-"));
			TestAdapter adapter = new TestAdapter(root, NativeHarness.CODEX, ReadMode.IO_FAILURE);
			NestedRunCompletion.Failed receipt = (NestedRunCompletion.Failed) adapter.settleBridgeCompletion(
				CompletableFuture.completedFuture(new HarnessRun("codex", "test-1", root.resolve("run"))),
				request(root), "test-1", root.resolve("test-1.jsonl"), "a".repeat(64)).toCompletableFuture().join();
			Assert.assertEquals(receipt.failure().kind(), NestedRunFailureKind.RECEIPT_FAILED);
			Assert.assertTrue(receipt.failure().reason().contains("unreadable evidence"));
			NestedRunCompletion.Failed transport = (NestedRunCompletion.Failed) adapter.settleBridgeCompletion(
				CompletableFuture.failedFuture(new IOException("bridge failed")), request(root), "test-1",
				root.resolve("test-1.jsonl"), "a".repeat(64)).toCompletableFuture().join();
			Assert.assertEquals(transport.failure().kind(), NestedRunFailureKind.TRANSPORT_FAILED);
			Assert.assertTrue(transport.failure().reason().contains("bridge failed"));
		}
	}

	/**
	 * An unchecked reader defect completes exceptionally without waiting or hiding its original cause.
	 *
	 * @throws IOException if the fixture directory cannot be created
	 */
	@Test
	public void terminatesUncheckedReaderFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-reader-defect-settlement-"));
			TestAdapter adapter = new TestAdapter(root, NativeHarness.CODEX, ReadMode.UNCHECKED_FAILURE);
			CompletableFuture<NestedRunCompletion> result = adapter.settleBridgeCompletion(
				CompletableFuture.completedFuture(new HarnessRun("codex", "test-1", root.resolve("run"))),
				request(root), "test-1", root.resolve("test-1.jsonl"), "a".repeat(64)).toCompletableFuture();
			Assert.assertTrue(result.isCompletedExceptionally());
			CompletionException failure = Assert.expectThrows(CompletionException.class, result::join);
			Assert.assertTrue(failure.getCause() instanceof IllegalStateException);
		}
	}

	/**
	 * Supplies only the identity and context required by completion mapping.
	 *
	 * @param root fixture directory
	 * @return original logical request
	 */
	private static NestedRunRequest request(Path root)
	{
		return new NestedRunRequest("call", EvidencePurpose.DEVELOPMENT,
			new ProjectRunContext(new ProjectExecutionSnapshot(root,
				new EvidenceRef("snapshot-manifests", "b".repeat(64))), ProjectWorkspaceMode.ISOLATED_COPY,
				Optional.empty(), Optional.empty()), List.of("task"), SessionMode.SINGLE_TURN,
			new ModelProfile("model", Optional.empty(), Map.of()), List.of());
	}

	/** Supported reader behaviors at this test's completion boundary. */
	private enum ReadMode
	{
		RETURN,
		IO_FAILURE,
		UNCHECKED_FAILURE
	}

	/** Supplies result-reader behavior while refusing native launch outside this test's claim. */
	private static final class TestAdapter extends RetainedBridgeAdapter
	{
		private final ReadMode mode;
		private final NestedRunCompletion settlement = new NestedRunCompletion.Failed(new NestedRunFailure(
			NestedRunFailureKind.INTERRUPTED, "call", Optional.of("test-1"), "retained interruption",
			List.of(new EvidenceRef("native-events", "a".repeat(64))), Optional.empty(), Optional.empty()));

		/**
		 * Configures only the terminal reader; no campaign or native process is created.
		 *
		 * @param root fixture directory
		 * @param harness native harness identity
		 * @param mode reader behavior
		 */
		private TestAdapter(Path root, NativeHarness harness, ReadMode mode)
		{
			super(harness, new CampaignStore(root.resolve("campaign")),
				new CampaignBinding("campaign", root.resolve("campaign")),
				new HarnessRunManager(root.resolve("runs"), Clock.systemUTC()), root.resolve("artifact"),
				root.resolve("transcripts"), root.resolve("temporary"));
			this.mode = mode;
		}

		@Override
		protected void validateHarnessRequest(NestedRunRequest request) throws IOException
		{
			requireSupportedToolsAndSettings(request);
		}

		@Override
		protected HarnessRunSession startPreparedNativeSession(NestedRunRequest request, Path artifact,
			String runId, NativeRunSource source)
		{
			throw new AssertionError("Completion mapping must not dispatch a native session");
		}

		@Override
		protected NestedRunCompletion readCompleted(NestedRunRequest request, String runId, Path transcript,
			String runtimeManifestSha256) throws IOException
		{
			return switch (mode)
			{
				case IO_FAILURE -> throw new IOException("unreadable evidence");
				case UNCHECKED_FAILURE -> throw new IllegalStateException("reader defect");
				case RETURN -> settlement;
			};
		}
	}
}
