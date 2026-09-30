package io.github.cowwoc.cat.common.optimizer.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.HostToolRequirement;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.api.SessionMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Tests request cardinality and workspace isolation.
 */
public final class NestedRunRequestTest
{
	/**
	 * Creates a test instance.
	 */
	public NestedRunRequestTest()
	{
	}

	/**
	 * Verifies an active worktree cannot be used for formal calls.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsFormalActiveWorktree()
	{
		new NestedRunRequest("call", EvidencePurpose.FORMAL, active(), List.of("prompt"), SessionMode.SINGLE_TURN,
			profile(), List.of());
	}

	/**
	 * A private candidate generation worktree may author only development evidence.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "private generation requires DEVELOPMENT")
	public void rejectsFormalPrivateGeneration()
	{
		new NestedRunRequest("call", EvidencePurpose.FORMAL, privateGeneration(), List.of("prompt"),
			SessionMode.SINGLE_TURN, profile(), List.of());
	}

	/**
	 * Development authoring retains the private root and its request authority.
	 */
	@Test
	public void acceptsDevelopmentPrivateGeneration()
	{
		PrivateGenerationContext context = privateGeneration();
		NestedRunRequest request = new NestedRunRequest("call", EvidencePurpose.DEVELOPMENT, context,
			List.of("prompt"), SessionMode.SINGLE_TURN, profile(), List.of());
		requireThat(request.context(), "context").isEqualTo(context);
	}

	/**
	 * Candidate authoring cannot treat the selected project as its private worktree.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "privateProject must differ from selectedProject")
	public void rejectsSelectedProjectAsPrivate()
	{
		Path selected = Path.of("selected").toAbsolutePath().normalize();
		new PrivateGenerationContext(selected, selected, ref("candidate-generation-requests"),
			ref("generation-attempt-readiness"), ref("confirmed-editable-roots"));
	}

	/**
	 * The native authoring context must refer to a candidate generation request.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "generationRequestRef must name candidate-generation-requests")
	public void rejectsOtherGenerationRequestCollection()
	{
		new PrivateGenerationContext(Path.of("selected").toAbsolutePath().normalize(),
			Path.of("private").toAbsolutePath().normalize(), ref("trial-requests"),
			ref("generation-attempt-readiness"), ref("confirmed-editable-roots"));
	}

	/**
	 * Verifies a deliberate empty turn is distinct from no turns.
	 */
	@Test
	public void acceptsEmptyTurnText()
	{
		NestedRunRequest request = new NestedRunRequest("call", EvidencePurpose.VALIDATION,
			new SterileWorkspaceContext(SterileWorkspaceKind.CONTRACT_DERIVATION, ref("sterile-manifests")),
			List.of(""), SessionMode.SINGLE_TURN, profile(), List.of());
		requireThat(request.turns().size(), "turnCount").isEqualTo(1);
	}

	/**
	 * Verifies a single-turn mode cannot hide a second turn.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
    public void rejectsSecondSingleTurn()
	{
		new NestedRunRequest("call", EvidencePurpose.VALIDATION,
			new SterileWorkspaceContext(SterileWorkspaceKind.CONTRACT_DERIVATION, ref("sterile-manifests")),
            List.of("one", "two"), SessionMode.SINGLE_TURN, profile(), List.of());
    }

	/**
	 * Verifies a multi-turn project call retains ordered turns in one session.
	 */
	@Test
	public void acceptsMultiTurnProjectCopy()
	{
		NestedRunRequest request = new NestedRunRequest("call", EvidencePurpose.DEVELOPMENT, isolated(),
			List.of("first", "second"), SessionMode.MULTI_TURN, profile(), List.of());
		requireThat(request.turns(), "turns").isEqualTo(List.of("first", "second"));
	}

