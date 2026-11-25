package com.holdmyspot.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.optimizer.CodexOptimizerCommandScope;
import com.holdmyspot.cat.codex.secrets.CodexOptimizerCommandScopeSecrets;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.TrialRecord;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram;
import com.holdmyspot.cat.common.optimizer.execution.contract.test.CheckProgramFixtures;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.fakes.test.RequestingNestedSession;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Tests that the check programs of a campaign that a nested Codex session owns run outside the session's sandbox,
 * where the program's own sandbox can start, under the policy that the session's runner applied to the session.
 */
public final class CodexCheckProgramOwnerTest
{
	private static final String SESSION_ID = "2c4e6a8b-1d3f-4a5c-9e7b-0f2d4c6e8a1b";

	/**
	 * Creates the test.
	 */
	public CodexCheckProgramOwnerTest()
	{
	}

	/**
	 * Verifies that a campaign that a nested session owns runs its check programs under the policy that the nested
	 * session's runner applied to the session, outside the session's sandbox: here the command's own search path leads
	 * only to a Bubblewrap that refuses to start, as the one inside a nested session's sandbox does, and the session's
	 * policy denies reading a folder, which the program cannot read.
	 *
	 * @throws IOException          if a file cannot be written or the programs cannot run
	 * @throws InterruptedException if the run is interrupted
	 */
	@Test(timeOut = 120_000)
	public void nestedOwnerRunsProgramsOutsideSandbox() throws IOException, InterruptedException
	{
		// Requirement: R-L21
		if (!Platform.current().isLinux() || Files.notExists(Path.of("/usr/bin/bwrap")))
			throw new SkipException("The check-program sandbox needs Linux and a system Bubblewrap");
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path root = scope.add(Files.createTempDirectory("codex-owner-project")).toRealPath();
			Files.createDirectory(root.resolve(".git"));
			Path home = scope.add(Files.createTempDirectory("codex-owner-home")).toRealPath();
			Path results = scope.add(Files.createTempDirectory("codex-owner-results")).toRealPath();
			Path secret = Files.createDirectory(results.resolve("secret"));
			Path secretFile = Files.writeString(secret.resolve("token.txt"), "do not read\n");
			Path refusingBin = CheckProgramFixtures.refusingBubblewrap(
				scope.add(Files.createTempDirectory("codex-owner-bin")));
			ParentPolicy owner = new ChildPolicy(List.of(results.resolve("session")), List.of(results.resolve("kept")),
				List.of(secret), List.of(), Set.of("api.example.com"), false).asParentPolicy();
			try (RequestingNestedSession parent = new RequestingNestedSession(results.resolve("parent"), new FileLocks(),
				1, owner))
			{
				Map<String, String> environment = new HashMap<>(Map.of("CODEX_THREAD_ID", SESSION_ID,
					"CAT_NESTED_SESSION", "1", "PATH", refusingBin.toString()));
				environment.put(SessionRequestAccess.REQUESTS_VARIABLE, parent.requests().toString());
				environment.put(Platform.current().homeVariable(), home.toString());
				PromptTest test = CheckProgramFixtures.promptTest(CheckProgramFixtures.DENIED_READ_PROGRAM);
				TrialRecord record = CheckProgramFixtures.deniedReadRecord(
					scope.add(Files.createTempDirectory("codex-owner-trial")), secretFile);
				try (CodexOptimizerCommandScope optimizer = CodexOptimizerCommandScopeSecrets.
					getCodexOptimizerCommandScopeAccess().newCodexOptimizerCommandScope(environment, home, root,
						home.resolve("artifact"), Yielding.waiting()))
				{
					Map<String, List<CheckProgram.Result>> runs = optimizer.checkPrograms().run(test,
						CheckProgramFixtures.testCase(test), record, 1);

					CheckProgram.Result run = runs.get(CheckProgramFixtures.CHECK).getFirst();
					requireThat(run.outcome(), "outcome").withContext(run.observed(), "observed").
						isEqualTo(CheckProgram.Outcome.PASS);
					requireThat(run.observed(), "observed").contains("service after").contains("secret readable: false");
				}
			}
		}
	}
}
