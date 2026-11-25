package com.holdmyspot.cat.claude.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.optimizer.ClaudeOptimizerCommandScope;
import com.holdmyspot.cat.claude.secrets.ClaudeOptimizerCommandScopeSecrets;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.fakes.test.RequestingNestedSession;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests that an optimizer that a nested Claude Code session's command runs learns the policy that bounds its check
 * programs from the session's runner, because the Claude Code process is not among the processes that the command's
 * sandbox shows it.
 */
public final class ClaudeCheckProgramOwnerTest
{
	private static final String SESSION_ID = "2c4e6a8b-1d3f-4a5c-9e7b-0f2d4c6e8a1b";
	/**
	 * A process identifier that no ancestor of the optimizer has, as in the sandbox of a command, where the Claude Code
	 * process that {@code CLAUDE_PID} names runs outside the command's process namespace.
	 */
	private static final String HIDDEN_HARNESS_PROCESS = "2000000000";

	/**
	 * Creates the test.
	 */
	public ClaudeCheckProgramOwnerTest()
	{
	}

	/**
	 * Verifies that the check programs of a campaign that a nested session owns are bounded by the policy that the
	 * nested session's runner applied to the session's sandbox.
	 *
	 * @throws IOException   if a file cannot be written or the policy cannot be read
	 * @throws PolicyRefusal if the policy cannot be read safely
	 */
	@Test(timeOut = 30_000)
	public void nestedOwnerPolicyComesFromRunner() throws IOException, PolicyRefusal
	{
		// Requirement: R-L21
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path root = scope.add(Files.createTempDirectory("claude-owner-project")).toRealPath();
			Files.createDirectory(root.resolve(".git"));
			Path home = scope.add(Files.createTempDirectory("claude-owner-home")).toRealPath();
			Path results = scope.add(Files.createTempDirectory("claude-owner-results")).toRealPath();
			ParentPolicy owner = new ChildPolicy(List.of(results.resolve("session")), List.of(results.resolve("kept")),
				List.of(results.resolve("secret")), List.of(), Set.of("api.example.com"), false).asParentPolicy();
			try (RequestingNestedSession parent = new RequestingNestedSession(results.resolve("parent"), new FileLocks(),
				1, owner))
			{
				Map<String, String> environment = new HashMap<>(Map.of("CLAUDE_CODE_SESSION_ID", SESSION_ID,
					"CLAUDECODE", "1", "CAT_NESTED_SESSION", "1", "CLAUDE_PID", HIDDEN_HARNESS_PROCESS));
				environment.put(SessionRequestAccess.REQUESTS_VARIABLE, parent.requests().toString());
				environment.put(Platform.current().homeVariable(), home.toString());
				try (ClaudeOptimizerCommandScope optimizer = ClaudeOptimizerCommandScopeSecrets.
					getClaudeOptimizerCommandScopeAccess().newClaudeOptimizerCommandScope(environment, home, root,
						home.resolve("artifact"), Yielding.waiting()))
				{
					List<ParentPolicy> policies = optimizer.checkProgramPolicies();

					requireThat(policies, "policies").isEqualTo(List.of(owner));
				}
			}
		}
	}
}
