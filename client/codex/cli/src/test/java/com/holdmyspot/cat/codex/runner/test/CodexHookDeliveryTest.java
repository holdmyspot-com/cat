package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.runner.CodexHookDelivery;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.fakes.test.TestToolScope;
import java.io.IOException;
import org.testng.annotations.Test;

/**
 * Tests CAT hook delivery verification at Codex's harness app-server event boundary.
 */
public final class CodexHookDeliveryTest
{
	/**
	 * Verifies CAT refuses a run before a configured context hook can silently remain untrusted.
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*subagent start hook is untrusted.*")
	public void rejectsUntrustedSubagentHook() throws IOException
	{
		try (ToolScope scope = new TestToolScope())
		{
			CodexHookDelivery.verifyConfiguredHooks(scope.json().readTree("""
				{"result":{"data":[{"hooks":[
				{"key":"cat@cat:hooks/hooks.json:session_start:0:0","trustStatus":"trusted"},
				{"key":"cat@cat:hooks/hooks.json:subagent_start:0:0","trustStatus":"untrusted"}
				]}]}}
				"""));
		}
	}

	/**
	 * Verifies CAT refuses a run while its PostToolUse hook, which repairs cut command output, is untrusted.
	 *
	 * @throws IOException if the hook is untrusted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*post tool use hook is untrusted.*")
	public void rejectsUntrustedPostToolUseHook() throws IOException
	{
		try (ToolScope scope = new TestToolScope())
		{
			CodexHookDelivery.verifyConfiguredHooks(scope.json().readTree("""
				{"result":{"data":[{"hooks":[
				{"key":"cat@cat:hooks/hooks.json:session_start:0:0","trustStatus":"trusted"},
				{"key":"cat@cat:hooks/hooks.json:subagent_start:0:0","trustStatus":"trusted"},
				{"key":"cat@cat:hooks/hooks.json:post_tool_use:0:0","trustStatus":"untrusted"}
				]}]}}
				"""));
		}
	}

	/**
	 * Verifies that a plugin that declares no PostToolUse hook may run, because Codex lists every hook a plugin
	 * declares, so an absent entry means there is no hook to trust.
	 *
	 * @throws IOException if a hook the plugin declares is unavailable or untrusted
	 */
	@Test
	public void acceptsUndeclaredPostToolUseHook() throws IOException
	{
		try (ToolScope scope = new TestToolScope())
		{
			CodexHookDelivery.verifyConfiguredHooks(scope.json().readTree("""
				{"result":{"data":[{"hooks":[
				{"key":"cat@cat:hooks/hooks.json:session_start:0:0","trustStatus":"trusted"},
				{"key":"cat@cat:hooks/hooks.json:subagent_start:0:0","trustStatus":"trusted"}
				]}]}}
				"""));
			requireThat(true, "accepted").isTrue();
		}
	}

	/**
	 * Verifies a turn that creates a subagent fails when Codex omits that subagent's start-hook event.
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*did not deliver CAT's SubagentStart.*")
	public void rejectsMissingSubagentStartEvent() throws IOException
	{
		try (ToolScope scope = new TestToolScope())
		{
			CodexHookDelivery.verifySubagentDelivery("""
				{"method":"item/started","params":{"item":{"type":"subAgentActivity","kind":"started","agentThreadId":"c"}}}
				""", scope.json());
		}
	}

	/**
	 * Verifies a harness subagent hook event satisfies the matching delegated agent's delivery requirement.
	 */
	@Test
	public void acceptsDeliveredSubagentStartEvent() throws IOException
	{
		try (ToolScope scope = new TestToolScope())
		{
			CodexHookDelivery.verifySubagentDelivery("""
				{"method":"hook/completed","params":{"threadId":"child","run":{"eventName":"subagentStart"}}}
				{"method":"item/started","params":{"item":{"type":"subAgentActivity","agentThreadId":"child"}}}
				""", scope.json());
			requireThat(true, "delivered").isTrue();
		}
	}

	/**
	 * Verifies that an activity reporting an interaction with an already-started parent is not mistaken for another
	 * spawned subagent requiring a start hook.
	 */
	@Test
	public void ignoresInteractedSubagentActivity() throws IOException
	{
		try (ToolScope scope = new TestToolScope())
		{
			CodexHookDelivery.verifySubagentDelivery("""
				{"method":"item/started","params":{"item":{"type":"subAgentActivity","kind":"started","agentThreadId":"child"}}}
				{"method":"hook/completed","params":{"threadId":"child","run":{"eventName":"subagentStart"}}}
				{"method":"item/started","params":{"item":{"type":"subAgentActivity","kind":"interacted","agentThreadId":"p"}}}
				""", scope.json());
			requireThat(true, "delivered").isTrue();
		}
	}
}
