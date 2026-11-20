package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.fakes.test.TestToolScope;
import com.holdmyspot.cat.common.runner.HarnessEvaluationResult;
import com.holdmyspot.cat.common.runner.NestedSession;
import com.holdmyspot.cat.common.runner.NestedSessionManager;
import com.holdmyspot.cat.common.runner.NestedSessionReceipt;
import com.holdmyspot.cat.common.runner.ResultsCopy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests {@link NestedSessionReceipt}.
 */
public final class NestedSessionReceiptTest
{
	/**
	 * Creates the test fixture.
	 */
	public NestedSessionReceiptTest()
	{
	}

	/**
	 * Verifies the receipt stores paths relative to the retained project and keeps an external response in the nested
	 * session.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void writesProjectDerivedRelativePaths() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "receipt-relative-"));
			NestedSession nestedSession = new NestedSessionManager(fileLocks, root.resolve("runs"),
				Clock.systemUTC(), Yielding.fileReplacer()).create("harness");
			Path externalOutput = Files.writeString(Files.createDirectories(root.resolve("requested")).
				resolve("output.jsonl"), "response bytes");
			Path nativeTranscript = Files.writeString(Files.createDirectories(
				nestedSession.sessionTranscripts().resolve("subagents")).resolve("native.jsonl"), "native bytes");
			Path nestedResponse = Files.createDirectories(nestedSession.directory().resolve("responses/nested")).
				resolve("response.json");
			ObjectNode response = scope.json().createObjectNode().put("id", "nested").put("harness", "harness").
				put("status", "completed").put("session_id", "child-session");
			scope.json().writeValue(nestedResponse.toFile(), response);
			Path receipt = root.resolve("receipt.json");

			NestedSessionReceipt.write(scope.json(), Yielding.fileReplacer(), receipt, nestedSession, externalOutput,
				List.of(nativeTranscript));

			ObjectNode written = (ObjectNode) scope.json().readTree(receipt.toFile());
			requireThat(written.path("format_version").asInt(-1), "formatVersion").isEqualTo(1);
			requireThat(written.path("path_base").asString(), "pathBase").isEqualTo("retained_project_parent");
			requireThat(written.path("retained_project").asString(), "retainedProject").isEqualTo("project");
			requireThat(written.path("raw_result").asString(), "rawResult").
				isEqualTo("artifacts/bridge-transcript.jsonl");
			requireThat(written.path("session_transcripts").get(0).asString(), "sessionTranscript").
				isEqualTo("artifacts/session-transcripts/subagents/native.jsonl");
			requireThat(written.path("requested_sessions").get(0).path("response").asString(), "response").
				isEqualTo("responses/nested/response.json");
			requireThat(written.toString(), "receipt").doesNotContain(root.toString());
		}
	}

	/**
	 * Verifies receipt paths remain valid when an external output and its complete nested session are relocated.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void externalReceiptUsesProjectBase() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "receipt-relocation-"));
			NestedSession nestedSession = new NestedSessionManager(fileLocks, root.resolve("runs"),
				Clock.systemUTC(), Yielding.fileReplacer()).create("harness");
			Path external = Files.writeString(root.resolve("external-output.jsonl"), "external response bytes");
			Path receipt = root.resolve("receipt.json");

			NestedSessionReceipt.write(scope.json(), Yielding.fileReplacer(), receipt, nestedSession, external, List.of());
			Path relocated = root.resolve("retained/run-copy");
			requireThat(ResultsCopy.copy(nestedSession.directory(), relocated).incomplete(), "relocatedRun").isEmpty();
			Files.delete(external);

			HarnessEvaluationResult result = NestedSessionReceipt.read(scope.json(), receipt,
				relocated.resolve("project"));

			requireThat(result.retainedProject(), "retainedProject").isEqualTo(relocated.resolve("project"));
			requireThat(result.rawResult(), "rawResult").
				isEqualTo(relocated.resolve("artifacts/bridge-transcript.jsonl"));
			requireThat(Files.readString(result.rawResult()), "rawResponse").isEqualTo("external response bytes");
		}
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
			Path root = cleanup.add(Files.createTempDirectory("nested-session-receipt-"));
			NestedSession nestedSession = new NestedSessionManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer()).create("harness");
			Path response = Files.writeString(nestedSession.directory().resolve("artifacts/response.txt"),
				"completed response");
			Path receipt = root.resolve("receipt.json");

			NestedSessionReceipt.write(scope.json(), Yielding.fileReplacer(), receipt, nestedSession, response, List.of());
			HarnessEvaluationResult result = NestedSessionReceipt.read(scope.json(), receipt,
				nestedSession.directory().resolve("project"));

			requireThat(result.retainedProject(), "retainedProject").isEqualTo(
				nestedSession.directory().resolve("project").toAbsolutePath().normalize());
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
			Path root = cleanup.add(Files.createTempDirectory("nested-session-receipt-"));
			NestedSession nestedSession = new NestedSessionManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer()).create("harness");
			Path response = Files.writeString(nestedSession.directory().resolve("artifacts/response.txt"),
				"completed response");
			Path transcript = Files.createDirectories(nestedSession.sessionTranscripts()).resolve("session-1.jsonl");
			Files.writeString(transcript, "{}\n");
			Path withCopies = root.resolve("with-copies.json");
			Path without = root.resolve("without.json");

			NestedSessionReceipt.write(scope.json(), Yielding.fileReplacer(), withCopies, nestedSession, response,
				List.of(transcript));
			NestedSessionReceipt.write(scope.json(), Yielding.fileReplacer(), without, nestedSession, response, List.of());

			requireThat(NestedSessionReceipt.read(scope.json(), withCopies, nestedSession.directory().resolve("project")).
				sessionTranscripts(), "sessionTranscripts").
				isEqualTo(List.of(transcript.toAbsolutePath().normalize()));
			requireThat(NestedSessionReceipt.read(scope.json(), without, nestedSession.directory().resolve("project")).
				sessionTranscripts(), "sessionTranscripts").
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
			NestedSession nestedSession = new NestedSessionManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer()).create("harness");
			Path response = Files.writeString(nestedSession.directory().resolve("artifacts/response.txt"),
				"completed response");
			Path child = Files.createDirectories(nestedSession.directory().resolve("responses/first")).resolve(
				"response.json");
			ObjectNode result = scope.json().createObjectNode().put("id", "first").put("harness", "harness").
				put("status", "completed").put("session_id", "child-session");
			scope.json().writeValue(child.toFile(), result);
			Path receipt = root.resolve("receipt.json");

			NestedSessionReceipt.write(scope.json(), Yielding.fileReplacer(), receipt, nestedSession, response, List.of());

			ObjectNode written = (ObjectNode) scope.json().readTree(receipt.toFile());
			requireThat(written.path("requested_sessions").size(), "requestedSessions").isEqualTo(1);
			requireThat(written.path("requested_sessions").get(0).path("response").asString(), "response").
				isEqualTo("responses/first/response.json");
			requireThat(NestedSessionReceipt.read(scope.json(), receipt,
				nestedSession.directory().resolve("project")).rawResult(), "rawResult").isEqualTo(response);
		}
	}

	/**
	 * Verifies a nested session whose responses folder also holds the limits file still gets a receipt that lists only
	 * its requested sessions.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void ignoresLimitsFileAmongResponses() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "receipt-"));
			NestedSession nestedSession = new NestedSessionManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer()).create("harness");
			Path response = Files.writeString(nestedSession.directory().resolve("artifacts/response.txt"),
				"completed response");
			Path responses = Files.createDirectories(nestedSession.directory().resolve("responses"));
			Files.writeString(responses.resolve("limits.json"), "{}");
			Path child = Files.createDirectories(responses.resolve("first")).resolve("response.json");
			ObjectNode result = scope.json().createObjectNode().put("id", "first").put("harness", "harness").
				put("status", "completed").put("session_id", "child-session");
			scope.json().writeValue(child.toFile(), result);
			Path receipt = root.resolve("receipt.json");

			NestedSessionReceipt.write(scope.json(), Yielding.fileReplacer(), receipt, nestedSession, response, List.of());

			ObjectNode written = (ObjectNode) scope.json().readTree(receipt.toFile());
			requireThat(written.path("requested_sessions").size(), "requestedSessions").isEqualTo(1);
		}
	}

	/**
	 * Verifies a nested session whose session asked for a worktree still gets a receipt that lists only its requested
	 * sessions. The response to a worktree request names no harness and no child session, because no session served it,
	 * so the receipt must not take it for a requested session. A nested session that failed this way ended its runner
	 * with status 1 after its last turn had completed.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void ignoresWorktreeResponseAmongResponses() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "receipt-"));
			NestedSession nestedSession = new NestedSessionManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer()).create("harness");
			Path response = Files.writeString(nestedSession.directory().resolve("artifacts/response.txt"),
				"completed response");
			Path responses = Files.createDirectories(nestedSession.directory().resolve("responses"));
			Path worktree = Files.createDirectories(responses.resolve("worktree-62099d29-79f4-49d7-be67-142b73709c53"));
			ObjectNode done = scope.json().createObjectNode().put("format_version", 1).
				put("id", "worktree-62099d29-79f4-49d7-be67-142b73709c53").put("status", "done").put("reason", "");
			scope.json().writeValue(worktree.resolve("response.json").toFile(), done);
			Path child = Files.createDirectories(responses.resolve("first")).resolve("response.json");
			ObjectNode result = scope.json().createObjectNode().put("id", "first").put("harness", "harness").
				put("status", "completed").put("session_id", "child-session");
			scope.json().writeValue(child.toFile(), result);
			Path receipt = root.resolve("receipt.json");

			NestedSessionReceipt.write(scope.json(), Yielding.fileReplacer(), receipt, nestedSession, response, List.of());

			ObjectNode written = (ObjectNode) scope.json().readTree(receipt.toFile());
			requireThat(written.path("requested_sessions").size(), "requestedSessions").isEqualTo(1);
			requireThat(written.path("requested_sessions").get(0).path("id").asString(), "id").isEqualTo("first");
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
			Path root = cleanup.add(Files.createTempDirectory("nested-session-receipt-"));
			Path project = Files.createDirectory(root.resolve("project"));
			Path response = Files.writeString(root.resolve("response.txt"), "completed response");
			ObjectNode receipt = scope.json().createObjectNode();
			receipt.put("format_version", 1);
			receipt.put("path_base", "retained_project_parent");
			receipt.put("retained_project", "project");
			receipt.put("raw_result", "response.txt");

			HarnessEvaluationResult result = NestedSessionReceipt.fromReceipt(receipt, root.resolve("receipt.json"), project);

			requireThat(result.retainedProject(), "retainedProject").isEqualTo(project.toAbsolutePath().normalize());
			requireThat(result.rawResult(), "rawResult").isEqualTo(response.toAbsolutePath().normalize());
		}
	}

	/**
	 * Verifies that a receipt that lists the paths which the copy of a results copy skipped is still read, and that the
	 * list does not change the evidence that an evaluator receives.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void readsReceiptThatListsSkippedCopyPaths() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("nested-session-receipt-"));
			Path project = Files.createDirectory(root.resolve("project"));
			Path response = Files.writeString(root.resolve("response.txt"), "completed response");
			ObjectNode fields = scope.json().createObjectNode();
			fields.put("format_version", 1);
			fields.put("path_base", "retained_project_parent");
			fields.put("retained_project", "project");
			fields.put("raw_result", "response.txt");
			fields.putArray(NestedSessionReceipt.SKIPPED_COPY_PATHS).add("project/locked/a.txt");
			Path receipt = Files.writeString(root.resolve("receipt.json"), scope.json().writeValueAsString(fields));

			HarnessEvaluationResult result = NestedSessionReceipt.read(scope.json(), receipt, project);

			requireThat(result.retainedProject(), "retainedProject").isEqualTo(project.toAbsolutePath().normalize());
			requireThat(result.rawResult(), "rawResult").isEqualTo(response.toAbsolutePath().normalize());
		}
	}

	/**
	 * Verifies the reader rejects rooted, traversing and malformed portable receipt paths and unsupported schemas.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsNonPortableReceiptPaths() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("nested-session-receipt-"));
			Path project = Files.createDirectory(root.resolve("project"));
			Files.writeString(root.resolve("response.txt"), "completed response");
			for (String rawResult : List.of("/tmp/response.txt", "C:/runs/response.txt", "../response.txt",
				"artifacts\\response.txt", "artifacts//response.txt", "artifacts/./response.txt"))
			{
				ObjectNode receipt = portableReceipt(scope.json(), rawResult);
				boolean rejected = false;
				try
				{
					NestedSessionReceipt.fromReceipt(receipt, root.resolve("receipt.json"), project);
				}
				catch (IOException _)
				{
					rejected = true;
				}
				requireThat(rejected, "rejectedReceiptPath").isTrue();
			}
			for (int version : List.of(0, 2))
			{
				ObjectNode receipt = portableReceipt(scope.json(), "response.txt").put("format_version", version);
				boolean rejected = false;
				try
				{
					NestedSessionReceipt.fromReceipt(receipt, root.resolve("receipt.json"), project);
				}
				catch (IOException _)
				{
					rejected = true;
				}
				requireThat(rejected, "rejectedReceiptVersion").isTrue();
			}
			ObjectNode wrongBase = portableReceipt(scope.json(), "response.txt").put("path_base", "run");
			boolean rejected = false;
			try
			{
				NestedSessionReceipt.fromReceipt(wrongBase, root.resolve("receipt.json"), project);
			}
			catch (IOException _)
			{
				rejected = true;
			}
			requireThat(rejected, "rejectedReceiptBase").isTrue();
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
			Path root = cleanup.add(Files.createTempDirectory("nested-session-receipt-"));
			Path project = Files.createDirectory(root.resolve("project"));
			Path response = Files.writeString(root.resolve("response.txt"), "completed response");
			ObjectNode fields = scope.json().createObjectNode();
			fields.put("retained_project", project.toString());
			fields.put("raw_result", response.toString());
			fields.put("unexpected", true);
			Path receipt = Files.writeString(root.resolve("receipt.json"), scope.json().writeValueAsString(fields));

			NestedSessionReceipt.read(scope.json(), receipt, project);
		}
	}

	/**
	 * Creates the required current receipt fields for one portable response path.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param rawResult the portable response path
	 * @return the receipt fields
	 */
	private static ObjectNode portableReceipt(JsonMapper json, String rawResult)
	{
		return json.createObjectNode().put("format_version", 1).put("path_base", "retained_project_parent").
			put("retained_project", "project").put("raw_result", rawResult);
	}
}
