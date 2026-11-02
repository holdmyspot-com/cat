package com.holdmyspot.cat.codex.optimizer.test;

import static com.holdmyspot.cat.common.optimizer.adapter.fakes.test.SessionRequestFixtures.partOf;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.runner.CodexAgentDefinition;
import com.holdmyspot.cat.codex.secrets.CodexSessionsSecrets;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.adapter.fakes.test.PackagedRuleArtifact;
import com.holdmyspot.cat.common.optimizer.contract.TestWorkspace;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests the rules that Codex delivers in the first brief of each role's session, rendered from the packaged rules.
 */
public final class CodexRoleRulesTest
{
	private static final Path RULES = Path.of("../../common/rules/src/main/resources/rules");
	private static final Path AGENTS = Path.of("../plugin/src/main/resources/agents/codex");

	/**
	 * Creates the test.
	 */
	public CodexRoleRulesTest()
	{
	}

	/**
	 * Returns every session role.
	 *
	 * @return the roles
	 */
	@DataProvider
	public Object[][] roles()
	{
		return new Object[][]
			{
				{SessionRole.PROMPT_AUTHOR}, {SessionRole.CONTRACT_AUTHOR}, {SessionRole.JUDGE}, {SessionRole.TRIAGE},
				{SessionRole.APPROACH_RANKER}, {SessionRole.TRIAL}
			};
	}

	/**
	 * Verifies that the first brief of every role's session starts and carries no unrendered rule variable when the
	 * rules come from the packaged rule tree.
	 *
	 * @param role the session's role
	 * @throws IOException if a fixture cannot be written
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session fails
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(dataProvider = "roles")
	public void firstBriefRendersPackagedRules(SessionRole role)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-role-rules-"));
			Path artifact = PackagedRuleArtifact.write(root.resolve("artifact"), RULES, AGENTS, "codex");

			String brief = deliver(jvm, root, artifact, role);

			requireThat(brief, "brief").doesNotContain("${CAT_RULE_");
		}
	}

	/**
	 * Verifies that the prompt author's rules name the strongest implementer that the installation's agent defines.
	 *
	 * @throws IOException if a fixture cannot be written
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session fails
	 * @throws TimeoutException if the session does not finish
	 */
	@Test
	public void promptAuthorNamesImplementer()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-role-rules-"));
			Path artifact = PackagedRuleArtifact.write(root.resolve("artifact"), RULES, AGENTS, "codex");
			CodexAgentDefinition agent = CodexAgentDefinition.load(artifact, "optimize-xhigh");

			String brief = deliver(jvm, root, artifact, SessionRole.PROMPT_AUTHOR);

			requireThat(brief, "brief").contains("`" + agent.model() + " at " + agent.effort() + " reasoning`");
		}
	}

	/**
	 * Sends a session's first brief through an in-memory bridge.
	 *
	 * @param jvm the borrowed process scope
	 * @param root the test-owned run root
	 * @param artifact the installed artifact
	 * @param role the session's role
	 * @return the first brief that the bridge received
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session fails
	 * @throws TimeoutException if the session does not finish
	 */
	private static String deliver(TestJvmScope jvm, Path root, Path artifact, SessionRole role)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		Path source = Files.createDirectories(root.resolve("source"));
		List<String> prompts = new CopyOnWriteArrayList<>();
		RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, artifact, root.resolve("runs"), List::of, "",
			false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
		RunnerSessions sessions = CodexSessionsSecrets.getCodexSessionsAccess().newCodexSessions(settings,
			(options, input, output) -> ScriptedHarness.serve(
				Path.of(ScriptedHarness.optionValue(options, "--run-root")), "codex", List.of(Turn.replies("ok")),
				input, output, prompts));

		SessionResult result = sessions.start(new SessionRequest(role, partOf(role), source, List.of("brief"),
			List.of(), Duration.ofMinutes(5), new TrialProfile("model-a", "low"), TestWorkspace.NONE, List.of())).
			completion().toCompletableFuture().get(60, TimeUnit.SECONDS);

		requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
		return prompts.getFirst();
	}
}
