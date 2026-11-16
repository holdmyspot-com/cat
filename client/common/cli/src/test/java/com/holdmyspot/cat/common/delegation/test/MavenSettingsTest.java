package com.holdmyspot.cat.common.delegation.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.delegation.MavenSettings;
import com.holdmyspot.cat.common.delegation.MavenSettings.Proxy;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Tests conversion of explicit native proxy settings into owned Maven settings.
 */
public final class MavenSettingsTest
{
	/**
	 * Verifies absent protocol-specific values use the shared proxy and default HTTP port.
	 */
	@Test
	public void selectsSharedProxyWithDefaultPort()
	{
		Optional<Proxy> selected = MavenSettings.selectProxy(Map.of("ALL_PROXY", "http://proxy.example"));
		Proxy proxy = selected.orElseThrow();
		requireThat(proxy.host(), "proxyHost").isEqualTo("proxy.example");
		requireThat(proxy.port(), "proxyPort").isEqualTo(80);
		requireThat(proxy.nonProxyHosts(), "nonProxyHosts").isEmpty();
	}

	/**
	 * Verifies equal HTTP endpoints accept case, default-port, and root-path spelling differences.
	 */
	@Test
	public void selectsEquivalentExplicitHTTPRoutes()
	{
		Optional<Proxy> selected = MavenSettings.selectProxy(Map.of("HTTP_PROXY", "http://proxy.example",
			"HTTPS_PROXY", "HTTP://PROXY.EXAMPLE:80/"));
		Proxy proxy = selected.orElseThrow();
		requireThat(proxy.host(), "proxyHost").isEqualTo("proxy.example");
		requireThat(proxy.port(), "proxyPort").isEqualTo(80);
	}

	/**
	 * Verifies present empty protocol values select direct routes while absent values use the shared route.
	 */
	@Test
	public void absentAndEmptyProtocolSettings()
	{
		Map<String, String> sharedRoute = Map.of("ALL_PROXY", "http://proxy.example");
		Proxy fallbackProxy = MavenSettings.selectProxy(sharedRoute).orElseThrow();
		requireThat(fallbackProxy.host(), "absentSpecificRoute").isEqualTo("proxy.example");

		UnsupportedOperationException mixedRoute = unsupported(Map.of("ALL_PROXY", "http://proxy.example",
			"HTTP_PROXY", ""));
		requireThat(mixedRoute.getMessage(), "mixedRouteDiagnostic").contains("HTTP and HTTPS must both be direct");

		Optional<Proxy> directRoute = MavenSettings.selectProxy(Map.of("ALL_PROXY", "http://proxy.example",
			"HTTP_PROXY", "", "HTTPS_PROXY", ""));
		requireThat(directRoute, "explicitDirectRoutes").isEmpty();
	}

	/**
	 * Verifies lowercase presence takes precedence over the uppercase shared proxy value.
	 */
	@Test
	public void lowercaseSharedProxyTakesPrecedence()
	{
		Optional<Proxy> selected = MavenSettings.selectProxy(Map.of("all_proxy", "http://lower.example",
			"ALL_PROXY", "http://upper.example"));
		requireThat(selected.orElseThrow().host(), "selectedProxyHost").isEqualTo("lower.example");
	}

	/**
	 * Verifies mixed routes and different proxy endpoints are rejected.
	 */
	@Test
	public void rejectsDifferentAndMixedProxyRoutes()
	{
		unsupported(Map.of("HTTP_PROXY", "http://one.example"));
		UnsupportedOperationException differentEndpoints = unsupported(Map.of("HTTP_PROXY", "http://one.example",
			"HTTPS_PROXY", "http://two.example"));
		requireThat(differentEndpoints.getMessage(), "differentEndpointDiagnostic").contains(
			"HTTP and HTTPS must select the same proxy endpoint");
	}

	/**
	 * Verifies unsupported URI forms reject without exposing proxy credentials.
	 */
	@Test
	public void rejectsUnsupportedProxyURIFormsSafely()
	{
		for (String endpoint : List.of("not a URI", "https://proxy.example", "http://user:fake-password@proxy.example",
			"http://proxy.example?query", "http://proxy.example#fragment", "http://proxy.example:0",
			"http://proxy.example:65536", "http://[::1]:8080", "http://proxy.example/path"))
		{
			UnsupportedOperationException failure = unsupported(Map.of("HTTP_PROXY", endpoint));
			requireThat(failure.getMessage(), "safeDiagnostic").contains("HTTP_PROXY");
			requireThat(failure.getMessage(), "credentialRedaction").doesNotContain("fake-password");
		}
	}

