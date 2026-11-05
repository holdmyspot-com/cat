package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.engine.InstallRequest;
import com.holdmyspot.cat.common.installer.engine.UpdateResult;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * An install of the build that the harness already has asks the harness to install nothing, but still brings CAT's
 * permission and sandbox entries in line with the harness configuration, which may have changed since the last
 * install.
 */
public final class UpToDateTest
{
	/**
	 * The migration registry of a release that registers no data migration.
	 */
	private static final String NO_MIGRATION = "# No migration\n";
	/**
	 * The question that the fake integration asks before it changes a shared setting.
	 */
	private static final String QUESTION = "May CAT change the shared setting?";

	/**
	 * Creates the tests.
	 */
	public UpToDateTest()
	{
	}

	/**
	 * Entries that the configuration lacks are added again, name the installer in the active version folder, and are
	 * recorded, while the harness installs nothing.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void repairsEntries() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("up-to-date-entries-")),
				new ReleaseCatalog("1.1", NO_MIGRATION));
			Path active = fixture.installActive("1.1");
			fixture.integration.adding(Map.of("settings.json", List.of("repaired-entry")));
			int calls = fixture.integration.calls().size();

			UpdateResult result = fixture.install("v1.1");

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UP_TO_DATE);
			requireThat(result.message(), "message").isEqualTo("CAT is up to date.");
			List<String> later = fixture.integration.callsWithSubjects().subList(calls,
				fixture.integration.calls().size());
			requireThat(later, "laterCalls").contains("installPermissions " + active.resolve("bin/installer"));
			requireThat(fixture.integration.calls().subList(calls, fixture.integration.calls().size()),
				"laterCallNames").doesNotContain("register");
			requireThat(Files.readString(fixture.permissionRecord()), "record").contains("repaired-entry");
		}
	}

	/**
	 * A change that needs the user's permission, such as a newly selected setting that CAT must replace, is asked
	 * about; when the user withholds it, nothing changes and the refusal is the question.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void asksBeforeChange() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("up-to-date-refused-")),
				new ReleaseCatalog("1.1", NO_MIGRATION));
			fixture.installActive("1.1");
			fixture.integration.asking(QUESTION, Map.of("config#selection", List.of("built-in")));
			Map<String, String> before = Releases.snapshot(fixture.home);

			UpdateResult result = fixture.engine().install("v1.1", request(fixture, false));

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(result.message(), "message").isEqualTo(QUESTION);
			requireThat(Releases.snapshot(fixture.home), "after").isEqualTo(before);
			requireThat(fixture.integration.calls(), "calls").doesNotContain("installConsentedEntries");
		}
	}

	/**
	 * A change that the user allows is made and recorded, and the message tells the user about it.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void makesChangeThatUserAllows() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("up-to-date-consent-")),
				new ReleaseCatalog("1.1", NO_MIGRATION));
			fixture.installActive("1.1");
			fixture.integration.asking(QUESTION, Map.of("config#selection", List.of("built-in"))).
				describing("config#selection", "CAT changed the shared setting.");

			UpdateResult result = fixture.engine().install("v1.1", request(fixture, true));

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UP_TO_DATE);
			requireThat(result.message(), "message").isEqualTo("CAT is up to date. CAT changed the shared setting.");
			requireThat(fixture.integration.calls(), "calls").contains("installConsentedEntries");
			requireThat(Files.readString(fixture.permissionRecord()), "record").contains("config#selection");
		}
	}

	/**
	 * Returns the request of an installer that runs from outside the harness home.
	 *
	 * @param fixture the harness home
	 * @param consent the user's answer to each question
	 * @return the request
	 */
	private static InstallRequest request(EngineFixture fixture, boolean consent)
	{
		return new InstallRequest(fixture.integration.session(), fixture.root.resolve("download/plugin"), List.of(),
			fixture.root.resolve("user/.cat/locks"), _ -> consent);
	}
}
