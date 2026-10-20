package com.holdmyspot.cat.common.optimizer.cli.test;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.store.FileCampaignStore;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies that the launcher fixture's stored campaign, including its frozen judge prompt, round-trips through the
 * store. The launcher tests run {@code prompt-optimizer status} on it.
 */
public final class FixtureCampaignTest
{
	/**
	 * The campaign's identifier.
	 */
	private static final String ID = "fixture-campaign";
	/**
	 * A JSON string that holds an absolute path of the committed fixture.
	 */
	private static final Pattern ABSOLUTE_PATH = Pattern.compile("\"(/project[^\"]*)\"");
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public FixtureCampaignTest()
	{
	}

	/**
	 * Verifies that loading and writing the committed campaign preserves its exact stored form.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void committedFixtureMatchesStoreOutput() throws IOException
	{
		// Requirement: R-E21
		// Requirement: R-I4
		try (TemporaryDirectories scope = new TemporaryDirectories();
			InputStream committed = FixtureCampaignTest.class.getResourceAsStream("/optimizer/cli/campaign.json"))
		{
			requireThat(committed, "committed").isNotNull();
			String stored = new String(committed.readAllBytes(), StandardCharsets.UTF_8);
			Path campaigns = scope.add(Files.createTempDirectory("fixture-campaign"));
			Path written = campaigns.resolve(ID).resolve("held_out/campaign.json");
			Files.createDirectories(written.getParent());
			Files.writeString(written, stored, StandardCharsets.UTF_8);
			FileCampaignStore store = new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer());
			Campaign campaign = store.load(ID).orElseThrow();
			store.replaceSnapshot(campaign);
			String fresh = Files.readString(written, StandardCharsets.UTF_8);
			Path copy = Path.of("target/fixture-campaign/campaign.json");
			Files.createDirectories(copy.getParent());
			Files.writeString(copy, fresh, StandardCharsets.UTF_8);
			requireThat(fresh, "written").isEqualTo(platformForm(stored));
		}
	}

	/**
	 * Returns the committed fixture as the store writes it on this platform. The fixture holds Linux's form of its
	 * absolute paths and line endings, and the store writes a path that starts at a root, and each line ending, in the
	 * platform's form.
	 *
	 * @param stored the committed fixture
	 * @return the fixture in this platform's form
	 */
	private static String platformForm(String stored)
	{
		Matcher path = ABSOLUTE_PATH.matcher(stored);
		StringBuilder result = new StringBuilder(stored.length());
		while (path.find())
		{
			String platformPath = JSON.writeValueAsString(Path.of(path.group(1)).toString());
			path.appendReplacement(result, Matcher.quoteReplacement(platformPath));
		}
		path.appendTail(result);
		return result.toString().replace("\n", System.lineSeparator());
	}
}
