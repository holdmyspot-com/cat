package com.holdmyspot.cat.common.installer.cli.internal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.activation.EntryEffect;
import com.holdmyspot.cat.common.installer.activation.EntryGroup;
import com.holdmyspot.cat.common.installer.cli.internal.ReportAudience;
import com.holdmyspot.cat.common.installer.cli.internal.ReportedEntries;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests what the installer prints for its result: the report to a person, or one line of JSON.
 */
public final class ReportAudienceTest
{
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public ReportAudienceTest()
	{
	}

	/**
	 * A person reads which product CAT is installed for, the installed version, that the product must be
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
	 * A command that asks for {@code --json} prints the result as one line of JSON, whatever the product and home.
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