	/**
	 * Verifies supported loopback bypass hosts are trimmed, canonicalized, and de-duplicated in order.
	 */
	// PMD.AvoidUsingHardCodedIP: This test asserts the exact supported native NO_PROXY host names.
	@SuppressWarnings("PMD.AvoidUsingHardCodedIP")
	@Test
	public void selectsLoopbackBypassHostsInOrder()
	{
		Optional<Proxy> selected = MavenSettings.selectProxy(Map.of("ALL_PROXY", "http://proxy.example",
			"NO_PROXY", " localhost,127.0.0.1,LOCALHOST, ::1,, "));
		List<String> bypassHosts = selected.orElseThrow().nonProxyHosts();
		requireThat(bypassHosts, "selectedBypassHosts").isEqualTo(List.of("localhost", "127.0.0.1", "::1"));
	}

	/**
	 * Verifies an unsupported nonempty bypass token rejects the selected proxy profile.
	 */
	@Test
	public void rejectsUnsupportedBypassSyntax()
	{
		UnsupportedOperationException failure = unsupported(Map.of("ALL_PROXY", "http://proxy.example",
			"NO_PROXY", "localhost,.example.com"));
		requireThat(failure.getMessage(), "bypassDiagnostic").contains("NO_PROXY");
	}

	/**
	 * Verifies the proxy value copies and validates its bypass host list.
	 */
	@Test
	public void proxyCopiesAndValidatesValues()
	{
		List<String> mutableHosts = new ArrayList<>(List.of("localhost"));
		Proxy proxy = new Proxy("proxy.example", 8080, mutableHosts);
		mutableHosts.clear();
		requireThat(proxy.nonProxyHosts(), "copiedBypassHosts").isEqualTo(List.of("localhost"));

		IllegalArgumentException invalidPort = null;
		try
		{
			new Proxy("proxy.example", 0, List.of());
		}
		catch (IllegalArgumentException failure)
		{
			invalidPort = failure;
		}
		requireThat(invalidPort, "invalidPort").isNotNull();
	}

	/**
	 * Verifies writing creates one new settings path and rejects a pre-existing destination without changing it.
	 *
	 * @throws IOException if a temporary settings fixture cannot be created
	 */
	@Test
	public void writesOnlyToNewSettingsDestination() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "maven-settings-home-"));
			Path settingsFile = home.resolve("settings.xml");
			Path written = MavenSettings.write(home, Map.of());
			requireThat(written, "settingsPath").isEqualTo(settingsFile.toAbsolutePath().normalize());
			requireThat(Files.isRegularFile(written), "settingsFileCreated").isTrue();

			Files.writeString(settingsFile, "caller-owned");
			IOException failure = null;
			try
			{
				MavenSettings.write(home, Map.of());
			}
			catch (FileAlreadyExistsException expected)
			{
				failure = expected;
			}
			requireThat(failure, "existingDestinationFailure").isNotNull();
			requireThat(Files.readString(settingsFile), "existingDestinationContents").isEqualTo("caller-owned");
		}
	}

	/**
	 * Verifies a settings symlink is rejected without changing its target.
	 *
	 * @throws IOException if the temporary symlink fixture cannot be created
	 */
	@Test
	public void rejectsExistingSettingsSymlink() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "maven-settings-link-"));
			Path target = Files.writeString(home.resolve("target.txt"), "target-owned");
			Path settingsFile = home.resolve("settings.xml");
			Files.createSymbolicLink(settingsFile, target.getFileName());

			IOException failure = null;
			try
			{
				MavenSettings.write(home, Map.of());
			}
			catch (FileAlreadyExistsException expected)
			{
				failure = expected;
			}
			requireThat(failure, "existingSymlinkFailure").isNotNull();
			requireThat(Files.isSymbolicLink(settingsFile), "symlinkRemains").isTrue();
			requireThat(Files.readString(target), "symlinkTargetContents").isEqualTo("target-owned");
		}
	}

	/**
	 * Verifies unsupported configuration is rejected before the destination is created.
	 *
	 * @throws IOException if a temporary settings home cannot be created
	 */
	@Test
	public void validatesProxyBeforeCreatingSettingsFile() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "maven-settings-invalid-"));
			UnsupportedOperationException failure = null;
			try
			{
				MavenSettings.write(home, Map.of("HTTP_PROXY", "http://user:fake-password@proxy.example"));
			}
			catch (UnsupportedOperationException expected)
			{
				failure = expected;
			}
			requireThat(failure, "unsupportedConfiguration").isNotNull();
			requireThat(failure.getMessage(), "credentialRedaction").doesNotContain("fake-password");
			requireThat(Files.exists(home.resolve("settings.xml")), "noSettingsFileOnInvalidInput").isFalse();
		}
	}

	/**
	 * Returns an unsupported-profile exception from the public proxy selector.
	 *
	 * @param inherited explicit native transport values
	 * @return safe unsupported-profile diagnostic
	 */
	private static UnsupportedOperationException unsupported(Map<String, String> inherited)
	{
		UnsupportedOperationException failure = null;
		try
		{
			MavenSettings.selectProxy(inherited);
		}
		catch (UnsupportedOperationException expected)
		{
			failure = expected;
		}
		requireThat(failure, "unsupportedConfiguration").isNotNull();
		return failure;
	}
}
