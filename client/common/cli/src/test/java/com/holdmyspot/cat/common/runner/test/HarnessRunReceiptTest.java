package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.runner.HarnessEvaluationResult;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunReceipt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("harness-run-receipt-"));
			HarnessRun run = new HarnessRunManager(root, Clock.systemUTC()).create("harness");
			Path response = Files.writeString(run.directory().resolve("artifacts/response.txt"), "completed response");
			Path receipt = root.resolve("receipt.json");

			HarnessRunReceipt.write(scope.json(), receipt, run, response);
			HarnessEvaluationResult result = HarnessRunReceipt.read(scope.json(), receipt);

			requireThat(result.retainedProject(), "retainedProject").isEqualTo(
				run.directory().resolve("project").toAbsolutePath().normalize());
			requireThat(result.rawResult(), "rawResult").isEqualTo(response.toAbsolutePath().normalize());
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
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
