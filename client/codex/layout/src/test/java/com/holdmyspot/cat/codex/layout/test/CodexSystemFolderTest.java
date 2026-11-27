package com.holdmyspot.cat.codex.layout.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.codex.layout.CodexSystemFolder;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link CodexSystemFolder}.
 * <p>
 * The tests give the folder the platform and the answers of the two Windows calls, which only Windows can make, so
 * they run on every platform.
 */
public final class CodexSystemFolderTest
{
	private static final String REQUIREMENTS_KEY = "SOFTWARE\\Policies\\OpenAI\\Codex";
	private static final String REQUIREMENTS_VALUE = "RequirementsPath";

	/**
	 * Creates a test instance.
	 */
	public CodexSystemFolderTest()
	{
	}

	/**
	 * Returns the platforms whose paths start at a root, on which Codex's machine-wide folder is {@code /etc/codex}.
	 *
	 * @return the platforms
	 */
	@DataProvider
	public static Object[][] rootedPlatforms()
	{
		return new Object[][]{{Platform.LINUX_X64}, {Platform.LINUX_ARM64}, {Platform.MACOS_X64},
			{Platform.MACOS_ARM64}};
	}

	/**
	 * Verifies that on Linux and macOS the folder is {@code /etc/codex}, which holds the configuration, managed
	 * configuration and requirements files, and that neither Windows call is made.
	 *
	 * @param platform the platform
	 * @throws IOException if the requirements file's location cannot be read
	 */
	@Test(dataProvider = "rootedPlatforms")
	public void usesEtcCodexOnLinuxAndMacos(Platform platform) throws IOException
	{
		CodexSystemFolder folder = CodexSystemFolder.of(platform, () ->
		{
			throw new AssertionError("Only Windows has a known folder");
		}, (key, name) ->
		{
			throw new AssertionError("Only Windows has a registry");
		});

		Path etc = Path.of("/etc/codex");
		requireThat(folder.folder(), "folder").isEqualTo(etc);
		requireThat(folder.config(), "config").isEqualTo(etc.resolve("config.toml"));
		requireThat(folder.managedConfig(), "managedConfig").isEqualTo(Optional.of(etc.resolve("managed_config.toml")));
		requireThat(folder.requirements(), "requirements").isEqualTo(etc.resolve("requirements.toml"));
		requireThat(folder.fallbackCause(), "fallbackCause").isEqualTo(Optional.empty());
	}

	/**
	 * Verifies that on Windows the folder is {@code OpenAI\Codex} below the program data folder that Windows names,
	 * and that the configuration and requirements files are in it.
	 *
	 * @throws IOException if the fixture cannot be created or the requirements file's location cannot be read
	 */
	@Test
	public void usesProgramDataOnWindows() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path programData = cleanup.add(Files.createTempDirectory("cat-program-data-"));

			CodexSystemFolder folder = CodexSystemFolder.of(Platform.WINDOWS_X64, () -> programData,
				(key, name) -> Optional.empty());

