package com.holdmyspot.cat.common.delegation.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;
import com.holdmyspot.cat.common.delegation.Plan;
import com.holdmyspot.cat.common.delegation.Verification;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests construction of the reviewed Maven invocation.
 */
public final class VerificationTest
{
	/**
	 * Creates the test fixture.
	 */
	public VerificationTest()
	{
	}

	/**
	 * Verifies that gate arguments are constructed as one argv list from typed reviewed values.
	 */
	@Test
	public void commandUsesReviewedArguments() throws IOException
	{
		Plan.Verification gate = new Plan.Verification(List.of("common/cli", "common/installer"),
			List.of("DelegationTest", "GitTest"), "review-1", "parent", List.of("client/pom.xml"),
			List.of("client/common/cli/target"));
		Plan.Work work = new Plan.Work("runtime", "assignment-1", List.of("client/common/cli/src/test/java/Test.java"),
			gate);

		List<String> command = Verification.command(work);

		requireThat(command, "command").isEqualTo(List.of("client/mvnw", "-q", "-f", "client/pom.xml", "-pl",
			"common/cli,common/installer", "-am", "-Dtest=DelegationTest,GitTest",
			"-Dsurefire.failIfNoSpecifiedTests=false", "verify"));
	}

	/**
	 * Verifies only the reviewed proxy inputs are preserved with their exact supplied values.
	 */
	@Test
	public void environmentPreservesReviewedTransport()
	{
		Map<String, String> inherited = Map.ofEntries(Map.entry("PATH", "/bin"),
			Map.entry("http_proxy", "http://lower-http"), Map.entry("HTTP_PROXY", "http://upper-http"),
			Map.entry("https_proxy", "http://lower-https"), Map.entry("HTTPS_PROXY", ""),
			Map.entry("all_proxy", "http://lower-all"), Map.entry("ALL_PROXY", "http://upper-all"),
			Map.entry("no_proxy", "lower-host"), Map.entry("NO_PROXY", "upper-host"),
			Map.entry("HOME", "/caller"), Map.entry("MAVEN_OPTS", "unsafe"), Map.entry("OTHER", "ignored"));
		Map<String, String> environment = Verification.environment(Path.of("/owned/invocation"), inherited);
		requireThat(environment, "environment").isEqualTo(Map.ofEntries(Map.entry("PATH", "/bin"),
			Map.entry("http_proxy", "http://lower-http"), Map.entry("HTTP_PROXY", "http://upper-http"),
			Map.entry("https_proxy", "http://lower-https"), Map.entry("HTTPS_PROXY", ""),
			Map.entry("all_proxy", "http://lower-all"), Map.entry("ALL_PROXY", "http://upper-all"),
			Map.entry("no_proxy", "lower-host"), Map.entry("NO_PROXY", "upper-host"),
			Map.entry("HOME", "/owned/invocation/home"), Map.entry("MAVEN_USER_HOME", "/owned/invocation/home"),
			Map.entry("TMPDIR", "/owned/invocation/temporary"), Map.entry("TMP", "/owned/invocation/temporary"),
			Map.entry("TEMP", "/owned/invocation/temporary"),
			Map.entry("MAVEN_OPTS", "-Djava.io.tmpdir=/owned/invocation/temporary")));
	}

	/**
	 * Verifies wrapper termination without a Maven-created log is rejected for both exit classes.
	 */
	@Test
	public void missingMavenLogRejectsTerminalExit()
	{
		for (int exit : List.of(0, 1))
		{
			IOException failure = null;
			try
			{
				Verification.requireMavenLog(false, exit, Path.of("/retained/wrapper.log"));
			}
			catch (IOException e)
			{
				failure = e;
			}
			requireThat(failure, "missingLog" + exit).isNotNull();
			requireThat(failure.getMessage(), "failureDetails" + exit).contains("Maven did not create its diagnostic log");
			requireThat(failure.getMessage(), "wrapperLog" + exit).contains("/retained/wrapper.log");
			requireThat(failure.getMessage(), "terminalExit" + exit).contains(Integer.toString(exit));
		}
	}

	/**
	 * Verifies Maven's diagnostic-log witness does not treat a Maven failure exit as a startup failure.
	 *
	 * @throws IOException if the witness decision rejects the terminal result
	 */
	@Test
	public void mavenLogPermitsNonzeroExit() throws IOException
	{
		Verification.requireMavenLog(true, 1, Path.of("/retained/wrapper.log"));
	}

	/**
	 * Verifies every output root is independently exercised with directory-boundary matching.
	 *
	 * @throws IOException if a covered or uncovered root is unexpectedly rejected
	 */
	@Test
	public void outputCoverageRequiresEveryReviewedRoot() throws IOException
	{
		Plan.Verification gate = new Plan.Verification(List.of("common/cli"), List.of("DelegationTest"), "review-1",
			"parent", List.of("client/pom.xml"), List.of("client/common/cli/target", "client/common/git/target"));
		Plan.Work work = new Plan.Work("runtime", "assignment-1", List.of("client/common/cli/src/Test.java"), gate);
		Verification.requireOutputCoverage(work, List.of("client/common/cli/target/probe.txt",
			"client/common/git/target/"));
		IOException failure = null;
		try
		{
			Verification.requireOutputCoverage(work, List.of("client/common/cli/targeted/probe.txt"));
		}
		catch (IOException e)
		{
			failure = e;
		}
		requireThat(failure, "missingRoots").isNotNull();
		requireThat(failure.getMessage(), "listsUnexercisedRoots").contains("client/common/cli/target");
		requireThat(failure.getMessage(), "listsSecondRoot").contains("client/common/git/target");
	}

	/**
	 * Verifies Maven receives the invocation-owned settings file as a separate command argument.
	 *
	 * @throws IOException if the temporary invocation cannot be created or run
	 */
	@Test
	public void runUsesInvocationOwnedMavenSettings() throws IOException
	{
		try (TestJvmScope baseScope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path testRoot = cleanup.add(Files.createTempDirectory(baseScope.temporaryDirectory(),
				"verification-settings-"));
			DelegationFixture fixture = new DelegationFixture(testRoot);
			Path project = Files.createDirectory(testRoot.resolve("project"));
			Path scopeTemporary = Files.createDirectory(testRoot.resolve("scope-temporary"));
			List<String> startedCommand = new ArrayList<>();
			ExecutableFiles executableFiles = ExecutableFilesSecrets.getExecutableFilesAccess().create(Thread::yield,
				Clock.systemUTC(), builder ->
				{
					startedCommand.addAll(builder.command());
					DelegationFixture.createMavenLog(builder);
					return fixture.running(0, "");
				});
			Plan.Verification gate = new Plan.Verification(List.of("common/cli"), List.of("DelegationTest"),
				"review-1", "parent", List.of("client/pom.xml"), List.of("client/common/cli/target"));
			Plan.Work work = new Plan.Work("runtime", "assignment-1", List.of("client/common/cli/src/test/java/Test.java"),
				gate);
			try (JvmScope scope = new TestJvmScope(scopeTemporary, executableFiles))
			{
				Verification.run(scope, fixture.admission(scope), project, work,
					testRoot.resolve("invocation"), Map.of("PATH", "/bin"), "source-manifest", List.of(),
					List.of("client/common/cli/target"));
			}

			requireThat(startedCommand, "startedCommand").contains("--settings");
			int settingsIndex = startedCommand.indexOf("--settings");
			requireThat(startedCommand.get(settingsIndex + 1), "settingsPath").isEqualTo(
				testRoot.resolve("invocation/home/settings.xml").toAbsolutePath().normalize().toString());
		}
	}
}
