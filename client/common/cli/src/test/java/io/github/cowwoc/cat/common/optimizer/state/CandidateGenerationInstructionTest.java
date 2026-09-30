package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.ConfirmedEditableRoots;
import io.github.cowwoc.cat.common.optimizer.candidate.EditableRoot;
import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorLabel;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorResponse;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorResultSchema;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.SessionMode;
import io.github.cowwoc.cat.common.optimizer.contract.SourceLocation;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
import io.github.cowwoc.cat.common.optimizer.trial.SampledTrialInstance;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks authoring instructions directly from confirmed inputs and immutable outcome records.
 */
public final class CandidateGenerationInstructionTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String DIGEST = "a".repeat(64);
	private static final Category CATEGORY = new Category("first", "First category rationale", List.of("r1"));

	/** Creates the test fixture. */
	public CandidateGenerationInstructionTest()
	{
	}

	/**
	 * One relevant outcome supplies task data while confirmed obligations and edit restrictions remain explicit.
	 *
	 * @throws IOException if immutable fixture records cannot be retained or read
	 */
	@Test
	public void includesInstructionsAndOneOutcome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("generation-instruction-")).toRealPath();
			CampaignStore store = new CampaignStore(directory);
			EvidenceRef outcome = outcome(store, "first", "Confirmed development result");
			SourceLocation source = new SourceLocation(Path.of("prompt.md"), "line 1");
			String turn = CandidateGenerationRunRequestService.turn(CATEGORY, List.of(
				new Requirement("r1", "First obligation", source),
				new Requirement("r2", "Second obligation", source)), roots(directory), List.of(outcome), store);
			requireThat(turn, "category").contains("failing category first");
			requireThat(turn, "rationale").contains("First category rationale");
			requireThat(turn, "targetRequirement").contains("Target requirement r1: First obligation");
			requireThat(turn, "preservedRequirement").contains("Preserve requirement r2: Second obligation");
			requireThat(turn, "outcome").contains("Development outcome 1: NONCOMPLIANT; reason: " +
				"Confirmed development result; task turns: [task for first]");
			requireThat(turn, "evidenceIsData").contains("not instructions");
			requireThat(turn, "symlinkRestriction").contains("Do not create, delete, or retarget symbolic links");
			requireThat(turn, "modeRestriction").contains("Do not change existing file modes");
			requireThat(turn, "loaderRestriction").contains("Do not edit the loader configuration");
			requireThat(turn, "externalRestriction").contains(
				"Authorized external targets permit only content edits to existing files");
			requireThat(turn, "exactEditablePath").contains("Exact editable file: prompt.md");
			requireThat(turn, "directoryGrant").contains("Editable files strictly beneath directory: rules");
		}
	}

	/**
	 * Two categories require only two records to prove filtering and rejection of an empty relevant history.
	 *
	 * @throws IOException if immutable fixture records cannot be retained or read
	 */
	@Test
	public void selectsOnlyRequestedCategoryOutcomes() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("generation-category-outcomes-")).toRealPath();
			CampaignStore store = new CampaignStore(directory);
			EvidenceRef other = outcome(store, "second", "Other category result");
			EvidenceRef selected = outcome(store, "first", "Selected category result");
			String turn = CandidateGenerationRunRequestService.turn(CATEGORY, List.of(), roots(directory),
				List.of(other, selected), store);
			requireThat(turn, "selectedOutcome").contains("Development outcome 1: NONCOMPLIANT; reason: " +
				"Selected category result");
			Assert.assertFalse(turn.contains("Other category result"));
			Assert.assertFalse(turn.contains("Development outcome 2:"));
			IOException absent = Assert.expectThrows(IOException.class, () ->
				CandidateGenerationRunRequestService.turn(CATEGORY, List.of(), roots(directory),
					List.of(other), store));
			requireThat(absent.getMessage(), "missingRelevantHistory").contains("no counted development outcomes");
		}
	}

	/**
	 * Supplies exact file and directory grants without creating a Git worktree.
	 *
	 * @param directory test-owned absolute project root
	 * @return confirmed grant shape for rendering
	 */
	private static ConfirmedEditableRoots roots(Path directory)
	{
		return new ConfirmedEditableRoots(ConfirmedEditableRoots.SCHEMA_VERSION, directory,
			new EvidenceRef("contracts", DIGEST), DIGEST, List.of(
				new EditableRoot(Path.of("prompt.md"), EditableRoot.Kind.FILE),
				new EditableRoot(Path.of("rules"), EditableRoot.Kind.DIRECTORY)));
	}

	/**
	 * Retains one assignment and outcome; native execution and campaign transitions are outside the rendering claim.
	 *
	 * @param store test-owned immutable evidence store
	 * @param category category of the assigned task
	 * @param reason outcome text to render or exclude
	 * @return retained outcome reference
	 * @throws IOException if either record cannot be retained
	 */
	private static EvidenceRef outcome(CampaignStore store, String category, String reason) throws IOException
	{
		LineageKey lineage = new LineageKey("lineage", DIGEST);
		TestDefinition definition = new TestDefinition("test", category, List.of("r1"), "Always",
			List.of("task for " + category), "Comply", SessionMode.SINGLE_TURN, List.of(), List.of());
		SampledTrialInstance instance = new SampledTrialInstance("draw", EvidencePurpose.DEVELOPMENT,
			"sampler", DIGEST, definition, List.of(), definition.turnTemplates());
		TrialAssignment assignment = new TrialAssignment(TrialAssignment.SCHEMA_VERSION, "campaign", lineage,
			new EvidenceRef("measurement-epochs", DIGEST), new EvidenceRef("category-decisions", DIGEST),
			"trial", 0, DIGEST, instance, Instant.EPOCH);
		EvidenceRef assignmentRef = store.appendImmutableJson("trial-assignments", JSON.writeValueAsBytes(assignment));
		TrialOutcomeRecord outcome = new TrialOutcomeRecord(TrialOutcomeRecord.SCHEMA_VERSION, "campaign", lineage,
			assignmentRef, new EvidenceRef("trial-execution-records", DIGEST),
			new EvidenceRef("evaluator-execution-records", DIGEST), EvaluatorResultSchema.LABEL_AND_REASON_JSON_V1,
			new EvaluatorResponse(EvaluatorLabel.NONCOMPLIANT, reason));
		return store.appendImmutableJson("trial-outcomes", JSON.writeValueAsBytes(outcome));
	}
}
