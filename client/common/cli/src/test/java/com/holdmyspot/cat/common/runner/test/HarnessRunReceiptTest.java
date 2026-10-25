package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.fakes.test.TestToolScope;
import com.holdmyspot.cat.common.runner.HarnessEvaluationResult;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunReceipt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.time.Clock;
import tools.jackson.databind.node.ObjectNode;
import org.testng.annotations.Test;

/**
 * Tests {@link HarnessRunReceipt}.
 */
public final class HarnessRunReceiptTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessRunReceiptTest()
	{
	}

	/**
	 * Verifies a runner receipt returns the exact retained project and final response to an evaluator.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void readsTypedRetainedEvidence() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("harness-run-receipt-"));
			HarnessRun run = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer()).create("harness");
			Path response = Files.writeString(run.directory().resolve("artifacts/response.txt"), "completed response");
			Path receipt = root.resolve("receipt.json");

			HarnessRunReceipt.write(scope.json(), Yielding.fileReplacer(), receipt, run, response, List.of());
			HarnessEvaluationResult result = HarnessRunReceipt.read(scope.json(), receipt);

			requireThat(result.retainedProject(), "retainedProject").isEqualTo(
				run.directory().resolve("project").toAbsolutePath().normalize());
			requireThat(result.rawResult(), "rawResult").isEqualTo(response.toAbsolutePath().normalize());
		}
	}

	/**
	 * Verifies that a receipt lists the session transcript copies and that an evaluator reads the list back, and that
	 * a receipt without copies lists none.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void listsSessionTranscripts() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-I7
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("harness-run-receipt-"));
			HarnessRun run = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer()).create("harness");
			Path response = Files.writeString(run.directory().resolve("artifacts/response.txt"), "completed response");
			Path transcript = Files.createDirectories(run.sessionTranscripts()).resolve("session-1.jsonl");
			Files.writeString(transcript, "{}\n");
			Path withCopies = root.resolve("with-copies.json");
			Path without = root.resolve("without.json");

			HarnessRunReceipt.write(scope.json(), Yielding.fileReplacer(), withCopies, run, response, List.of(transcript));
			HarnessRunReceipt.write(scope.json(), Yielding.fileReplacer(), without, run, response, List.of());

			requireThat(HarnessRunReceipt.read(scope.json(), withCopies).sessionTranscripts(), "sessionTranscripts").
				isEqualTo(List.of(transcript.toAbsolutePath().normalize()));
			requireThat(HarnessRunReceipt.read(scope.json(), without).sessionTranscripts(), "sessionTranscripts").
				isEmpty();
		}
	}

	/**
	 * Verifies the receipt lists requested-session identities and response paths and remains readable by evaluators.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void listsRequestedSessions() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-I9
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "receipt-"));
			HarnessRun run = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer()).create("harness");
			Path response = Files.writeString(run.directory().resolve("artifacts/response.txt"), "completed response");
			Path child = Files.createDirectories(run.directory().resolve("responses/first")).resolve("response.json");
			ObjectNode result = scope.json().createObjectNode().put("id", "first").put("harness", "harness").
				put("status", "completed").put("child_run_id", "child-run");
			scope.json().writeValue(child.toFile(), result);
			Path receipt = root.resolve("receipt.json");

			HarnessRunReceipt.write(scope.json(), Yielding.fileReplacer(), receipt, run, response, List.of());

			ObjectNode written = (ObjectNode) scope.json().readTree(receipt.toFile());
			requireThat(written.path("requested_sessions").size(), "requestedSessions").isEqualTo(1);
			requireThat(written.path("requested_sessions").get(0).path("response").asString(), "response").
				isEqualTo(child.toString());
			requireThat(HarnessRunReceipt.read(scope.json(), receipt).rawResult(), "rawResult").isEqualTo(response);
		}
	}

	/**
	 * Verifies an evaluator can validate a receipt it has already parsed without duplicating the receipt schema.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void convertsParsedReceipt() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("harness-run-receipt-"));
			Path project = Files.createDirectory(root.resolve("project"));
			Path response = Files.writeString(root.resolve("response.txt"), "completed response");
			ObjectNode receipt = scope.json().createObjectNode();
			receipt.put("retained_project", project.toString());
			receipt.put("raw_result", response.toString());

			HarnessEvaluationResult result = HarnessRunReceipt.fromReceipt(receipt, root.resolve("receipt.json"));

			requireThat(result.retainedProject(), "retainedProject").isEqualTo(project.toAbsolutePath().normalize());
			requireThat(result.rawResult(), "rawResult").isEqualTo(response.toAbsolutePath().normalize());
		}
	}

	/**
	 * Verifies a receipt cannot add unrecognized data that an evaluator might otherwise ignore.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*unsupported fields.*")
	public void rejectsReceiptWithUnknownField() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("harness-run-receipt-"));
			Path project = Files.createDirectory(root.resolve("project"));
			Path response = Files.writeString(root.resolve("response.txt"), "completed response");
			ObjectNode fields = scope.json().createObjectNode();
			fields.put("retained_project", project.toString());
			fields.put("raw_result", response.toString());
			fields.put("unexpected", true);
			Path receipt = Files.writeString(root.resolve("receipt.json"), scope.json().writeValueAsString(fields));

			HarnessRunReceipt.read(scope.json(), receipt);
		}
	}
}