	/**
	 * Verifies a multi-turn session requires at least two turns.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsOneTurnMultiSession()
	{
		new NestedRunRequest("call", EvidencePurpose.DEVELOPMENT, isolated(), List.of("only"),
			SessionMode.MULTI_TURN, profile(), List.of());
	}

	/**
	 * Verifies a sterile call cannot inherit host tool availability.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsSterileHostTool()
	{
		new NestedRunRequest("call", EvidencePurpose.VALIDATION,
			new SterileWorkspaceContext(SterileWorkspaceKind.GENERATOR_VALIDATION, ref("manifests")),
			List.of("turn"), SessionMode.SINGLE_TURN, profile(),
			List.of(new HostToolRequirement("git", "git")));
	}

	/**
	 * Verifies an evaluator workspace cannot be used for contract validation.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsValidationEvaluator()
	{
		new NestedRunRequest("call", EvidencePurpose.VALIDATION,
			new SterileWorkspaceContext(SterileWorkspaceKind.EVALUATOR, ref("manifests")),
			List.of("turn"), SessionMode.SINGLE_TURN, profile(), List.of());
	}

	/**
	 * Verifies an evaluator workspace may be used for formal evidence.
	 */
	@Test
	public void acceptsFormalEvaluator()
	{
		NestedRunRequest request = new NestedRunRequest("call", EvidencePurpose.FORMAL,
			new SterileWorkspaceContext(SterileWorkspaceKind.EVALUATOR, ref("manifests")),
			List.of("turn"), SessionMode.SINGLE_TURN, profile(), List.of());
		requireThat(request.purpose(), "purpose").isEqualTo(EvidencePurpose.FORMAL);
	}

	/**
	 * Verifies host-tool logical IDs are unique even when executable names differ.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsDuplicateHostToolId()
	{
		new NestedRunRequest("call", EvidencePurpose.DEVELOPMENT, isolated(), List.of("turn"),
			SessionMode.SINGLE_TURN, profile(), List.of(new HostToolRequirement("tool", "one"),
			new HostToolRequirement("tool", "two")));
	}

	/**
	 * Verifies changes to caller-owned turn and tool lists cannot change a request.
	 */
	@Test
	public void copiesTurnAndToolLists()
	{
		List<String> turns = new ArrayList<>(List.of("turn"));
		List<HostToolRequirement> tools = new ArrayList<>(List.of(new HostToolRequirement("git", "git")));
		NestedRunRequest request = new NestedRunRequest("call", EvidencePurpose.DEVELOPMENT, isolated(),
			turns, SessionMode.SINGLE_TURN, profile(), tools);
		turns.clear();
		tools.clear();
		requireThat(request.turns().size(), "turnCount").isEqualTo(1);
		requireThat(request.requiredHostTools().size(), "toolCount").isEqualTo(1);
	}

	/**
	 * active at the native runner boundary.
	  *
	  * @return the resulting value
	 */
	private static ProjectRunContext active()
	{
		return new ProjectRunContext(new ProjectExecutionSnapshot(Path.of("project").toAbsolutePath().normalize(),
			ref("snapshots")), ProjectWorkspaceMode.ACTIVE_WORKTREE, Optional.of(ref("roots")),
			Optional.of(ref("checkpoints")));
	}

	/**
	 * Creates an authoring context with separate selected and private worktrees.
	 *
	 * @return private generation context
	 */
	private static PrivateGenerationContext privateGeneration()
	{
		return new PrivateGenerationContext(Path.of("selected").toAbsolutePath().normalize(),
			Path.of("private").toAbsolutePath().normalize(), ref("candidate-generation-requests"),
			ref("generation-attempt-readiness"), ref("confirmed-editable-roots"));
	}

	/**
	 * Creates an isolated copy of the selected project.
	 *
	 * @return an isolated project context
	 */
	private static ProjectRunContext isolated()
	{
		return new ProjectRunContext(new ProjectExecutionSnapshot(Path.of("project").toAbsolutePath().normalize(),
			ref("snapshots")), ProjectWorkspaceMode.ISOLATED_COPY, Optional.empty(), Optional.empty());
	}

	/**
	 * profile at the native runner boundary.
	  *
	  * @return the resulting value
	 */
	private static ModelProfile profile()
	{
		return new ModelProfile("model", Optional.empty(), Map.of());
	}

	/**
	 * ref at the native runner boundary.
	  *
	  * @return the resulting value
	  * @param collection the collection value
	 */
	private static EvidenceRef ref(String collection)
	{
		return new EvidenceRef(collection, "a".repeat(64));
	}
}
