package com.holdmyspot.cat.common.updater.cli.internal.test;

import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.policy;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.testDirectory;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.trusted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.cli.internal.Locations;
import com.holdmyspot.cat.common.updater.cli.internal.Setup;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import com.holdmyspot.cat.common.updater.trust.TrustPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests explicit setup's release authority, retained harness homes and protected installation binding.
 */
public final class SetupTest
{
	/**
	 * Creates the test.
	 */
	public SetupTest()
	{
	}

	/**
	 * Setup replaces release authority with the image default while preserving another harness's recorded home.
	 */
	@Test
	public void mergesHomesAndBindsAuthority() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("setup-"));
			Path data = Files.createDirectories(root.resolve("data"));
			Path image = Files.createDirectories(data.resolve("1.0"));
			Path configuration = Files.createDirectories(root.resolve("config"));
			Path home = Files.createDirectory(root.resolve("harness-a"));
			Path otherHome = Files.createDirectory(root.resolve("harness-b"));
			Path cwd = Files.createDirectory(root.resolve("project"));
			Path defaultFile = image.resolve("conf/cat-updater/default-policy.json");
			Files.createDirectories(defaultFile.getParent());
			FileReplacer replacer = replacer();
			TrustPolicy defaults = policy(List.of(trusted("new-key", keyPair())));
			defaults.write(defaultFile, replacer);
			Files.writeString(image.resolve("VERSION"), "1.0\n");
			Path policyFile = configuration.resolve("policy.json");
			TrustPolicy old = TrustPolicy.of(List.of(trusted("old-key", keyPair())), defaults.releaseBaseUrl(),
				defaults.minimum(), defaults.maximumMajor(), Map.of("harness-b", otherHome));
			old.write(policyFile, replacer);
			Locations locations = new Locations(image.toRealPath(), data, policyFile);

			new Setup(locations, Platform.current(), replacer).setUp("harness-a", home, cwd);

			Locations installed = Locations.installed(image);
			TrustPolicy actual = TrustPolicy.read(installed.policy());
			requireThat(installed.data(), "data").isEqualTo(data);
			requireThat(actual.keys(), "keys").isEqualTo(defaults.keys());
			requireThat(actual.harnesses(), "harnesses").isEqualTo(Map.of("harness-a", home,
				"harness-b", otherHome));
		}
	}

	/**
	 * A development image without a signing key is refused before setup records any authority.
	 */
	@Test(expectedExceptions = TrustException.class,
		expectedExceptionsMessageRegExp = "This build has no release key[.]")
	public void refusesUnsignedDevelopmentBuild() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("setup-no-key-"));
			Path image = Files.createDirectories(root.resolve("data/1.0"));
			Path defaults = image.resolve("conf/cat-updater/default-policy.json");
			Files.createDirectories(defaults.getParent());
			policy(List.of()).write(defaults, replacer());
			Path home = Files.createDirectory(root.resolve("harness-a"));
			Path cwd = Files.createDirectory(root.resolve("project"));
			Locations locations = new Locations(image, image.getParent(), root.resolve("config/policy.json"));
			new Setup(locations, Platform.current(), replacer()).setUp("harness-a", home, cwd);
		}
	}

	/**
	 * Setup inside the calling project is refused before it writes a policy or anchor.
	 */
	@Test(expectedExceptions = TrustException.class, expectedExceptionsMessageRegExp = ".*outside.*")
	public void refusesProjectWritableLocations() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("setup-project-"));
			Path project = Files.createDirectory(root.resolve("project"));
			Path image = Files.createDirectories(project.resolve("data/1.0"));
			Path defaults = image.resolve("conf/cat-updater/default-policy.json");
			Files.createDirectories(defaults.getParent());
			policy(List.of(trusted("test-key", keyPair()))).write(defaults, replacer());
			Files.writeString(image.resolve("VERSION"), "1.0\n");
			Path home = Files.createDirectory(root.resolve("harness-a"));
			Path policyFile = root.resolve("config/policy.json");
			Locations locations = new Locations(image, image.getParent(), policyFile);
			try
			{
				new Setup(locations, Platform.current(), replacer()).setUp("harness-a", home, project);
			}
			finally
			{
				requireThat(Files.exists(policyFile, NOFOLLOW_LINKS), "policyFileExists").isFalse();
				requireThat(Files.exists(image.resolve(Locations.POLICY_ANCHOR), NOFOLLOW_LINKS), "anchorExists").isFalse();
			}
		}
	}

	/**
	 * Relative XDG values are ignored when explicit setup resolves its destinations.
	 */
	@Test
	public void ignoresRelativeXdgValues() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("setup-xdg-"));
			Path image = Files.createDirectories(root.resolve("image"));
			Locations actual = Locations.forSetup(image, Platform.LINUX_X64, Map.of("HOME", root.toString(),
				"XDG_DATA_HOME", "relative-data", "XDG_CONFIG_HOME", "relative-config"));
			requireThat(actual.data(), "data").isEqualTo(root.resolve(".local/share/cat-updater"));
			requireThat(actual.policy(), "policy").isEqualTo(root.resolve(".config/cat-updater/policy.json"));
		}
	}

	/**
	 * A relative protected policy anchor is refused rather than resolved against the calling project.
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*absolute and normalized.*")
	public void refusesRelativePolicyAnchor() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("setup-anchor-"));
			Path image = Files.createDirectory(root.resolve("image"));
			Path anchor = image.resolve(Locations.POLICY_ANCHOR);
			Files.createDirectories(anchor.getParent());
			Files.writeString(anchor, "project-policy.json\n");
			Locations.installed(image);
		}
	}

	/**
	 * Complete current-file validation refuses content a first-line-only batch reader cannot distinguish.
	 *
	 * @param content the protected current file's complete contents
	 */
	@Test(dataProvider = "invalidCurrentDataProvider", expectedExceptions = IOException.class)
	public void refusesMalformedCurrent(String content) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("setup-current-"));
			Path image = Files.createDirectory(root.resolve("1.0"));
			Files.writeString(root.resolve("current"), content);
			Locations.validateCurrent(image);
		}
	}

	/**
	 * Supplies full-file boundary cases, including blank lines skipped by batch FOR /F.
	 *
	 * @return the malformed selectors
	 */
	@DataProvider
	public Object[][] invalidCurrentDataProvider()
	{
		return new Object[][]{
			{""}, {"1.0"}, {"1.0\n\n"}, {"1.0\n2.0\n"}, {"1.0\r\n"},
			{"../1.0\n"}, {"1.1\n"}, {"1.0\n" + "x".repeat(128)}
		};
	}

	/**
	 * The exact setup-written selector passes validation against the actual image path.
	 */
	@Test
	public void acceptsExactCurrent() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("setup-valid-current-"));
			Path image = Files.createDirectory(root.resolve("1.0"));
			Files.writeString(root.resolve("current"), "1.0\n");
			Locations.validateCurrent(image);
		}
	}

	/**
	 * Returns an atomic replacer for isolated temporary files, which never need a retry pause.
	 *
	 * @return the replacer
	 */
	private static FileReplacer replacer()
	{
		return new FileReplacer(Platform.current(), () ->
		{
		}, Clock.systemUTC());
	}
}
