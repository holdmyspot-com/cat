package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.Builder;
import io.github.cowwoc.cat.common.optimizer.campaign.Header;
import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.store.FileCampaignStore;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifies that the fixture campaign that the launcher tests read is what the store writes today. The launcher tests
 * run {@code prompt-optimizer status} on it.
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
	 * Verifies that the committed fixture equals a fresh write of the same campaign.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	@Test
	public void committedFixtureMatchesStoreOutput() throws IOException
	{
		// Requirement: R-I4
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path root = scope.add(Files.createTempDirectory("fixture-campaign"));
			Header header = new Header(ID, Path.of("/project"), Path.of("/project/CLAUDE.md"),
				Harness.CLAUDE, Lifecycle.ACTIVE);
			Campaign campaign = new Builder().header(header).confirmed().build();
			new FileCampaignStore(root, JsonMapper.builder().build()).replaceSnapshot(campaign);
			Path written = root.resolve(".cat/work/optimizer").resolve(ID).resolve("campaign.json");
			String fresh = Files.readString(written, StandardCharsets.UTF_8);
			Path copy = Path.of("target/fixture-campaign/campaign.json");
			Files.createDirectories(copy.getParent());
			Files.writeString(copy, fresh, StandardCharsets.UTF_8);
			try (InputStream committed = FixtureCampaignTest.class.getResourceAsStream(
				"/optimizer/cli/campaign.json"))
			{
				requireThat(committed, "committed").isNotNull();
				requireThat(new String(committed.readAllBytes(), StandardCharsets.UTF_8), "committed").
					isEqualTo(fresh);
			}
		}
	}
}
