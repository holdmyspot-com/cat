package io.github.cowwoc.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Checks announced session metadata without launching a native harness or creating files. */
public final class ClaudeSessionInitializationTest
{
	private static final String INITIALIZATION = """
		{"type":"system","subtype":"init","session_id":"session","model":"native-model"}
		""";

	/** Creates the test fixture. */
	public ClaudeSessionInitializationTest()
	{
	}

	/**
	 * Retains observed identities and an absent native version without inventing a launch profile.
	 *
	 * @throws IOException if valid native metadata is rejected
	 */
	@Test
	public void readsAnnouncedSessionAndModel() throws IOException
	{
		ClaudeSessionInitialization observed = read(INITIALIZATION);
		requireThat(observed.sessionId(), "session").isEqualTo("session");
		requireThat(observed.modelId(), "model").isEqualTo("native-model");
		requireThat(observed.nativeVersion(), "unavailableVersion").isEqualTo(Optional.empty());
		ClaudeSessionInitialization formatted = read(INITIALIZATION.replace(":", " : "));
		requireThat(formatted, "equivalentFormatting").isEqualTo(observed);
	}

	/**
	 * An explicitly observed version is retained, while null and ambiguous identities are rejected.
	 *
	 * @throws IOException if the valid version event is rejected
	 */
	@Test
	public void retainsOnlyObservedVersion() throws IOException
	{
		String versioned = INITIALIZATION.replace("\"model\":\"native-model\"",
			"\"model\":\"native-model\",\"claude_code_version\":\"2.1\"");
		requireThat(read(versioned).nativeVersion(), "observedVersion").isEqualTo(Optional.of("2.1"));
		Assert.expectThrows(IOException.class, () -> read(versioned.replace("\"2.1\"", "null")));
		Assert.expectThrows(IOException.class, () -> read(INITIALIZATION.replace("\"model\":\"native-model\"",
			"\"model\":\"native-model\",\"model\":\"native-model\"")));
	}

	/** A retained record must be one well-formed initialization with nonblank textual identities. */
	@Test
	public void rejectsInvalidInitialization()
	{
		for (String invalid : new String[]{"{", "[]", INITIALIZATION + "{}",
			INITIALIZATION.replace("\"init\"", "\"success\""),
			INITIALIZATION.replace("\"model\":\"native-model\"", "\"model\":3"),
			INITIALIZATION.replace("\"model\":\"native-model\"", "\"model\":\" \""),
			INITIALIZATION.replace("\"session_id\":\"session\",", "")})
			Assert.expectThrows(IOException.class, () -> read(invalid));
	}

	/**
	 * Invokes the production decoder with exactly one UTF-8 native record.
	 *
	 * @param event record to decode
	 * @return decoded announced metadata
	 * @throws IOException if the record is rejected
	 */
	private static ClaudeSessionInitialization read(String event) throws IOException
	{
		return ClaudeSessionInitialization.read(event.getBytes(StandardCharsets.UTF_8));
	}
}
