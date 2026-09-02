package io.github.cowwoc.cat.common.runner;
import io.github.cowwoc.cat.common.RecordStore;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests the durable lifecycle around one typed harness evaluation.
 */
public final class HarnessEvaluationOperationTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public HarnessEvaluationOperationTest()
	{
	}

	/**
	 * Closes the operation-owned JSON scope after the tests complete.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies a completed operation retains its exact profile and evidence fingerprints, then reuses that result on an
	 * identical retry.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsAndReusesCompletedEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("harness-evaluation-operation-"));
			HarnessEvaluationRequest request = request(root, "{\"type\":\"complete\"}\n");
			AtomicInteger invocations = new AtomicInteger();
			HarnessEvaluationRunner runner = current ->
			{
				invocations.incrementAndGet();
				ObjectNode running = (ObjectNode) RecordStore.read(scope.json(), manifest(root));
				requireThat(running.path("state").asString(), "stateDuringExecution").isEqualTo("running");
				Path retainedProject = Files.createDirectory(root.resolve("retained-project"));
				Path rawResult = Files.writeString(root.resolve("raw-result.jsonl"), "completed\n");
				return new HarnessEvaluationExecution(HarnessEvaluationResult.retained(retainedProject, rawResult), 10, 20);
			};

			HarnessEvaluationExecution first = HarnessEvaluationOperation.execute(scope.json(), request, runner);
			HarnessEvaluationExecution second = HarnessEvaluationOperation.execute(scope.json(), request, runner);

			requireThat(invocations.get(), "runnerInvocations").isEqualTo(1);
			requireThat(second.result(), "reusedResult").isEqualTo(first.result());
			ObjectNode completed = (ObjectNode) RecordStore.read(scope.json(), manifest(root));
			requireThat(completed.path("state").asString(), "state").isEqualTo("completed");
			requireThat(completed.path("model").asString(), "model").isEqualTo("gpt-5.6-luna");
			requireThat(completed.path("effort").asString(), "effort").isEqualTo("medium");
			requireThat(completed.path("input_sha256").asString(), "inputFingerprint").isNotBlank();
			requireThat(completed.path("raw_result_sha256").asString(), "rawResultFingerprint").isNotBlank();
			requireThat(completed.path("retained_project_sha256").asString(), "projectFingerprint").isNotBlank();
		}
	}

	/**
	 * Verifies one operation identity rejects changed input instead of spending on another runner call.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Operation input differs from its retained manifest.*")
	public void rejectsChangedInputOnRetry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("harness-evaluation-operation-"));
			AtomicInteger invocations = new AtomicInteger();
			HarnessEvaluationRunner runner = current ->
			{
				invocations.incrementAndGet();
				Path retainedProject = Files.createDirectory(root.resolve("retained-project"));
				Path rawResult = Files.writeString(root.resolve("raw-result.jsonl"), "completed\n");
				return new HarnessEvaluationExecution(HarnessEvaluationResult.retained(retainedProject, rawResult), 10, 20);
			};
			HarnessEvaluationOperation.execute(scope.json(), request(root, "{\"type\":\"complete\"}\n"), runner);

			HarnessEvaluationOperation.execute(scope.json(), request(root, "{\"type\":\"turn\"}\n"), runner);
		}
	}

	/**
	 * Verifies read-only evidence content is part of the operation identity, not merely its filesystem path.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Operation input differs from its retained manifest.*")
	public void rejectsChangedReadOnlyEvidenceOnRetry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("harness-evaluation-operation-"));
			Path evidence = Files.createDirectories(root.resolve("evidence"));
			Path evidenceFile = Files.writeString(evidence.resolve("result.txt"), "original");
			Path project = Files.createDirectories(root.resolve("project"));
			HarnessModelProfile profile = HarnessModelProfile.fromExternalValues("gpt-5.6-luna", "medium");
			HarnessEvaluationRequest request = new HarnessEvaluationRequest(root.resolve("runner"),
				root.resolve("artifact"), project, profile, "{\"type\":\"complete\"}\n",
				root.resolve("transcript.jsonl"), root.resolve("receipt.json"), false, List.of(evidence));
			HarnessEvaluationRunner runner = current ->
			{
				Path retainedProject = Files.createDirectory(root.resolve("retained-project"));
				Path rawResult = Files.writeString(root.resolve("raw-result.jsonl"), "completed\n");
				return new HarnessEvaluationExecution(HarnessEvaluationResult.retained(retainedProject, rawResult), 10, 20);
			};
			HarnessEvaluationOperation.execute(scope.json(), request, runner);
			Files.writeString(evidenceFile, "changed");

			HarnessEvaluationOperation.execute(scope.json(), request, runner);
		}
	}

	/**
	 * Verifies a failed runner call receives one terminal failure record and is not repeated by an identical retry.
	 *
	 * @throws IOException for the expected runner failure
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*fixture runner failed.*")
	public void retainsFailedOperation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("harness-evaluation-operation-"));
			HarnessEvaluationRequest request = request(root, "{\"type\":\"complete\"}\n");
			AtomicInteger invocations = new AtomicInteger();
			HarnessEvaluationRunner runner = current ->
			{
				invocations.incrementAndGet();
				throw new IOException("fixture runner failed");
			};
			try
			{
				HarnessEvaluationOperation.execute(scope.json(), request, runner);
			}
			catch (IOException firstFailure)
			{
				requireThat(firstFailure.getMessage(), "firstFailure").contains("fixture runner failed");
				ObjectNode failed = (ObjectNode) RecordStore.read(scope.json(), manifest(root));
				requireThat(failed.path("state").asString(), "state").isEqualTo("failed");
			}

			HarnessEvaluationOperation.execute(scope.json(), request, runner);
		}
	}

	/**
	 * Verifies an interrupted owner still retains the active operation, then yields before another runner call.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void completesActiveOperationBeforeYielding() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("harness-evaluation-operation-"));
			HarnessEvaluationRequest active = request(root.resolve("active"), "{\"type\":\"complete\"}\n");
			HarnessEvaluationRequest next = request(root.resolve("next"), "{\"type\":\"complete\"}\n");
			AtomicInteger nextInvocations = new AtomicInteger();
			HarnessEvaluationRunner interruptedRunner = current ->
			{
				Path retainedProject = Files.createDirectory(root.resolve("retained-project"));
				Path rawResult = Files.writeString(root.resolve("raw-result.jsonl"), "completed\n");
				return new HarnessEvaluationExecution(HarnessEvaluationResult.retained(retainedProject, rawResult), 10, 20,
					true);
			};

			try
			{
				HarnessEvaluationOperation.execute(scope.json(), active, interruptedRunner);
				requireThat(Thread.currentThread().isInterrupted(), "ownerInterrupted").isTrue();
				ObjectNode completed = (ObjectNode) RecordStore.read(scope.json(), manifest(root.resolve("active")));
				requireThat(completed.path("state").asString(), "activeState").isEqualTo("completed");
				try
				{
					HarnessEvaluationOperation.execute(scope.json(), next, current ->
					{
						nextInvocations.incrementAndGet();
						throw new IOException("next runner must not start");
					});
					throw new AssertionError("Interrupted owner started another harness evaluation");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "interruptionFailure").
						contains("interrupted before the next harness evaluation");
				}
				requireThat(nextInvocations.get(), "nextRunnerInvocations").isEqualTo(0);
			}
			finally
			{
				Thread.interrupted();
			}
		}
	}

	/**
	 * Creates one typed launch-profile request with operation-owned evidence destinations.
	 *
	 * @param root the test-owned operation directory
	 * @param input the complete runner input
	 * @return the typed runner request
	 * @throws IOException if the project cannot be created
	 */
	private static HarnessEvaluationRequest request(Path root, String input) throws IOException
	{
		Path project = root.resolve("project");
		Files.createDirectories(project);
		HarnessModelProfile profile = HarnessModelProfile.fromExternalValues("gpt-5.6-luna", "medium");
		return new HarnessEvaluationRequest(root.resolve("runner"), root.resolve("artifact"), project, profile, input,
			root.resolve("transcript.jsonl"), root.resolve("receipt.json"), true, List.of());
	}

	/**
	 * Returns the durable manifest path owned by the fixture operation.
	 *
	 * @param root the test-owned operation directory
	 * @return the operation manifest path
	 */
	private static Path manifest(Path root)
	{
		return root.resolve("receipt.json.operation.json");
	}
}