			Path codex = programData.resolve("OpenAI").resolve("Codex");
			requireThat(folder.folder(), "folder").isEqualTo(codex);
			requireThat(folder.config(), "config").isEqualTo(codex.resolve("config.toml"));
			requireThat(folder.requirements(), "requirements").isEqualTo(codex.resolve("requirements.toml"));
			requireThat(folder.fallbackCause(), "fallbackCause").isEqualTo(Optional.empty());
		}
	}

	/**
	 * Verifies that when Windows cannot name the program data folder, the folder is below {@code C:\ProgramData}, as
	 * Codex's own fallback, and that the folder says why it fell back, so that a check of the folder can say that it
	 * could not be sure.
	 */
	@Test
	public void fallsBackWhenWindowsNamesNoFolder()
	{
		CodexSystemFolder folder = CodexSystemFolder.of(Platform.WINDOWS_ARM64, () ->
		{
			throw new IOException("SHGetKnownFolderPath failed with HRESULT 0x80070005");
		}, (key, name) -> Optional.empty());

		Path codex = Path.of("C:\\ProgramData").resolve("OpenAI").resolve("Codex");
		requireThat(folder.folder(), "folder").isEqualTo(codex);
		requireThat(folder.config(), "config").isEqualTo(codex.resolve("config.toml"));
		requireThat(folder.fallbackCause().isPresent(), "fallbackPresent").isTrue();
		requireThat(folder.fallbackCause().get(), "fallbackCause").contains("0x80070005").contains(codex.toString());
	}

	/**
	 * Verifies that Windows has no machine-wide managed configuration, though the folder holds a file of that name.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void hasNoManagedConfigOnWindows() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path programData = cleanup.add(Files.createTempDirectory("cat-program-data-"));
			Path codex = Files.createDirectories(programData.resolve("OpenAI").resolve("Codex"));
			Files.writeString(codex.resolve("managed_config.toml"), "sandbox_mode = \"danger-full-access\"\n");

			CodexSystemFolder folder = CodexSystemFolder.of(Platform.WINDOWS_X64, () -> programData,
				(key, name) -> Optional.empty());

			requireThat(folder.managedConfig(), "managedConfig").isEqualTo(Optional.empty());
		}
	}

	/**
	 * Verifies that on Windows the registry value {@code RequirementsPath} of the Codex policy key moves the
	 * requirements file, and that it is the only file that it moves.
	 *
	 * @throws IOException if the fixture cannot be created or the requirements file's location cannot be read
	 */
	@Test
	public void relocatesRequirementsByRegistryOnWindows() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path programData = cleanup.add(Files.createTempDirectory("cat-program-data-"));
			Path relocated = cleanup.add(Files.createTempDirectory("cat-policy-")).resolve("codex-requirements.toml");
			List<String> asked = new ArrayList<>();

			CodexSystemFolder folder = CodexSystemFolder.of(Platform.WINDOWS_X64, () -> programData, (key, name) ->
			{
				asked.add(key + " " + name);
				return Optional.of(relocated.toString());
			});

			requireThat(folder.requirements(), "requirements").isEqualTo(relocated);
			requireThat(asked, "asked").isEqualTo(List.of(REQUIREMENTS_KEY + " " + REQUIREMENTS_VALUE));
			requireThat(folder.config(), "config").
				isEqualTo(programData.resolve("OpenAI").resolve("Codex").resolve("config.toml"));
		}
	}

	/**
	 * Verifies that a missing registry value leaves the requirements file in the folder, as Codex does.
	 *
	 * @throws IOException if the fixture cannot be created or the requirements file's location cannot be read
	 */
	@Test
	public void keepsRequirementsWithoutRegistryValue() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path programData = cleanup.add(Files.createTempDirectory("cat-program-data-"));

			CodexSystemFolder folder = CodexSystemFolder.of(Platform.WINDOWS_X64, () -> programData,
				(key, name) -> Optional.empty());

			requireThat(folder.requirements(), "requirements").
				isEqualTo(programData.resolve("OpenAI").resolve("Codex").resolve("requirements.toml"));
		}
	}

	/**
	 * Verifies that a relative registry value, which Codex refuses, makes the location unknown rather than a guess.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesRelativeRegistryValue() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path programData = cleanup.add(Files.createTempDirectory("cat-program-data-"));
			CodexSystemFolder folder = CodexSystemFolder.of(Platform.WINDOWS_X64, () -> programData,
				(key, name) -> Optional.of("policy\\requirements.toml"));

			IOException refusal = expectThrows(IOException.class, folder::requirements);

			requireThat(refusal.getMessage(), "message").contains("policy\\requirements.toml").contains(REQUIREMENTS_VALUE);
		}
	}

	/**
	 * Verifies that a registry that cannot be read makes the location unknown, and the message names the cause.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesUnreadableRegistry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path programData = cleanup.add(Files.createTempDirectory("cat-program-data-"));
			CodexSystemFolder folder = CodexSystemFolder.of(Platform.WINDOWS_X64, () -> programData, (key, name) ->
			{
				throw new IOException("RegGetValueW failed with error 5");
			});

			IOException refusal = expectThrows(IOException.class, folder::requirements);

			requireThat(refusal.getMessage(), "message").contains("error 5").contains(REQUIREMENTS_VALUE);
		}
	}

	/**
	 * Verifies that a folder that a test or an installer names has all three files and no registry.
	 *
	 * @throws IOException if the requirements file's location cannot be read
	 */
	@Test
	public void namedFolderHoldsAllFiles() throws IOException
	{
		Path named = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("named-codex");

		CodexSystemFolder folder = CodexSystemFolder.at(named);

		requireThat(folder.folder(), "folder").isEqualTo(named);
		requireThat(folder.config(), "config").isEqualTo(named.resolve("config.toml"));
		requireThat(folder.managedConfig(), "managedConfig").isEqualTo(Optional.of(named.resolve("managed_config.toml")));
		requireThat(folder.requirements(), "requirements").isEqualTo(named.resolve("requirements.toml"));
		requireThat(folder.fallbackCause(), "fallbackCause").isEqualTo(Optional.empty());
	}
}
