package io.github.cowwoc.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.util.Optional;
import org.testng.Assert;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/** Tests reported assistant identities without launching a harness or creating a campaign. */
public final class ClaudeAssistantMessageIdentityTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String PARENT = """
		{"type":"assistant","session_id":"session","parent_tool_use_id":null,
		 "message":{"role":"assistant","id":"message","model":"reported-model"}}
		""";

	/** Creates the test fixture. */
	public ClaudeAssistantMessageIdentityTest()
	{
	}

	/**
	 * Retains parent-message identities without manufacturing effective reasoning settings.
	 *
	 * @throws IOException if valid identities are rejected
	 */
	@Test
	public void readsParentMessageIdentity() throws IOException
	{
		ClaudeAssistantMessageIdentity observed = read(PARENT);
		requireThat(observed.sessionId(), "session").isEqualTo("session");
		requireThat(observed.messageId(), "message").isEqualTo("message");
		requireThat(observed.modelId(), "reportedModel").isEqualTo("reported-model");
		requireThat(observed.parentToolUseId(), "parentConversation").isEqualTo(Optional.empty());
	}

	/**
	 * A child message keeps its parent tool and its own reported model.
	 *
	 * @throws IOException if valid child identities are rejected
	 */
	@Test
	public void distinguishesSubagentMessage() throws IOException
	{
		ClaudeAssistantMessageIdentity child = read(PARENT.replace("\"parent_tool_use_id\":null",
			"\"parent_tool_use_id\":\"tool\"").replace("reported-model", "child-model"));
		requireThat(child.parentToolUseId(), "childParentTool").isEqualTo(Optional.of("tool"));
		requireThat(child.modelId(), "childModel").isEqualTo("child-model");
	}

	/** Missing ownership, coerced identities, and a different message role are rejected. */
	@Test
	public void rejectsIncompleteMessageIdentity()
	{
		for (String invalid : new String[]{PARENT.replace("\"parent_tool_use_id\":null,", ""),
			PARENT.replace("\"parent_tool_use_id\":null", "\"parent_tool_use_id\":3"),
			PARENT.replace("\"reported-model\"", "3"),
			PARENT.replace("\"reported-model\"", "\" \""),
			PARENT.replace("\"id\":\"message\",", ""),
			PARENT.replace("\"role\":\"assistant\"", "\"role\":\"user\"")})
			Assert.expectThrows(IOException.class, () -> read(invalid));
	}

	/**
	 * Parses test input before invoking the production identity mapping.
	 *
	 * @param event native event fixture
	 * @return reported message identities
	 * @throws IOException if semantic identity validation fails
	 */
	private static ClaudeAssistantMessageIdentity read(String event) throws IOException
	{
		return ClaudeAssistantMessageIdentity.read(JSON.readTree(event));
	}
}
