package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.store.FileCampaignStore;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
			Path root = scope.add(Files.createTempDirectory("fixture-campaign"));
			Path written = root.resolve(".cat/work/optimizer").resolve(ID).resolve("held_out/campaign.json");
			Files.createDirectories(written.getParent());
			Files.writeString(written, stored, StandardCharsets.UTF_8);
			FileCampaignStore store = new FileCampaignStore(root, JsonMapper.builder().build());
			Campaign campaign = store.load(ID).orElseThrow();
			store.replaceSnapshot(campaign);
			String fresh = Files.readString(written, StandardCharsets.UTF_8);
			Path copy = Path.of("target/fixture-campaign/campaign.json");
			Files.createDirectories(copy.getParent());
			Files.writeString(copy, fresh, StandardCharsets.UTF_8);
			requireThat(fresh, "written").isEqualTo(stored);
		}
	}
}
