package io.github.cowwoc.cat.common.optimizer.workspace.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.optimizer.contract.Dependent;
import io.github.cowwoc.cat.common.optimizer.workspace.DependentRemoval;

import java.nio.file.Path;
import java.util.Optional;

import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies that each kind of dependent is removed from its file's content, and that a file that does not hold the
 * dependent is refused.
 */
public final class DependentRemovalTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String HOOKS = """
		{
		  "hooks": {
		    "SessionStart": [
		      {"matcher": "^startup$", "hooks": [{"type": "command", "command": "start.sh"}]}
		    ],
		    "PostToolUse": [
		      {"matcher": "^Bash$", "hooks": [{"type": "command", "command": "post-tool-use.sh"}]},
		      {"matcher": "^Read$", "hooks": [
		        {"type": "command", "command": "post-tool-use.sh"},
		        {"type": "command", "command": "audit.sh"}
		      ]}
		    ]
		  }
		}
		""";

	/**
	 * Verifies that a whole-file dependent removes the file.
	 */
	@Test
	public void wholeFileIsRemoved()
	{
		// Requirement: R-W10
		Optional<byte[]> result = DependentRemoval.apply(dependent(new Dependent.WholeFile()),
			"#!/bin/sh\n".getBytes(UTF_8), JSON);

		requireThat(result.isEmpty(), "removed").isTrue();
	}

	/**
	 * Verifies that a span dependent deletes exactly that span and keeps the rest of the file.
	 */
	@Test
	public void spanIsDeleted()
	{
		// Requirement: R-W10
		Optional<byte[]> result = DependentRemoval.apply(dependent(new Dependent.Span("Route B.\n")),
			"Route A.\nRoute B.\nRoute C.\n".getBytes(UTF_8), JSON);

		requireThat(new String(result.orElseThrow(), UTF_8), "content").isEqualTo("Route A.\nRoute C.\n");
	}

	/**
	 * Verifies that a span that occurs twice is refused, because CAT cannot tell which occurrence serves the target.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*2 times.*")
	public void repeatedSpanIsRefused()
	{
		// Requirement: R-W10
		DependentRemoval.apply(dependent(new Dependent.Span("Route")), "Route A.\nRoute B.\n".getBytes(UTF_8), JSON);
	}

	/**
	 * Verifies that a span that the file does not hold is refused.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*0 times.*")
	public void missingSpanIsRefused()
	{
		// Requirement: R-W10
		DependentRemoval.apply(dependent(new Dependent.Span("Route D.")), "Route A.\n".getBytes(UTF_8), JSON);
	}

	/**
	 * Verifies that a hook registration removes every hook of its event with its command, drops a group left without
	 * hooks, and keeps the event's other hooks and every other event.
	 */
	@Test
	public void hookRegistrationIsUnregistered()
	{
		// Requirement: R-W10
		Optional<byte[]> result = DependentRemoval.apply(dependent(new Dependent.HookRegistration("PostToolUse",
			"post-tool-use.sh")), HOOKS.getBytes(UTF_8), JSON);

		JsonNode hooks = JSON.readTree(result.orElseThrow()).get("hooks");
		requireThat(hooks.get("SessionStart").get(0).get("hooks").get(0).get("command").asString(), "sessionStart").
			isEqualTo("start.sh");
		JsonNode postToolUse = hooks.get("PostToolUse");
		requireThat(postToolUse.size(), "groups").isEqualTo(1);
		requireThat(postToolUse.get(0).get("matcher").asString(), "matcher").isEqualTo("^Read$");
		requireThat(postToolUse.get(0).get("hooks").size(), "hooks").isEqualTo(1);
		requireThat(postToolUse.get(0).get("hooks").get(0).get("command").asString(), "command").
			isEqualTo("audit.sh");
	}

	/**
	 * Verifies that an event left without groups is removed.
	 */
	@Test
	public void emptiedEventIsRemoved()
	{
		// Requirement: R-W10
		Optional<byte[]> result = DependentRemoval.apply(dependent(new Dependent.HookRegistration("SessionStart",
			"start.sh")), HOOKS.getBytes(UTF_8), JSON);

		JsonNode hooks = JSON.readTree(result.orElseThrow()).get("hooks");
		requireThat(hooks.has("SessionStart"), "sessionStart").isFalse();
		requireThat(hooks.get("PostToolUse").size(), "postToolUse").isEqualTo(2);
	}

	/**
	 * Verifies that a hook that the configuration does not register is refused.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*registers no PostToolUse hook.*")
	public void unregisteredHookIsRefused()
	{
		// Requirement: R-W10
		DependentRemoval.apply(dependent(new Dependent.HookRegistration("PostToolUse", "other.sh")),
			HOOKS.getBytes(UTF_8), JSON);
	}

	/**
	 * Verifies that a hook registration in a file that is not a hook configuration is refused.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*not a hook configuration.*")
	public void nonHookFileIsRefused()
	{
		// Requirement: R-W10
		DependentRemoval.apply(dependent(new Dependent.HookRegistration("PostToolUse", "post-tool-use.sh")),
			"# Rules\n".getBytes(UTF_8), JSON);
	}

	/**
	 * Returns a dependent in the project.
	 *
	 * @param removal the part of the file that control trials remove
	 * @return the dependent
	 */
	private static Dependent dependent(Dependent.Removal removal)
	{
		return new Dependent(Path.of("hooks/hooks.json"), removal,
			"Repairs the output of read-part.");
	}
}
