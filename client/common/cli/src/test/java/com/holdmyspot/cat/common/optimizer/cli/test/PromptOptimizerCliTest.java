package com.holdmyspot.cat.common.optimizer.cli.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.cli.PromptOptimizerCli;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneOffset;

/**
 * Tests shared optimizer command scope ownership and failure output.
 */
public final class PromptOptimizerCliTest
{
	/**
	 * Creates the test.
	 */
	public PromptOptimizerCliTest()
	{
	}

	/**
	 * Verifies a command closes its supplied scope and retains the invalid-input exit code.
	 */
	@Test
	public void closesScopeAfterInvalidCommand()
	{
		try (TestOptimizerCommandScope<?> scope = TestOptimizerCommandScope.of(Harness.CLAUDE, Path.of("."));
			PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
		{
			int exit = PromptOptimizerCli.run(new String[]{"frobnicate"}, () -> scope, ZoneOffset.UTC, out);

			requireThat(exit, "exit").isEqualTo(2);
			requireThat(scope.isClosed(), "closed").isTrue();
		}
	}

	/**
	 * Verifies scope-creation failures produce the same failure JSON and exit code as command failures.
	 */
	@Test
	public void rendersScopeCreationFailure()
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
		{
			int exit = PromptOptimizerCli.run(new String[]{"status"}, () ->
			{
				throw new IOException("configuration unavailable");
			}, ZoneOffset.UTC, out);

			requireThat(exit, "exit").isEqualTo(1);
			JsonNode result = JsonMapper.builder().build().readTree(bytes.toString(StandardCharsets.UTF_8));
			requireThat(result.get("status").asString(), "status").isEqualTo("error");
			requireThat(result.get("message").asString(), "message").isEqualTo("configuration unavailable");
		}
	}

	/**
	 * Verifies that a contract tool runs without opening a command scope, so that it needs nothing of the harness
	 * inside a contract author session's sandbox.
	 */
	@Test
	public void contractToolOpensNoScope()
	{
		// Requirement: R-C27
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
		{
			int exit = PromptOptimizerCli.run(new String[]{"contract", "check", "missing-cat-contract.json"}, () ->
			{
				throw new AssertionError("A contract tool opened a command scope");
			}, ZoneOffset.UTC, out);

			requireThat(exit, "exit").isEqualTo(1);
			requireThat(bytes.toString(StandardCharsets.UTF_8), "output").contains("missing-cat-contract.json");
		}
	}
}
