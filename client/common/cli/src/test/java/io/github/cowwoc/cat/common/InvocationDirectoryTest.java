package io.github.cowwoc.cat.common;

import io.github.cowwoc.cat.common.evaluation.WorkDirectories;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests {@link InvocationDirectory}.
 */
public final class InvocationDirectoryTest
{
	/**
	 * Verifies the command exposes the CAT work root used by its other operations.
	 */
	@Test
	public void returnsWorkRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-workflow-temp-"));

			requireThat(InvocationDirectory.root(root), "workRoot").isEqualTo(root.toAbsolutePath().normalize());
		}
	}

	/**
	 * Creates the test fixture.
	 */
	public InvocationDirectoryTest()
	{
	}

	/**
	 * Moves completed invocation data from CAT's temporary work root to a retained location beneath CAT work storage.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void handsOffOwnedDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("cat-workflow-parent-"));
			Path work = Files.createDirectory(parent.resolve("work"));
			Path temporary = Files.createDirectories(work.resolve("temp"));
			Path directory = InvocationDirectory.create(temporary, "prompt-evaluation");
			Files.writeString(directory.resolve("result.txt"), "evidence");
			Path durable = work.resolve("retained/prompt-evaluation");

			Path result = InvocationDirectory.handoff(work, directory, durable);

			requireThat(result, "destination").isEqualTo(durable);
			requireThat(Files.exists(directory), "temporaryDirectory").isFalse();
			requireThat(Files.readString(durable.resolve("result.txt")), "retainedEvidence").isEqualTo("evidence");
		}
	}

	/**
	 * Rejects an uncommitted evidence handoff outside CAT work storage.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*remain beneath the CAT work root.*")
	public void rejectsHandoffOutsideWorkRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("cat-workflow-parent-"));
			Path work = Files.createDirectory(parent.resolve("work"));
			Path directory = InvocationDirectory.create(Files.createDirectories(work.resolve("temp")), "prompt-evaluation");

			InvocationDirectory.handoff(work, directory, parent.resolve("durable-workflow-evidence"));
		}
	}

	/**
	 * Rejects a retained destination beneath the CAT work temp directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*cannot remain beneath the CAT work temp directory.*")
	public void rejectsHandoffInsideTemporaryRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("cat-workflow-parent-"));
			Path work = Files.createDirectory(parent.resolve("work"));
			Path temporary = Files.createDirectories(work.resolve("temp"));
			Path directory = InvocationDirectory.create(temporary, "prompt-evaluation");

			InvocationDirectory.handoff(work, directory, temporary.resolve("retained-workflow-evidence"));
		}
	}

	/**
	 * Rejects a handoff while an evaluator has retained a live-work marker.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*terminal decision.*")
	public void rejectsHandoffOfLivePromptEvaluation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("cat-workflow-parent-"));
			Path work = Files.createDirectory(parent.resolve("work"));
			Path directory = InvocationDirectory.create(Files.createDirectories(work.resolve("temp")), "prompt-evaluation");
			Files.writeString(directory.resolve(".cat-prompt-evaluation-active"), "running\n");

			InvocationDirectory.handoff(work, directory, parent.resolve("durable-workflow-evidence"));
		}
	}

	/**
	 * Deletes completed invocation scratch state rather than leaving it below the CAT work temporary root.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void deletesOwnedDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-workflow-temp-"));
			Path directory = InvocationDirectory.create(root, "prompt-optimizer");
			Files.writeString(directory.resolve("scratch.txt"), "temporary");

			InvocationDirectory.delete(root, directory);

			requireThat(Files.exists(directory), "temporaryDirectory").isFalse();
		}
	}

	/**
	 * Reports an owned terminal invocation as removable without treating an unrecorded durable file as stale.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void inventoriesOwnershipBeforeCleanup() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("cat-workflow-parent-"));
			Path work = Files.createDirectory(parent.resolve("work"));
			Path invocation = InvocationDirectory.create(Files.createDirectories(work.resolve("temp")),
				"prompt-optimizer");
			Files.writeString(invocation.resolve("scratch.txt"), "temporary");
			Files.writeString(work.resolve("unrecorded.txt"), "retain until its owner is known");
			Path nestedMarker = Files.createDirectories(work.resolve("durable/nested")).resolve(".cat-workflow-temp");
			Files.writeString(nestedMarker, "another-workflow\n");
			Path retainedEvaluation = Files.createDirectory(work.resolve("retained-evaluation"));
			Path retainedRun = retainedEvaluation.resolve("run.json");
			Files.writeString(retainedRun, """
				{"workspace_finalized":true,"decision":{"overall_decision":"PASS"}}
				""");
			Files.writeString(retainedEvaluation.resolve(".cat-prompt-evaluation-record"), retainedRun + "\n");
			Path retainedReview = Files.createDirectories(retainedEvaluation.resolve("review"));
			Path reviewRun = retainedReview.resolve("run.json");
			Files.writeString(reviewRun, """
				{"workspace_finalized":true,"decision":{"overall_decision":"FAIL"}}
				""");
			Files.writeString(retainedReview.resolve(".cat-prompt-evaluation-record"), reviewRun + "\n");
			Files.writeString(work.resolve("candidate-state.json"), """
				{"checks":[{"run":"%s"},{"run":"%s"}]}
				""".formatted(retainedRun, reviewRun));
			Files.writeString(work.resolve(".cat-work-ownership.json"), """
				{"schema_version":1,"entries":{"unrecorded.txt":{"creator":"documentation-audit",
				"declared_consumer":"active documentation review"}}}
				""");
			for (String store : List.of(WorkDirectories.RUNNER_ARTIFACTS, WorkDirectories.INPUT_ARTIFACTS,
				WorkDirectories.EVIDENCE_ARTIFACTS, WorkDirectories.SUITE_ARTIFACTS))
			{
				Files.createDirectory(work.resolve(store));
			}
			Files.createDirectory(work.resolve("prompt-evaluation-preflight"));

			try (ToolScope scope = new ToolScope(parent))
			{
				ArrayNode entries = WorkflowWorkInventory.inventory(work, scope.json());
				requireThat(entries.size(), "entries.size()").isEqualTo(10);
				JsonNode durable = entries.valueStream().filter(entry -> entry.path("path").asString().equals(
					work.resolve("unrecorded.txt").toString())).findFirst().orElseThrow();
				JsonNode temporary = entries.valueStream().filter(entry -> entry.path("path").asString().equals(
					invocation.toString())).findFirst().orElseThrow();
				JsonNode durableDirectory = entries.valueStream().filter(entry -> entry.path("path").asString().equals(
					work.resolve("durable").toString())).findFirst().orElseThrow();
				JsonNode retained = entries.valueStream().filter(entry -> entry.path("path").asString().equals(
					retainedEvaluation.toString())).findFirst().orElseThrow();
				requireThat(durable.path("safe_to_remove").asBoolean(), "durable.safeToRemove").isFalse();
				requireThat(durable.path("creator").asString(), "durable.creator").isEqualTo("documentation-audit");
				requireThat(durable.path("declared_consumer").asString(), "durable.consumer").
					isEqualTo("active documentation review");
				requireThat(durableDirectory.path("safe_to_remove").asBoolean(), "nestedOwner.safeToRemove").isFalse();
				requireThat(retained.path("terminal_state").asString(), "retained.terminalState").isEqualTo("terminal");
				requireThat(retained.path("retained_references").asInt(), "retained.references").isEqualTo(2);
				requireThat(retained.path("evaluations").size(), "retained.evaluations.size()").isEqualTo(2);
				requireThat(retained.path("evaluations").get(0).path("run").asString(), "retained.firstRun").isEqualTo(
				retainedRun.toString());
				requireThat(retained.path("evaluations").get(1).path("run").asString(), "retained.secondRun").isEqualTo(
				reviewRun.toString());
				requireThat(temporary.path("creator").asString(), "invocation.creator").isEqualTo("prompt-optimizer");
				requireThat(temporary.path("safe_to_remove").asBoolean(), "invocation.safeToRemove").isTrue();
				for (String store : List.of(WorkDirectories.RUNNER_ARTIFACTS, WorkDirectories.INPUT_ARTIFACTS,
					WorkDirectories.EVIDENCE_ARTIFACTS, WorkDirectories.SUITE_ARTIFACTS))
				{
					JsonNode sharedStore = entries.valueStream().filter(entry -> entry.path("path").asString().equals(
						work.resolve(store).toString())).findFirst().orElseThrow();
					requireThat(sharedStore.path("terminal_state").asString(), "sharedStore.terminalState").
						isEqualTo("durable shared store");
					requireThat(sharedStore.path("safe_to_remove").asBoolean(), "sharedStore.safeToRemove").isFalse();
				}
				JsonNode preflight = entries.valueStream().filter(entry -> entry.path("path").asString().equals(
					work.resolve("prompt-evaluation-preflight").toString())).findFirst().orElseThrow();
				requireThat(preflight.path("creator").asString(), "preflight.creator").isEqualTo("prompt-evaluation");
				requireThat(preflight.path("declared_consumer").asString(), "preflight.consumer").
					isEqualTo("reusable preflight cache");
				requireThat(preflight.path("terminal_state").asString(), "preflight.terminalState").
					isEqualTo("durable shared store");
			}
		}
	}

	/**
	 * Records why a direct durable work file remains after the workflow that created it exits.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void retainsDirectDurableEntry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("cat-workflow-parent-"));
			Path work = Files.createDirectory(parent.resolve("work"));
			Path retained = Files.writeString(work.resolve("review.md"), "review evidence");

			try (ToolScope scope = new ToolScope(parent))
			{
				Path resolved = WorkflowWorkOwnership.retain(work, Path.of("review.md"), "documentation-audit",
					"active reader review", scope.json());

				WorkflowWorkOwnership.Entry ownership = WorkflowWorkOwnership.read(work, scope.json()).get(retained);
				requireThat(resolved, "resolved").isEqualTo(retained);
				requireThat(ownership.creator(), "ownership.creator").isEqualTo("documentation-audit");
				requireThat(ownership.consumer(), "ownership.consumer").isEqualTo("active reader review");
			}
		}
	}

	/**
	 * Rejects an ownership declaration that would escape the direct CAT work boundary.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsIndirectOwnership() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-workflow-parent-"));
			Path work = Files.createDirectory(root.resolve("work"));

			WorkflowWorkOwnership.retain(work, root, "documentation-audit", "active reader review", scope.json());
		}
	}

	/**
	 * Rejects deletion of a direct CAT work temporary child that this launcher did not create.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsUnownedDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-workflow-temp-"));
			Path unowned = Files.createDirectory(root.resolve("runner-runs"));

			InvocationDirectory.delete(root, unowned);
		}
	}
}
