package com.holdmyspot.cat.common.installer.cli.internal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.activation.EntryEffect;
import com.holdmyspot.cat.common.installer.activation.EntryGroup;
import com.holdmyspot.cat.common.installer.cli.internal.ReportAudience;
import com.holdmyspot.cat.common.installer.cli.internal.ReportedEntries;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests who the installer takes to read its result, and what it prints for each reader.
 */
public final class ReportAudienceTest
{
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final List<String> AGENT_VARIABLES = List.of("HARNESS_A_SESSION", "HARNESS_B_SESSION");

	/**
	 * Creates the test.
	 */
	public ReportAudienceTest()
	{
	}

	/**
	 * Supplies the facts that decide the reader.
	 *
	 * @return whether the command line asks for {@code --json}, whether the standard output shows on a terminal, the
	 *         environment, and whether a person reads the result
	 */
	@DataProvider
	public Object[][] readers()
	{
		return new Object[][]
			{
				// A person at a terminal
				{false, true, Map.of(), true},
				// A reader that is not a terminal, as the update skill is
				{false, false, Map.of(), false},
				// The same command with --json at a terminal prints the same line as the reader that is not a terminal
				{true, true, Map.of(), false},
				{true, false, Map.of(), false},
				// An agent that runs the installer in a terminal that its harness allocates parses the result
				{false, true, Map.of("HARNESS_A_SESSION", "1"), false},
				{false, true, Map.of("HARNESS_B_SESSION", "1"), false},
				// A variable with an empty value does not mark an agent session
				{false, true, Map.of("HARNESS_A_SESSION", ""), true}
			};
	}

	/**
	 * The installer describes its result to a person only where one reads it.
	 *
	 * @param json        whether the command line asks for {@code --json}
	 * @param terminal    whether the standard output shows on a terminal
	 * @param environment the installer's environment
	 * @param person      whether a person reads the result
	 */
	@Test(dataProvider = "readers")
	public void personReadsOnlyAtTerminal(boolean json, boolean terminal,
		Map<String, String> environment, boolean person)
	{
		boolean actual = ReportAudience.isPerson(json, () -> terminal, environment, AGENT_VARIABLES);

		requireThat(actual, "person").isEqualTo(person);
	}

	/**
	 * A command line that asks for {@code --json} does not ask the terminal where the standard output shows.
	 */
	@Test
	public void jsonOptionSkipsTerminalCheck()
	{
		boolean person = ReportAudience.isPerson(true, () ->
		{
			throw new AssertionError("The terminal was asked");
		}, Map.of(), AGENT_VARIABLES);

		requireThat(person, "person").isFalse();
	}

	/**
	 * A person at a terminal reads which product CAT is installed for, the installed version, that the product must be
	 * restarted, and what the permission entries that install added let the product do, with the file that holds
	 * them. The report is not JSON.
	 */
	@Test
	public void personReadsInstalledReport()
	{
		ObjectNode result = MAPPER.createObjectNode();
		result.put("result", "installed");
		result.put("harness", "harness-a");
		result.put("version", "1.2");
		result.put("message", "CAT is installed for harness-a.");
		result.putArray("paths");
		result.put("consistent", true);
		result.putArray("key_ids");
		result.putObject("permissions").putArray("settings.json#permissions.allow").add("Bash(entry-one)").
			add("Bash(entry-two)");
		Path home = FileSystems.getDefault().getRootDirectories().iterator().next().resolve("user").resolve("home");
		List<EntryGroup> groups = List.of(new EntryGroup(EntryEffect.RUNS_WITHOUT_ASKING, Path.of("settings.json"),
			"permissions.allow", List.of("installer install", "installer status"),
			List.of("Bash(entry-one)", "Bash(entry-two)")));

		String text = ReportAudience.text(result, true, Optional.of("Product harness-a"),
			new ReportedEntries(groups, home, home, home.resolve("installer"), false));

		requireThat(text, "text").contains("Product harness-a").contains("1.2").
			contains("Restart Product harness-a for the changes to take effect.").
			contains("~" + home.getFileSystem().getSeparator() + "settings.json").contains("permissions.allow").
			contains("installer install, installer status").doesNotContain("{").doesNotContain("\"result\"");
	}

	/**
	 * A reader that is not a person reads the result as one line of JSON, whatever the product and home.
	 */
	@Test
	public void programReadsJsonLine()
	{
		ObjectNode result = MAPPER.createObjectNode();
		result.put("result", "refused");
		result.put("message", "May CAT change the shared setting? CAT changed nothing.");

		String text = ReportAudience.text(result, false, Optional.of("Product harness-a"), null);

		requireThat(text, "text").isEqualTo("{\"result\":\"refused\"," +
			"\"message\":\"May CAT change the shared setting? CAT changed nothing.\"}");
	}
}
