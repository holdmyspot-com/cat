package com.holdmyspot.cat.common.installer.cli.internal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.cli.internal.Arguments;
import com.holdmyspot.cat.common.installer.cli.internal.Arguments.Operation;
import java.nio.file.Path;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests the installer's complete strict argument grammar.
 */
public final class ArgumentsTest
{
	/**
	 * Creates the test.
	 */
	public ArgumentsTest()
	{
	}

	/**
	 * Install accepts a selected provider and an optional approved release selector, after an optional harness home
	 * that comes before the operation.
	 */
	@Test
	public void parsesInstallRelease()
	{
		Path home = Path.of(System.getProperty("cat.installer.test.directory")).resolve("harness-home");
		Arguments tagged = Arguments.parse(new String[]{"install", "--harness", "harness-a", "v1.2.3"},
			Set.of("harness-a"));
		Arguments homed = Arguments.parse(new String[]{"--home", home.toString(), "install", "--harness", "harness-a",
			"v1.2"}, Set.of("harness-a"));
		requireThat(tagged.operation(), "operation").isEqualTo(Operation.INSTALL);
		requireThat(tagged.harness(), "harness").isEqualTo("harness-a");
		requireThat(tagged.tag(), "tag").isEqualTo("v1.2.3");
		requireThat(tagged.home(), "taggedHome").isNull();
		requireThat(homed.home(), "home").isEqualTo(home);
		requireThat(homed.tag(), "homedTag").isEqualTo("v1.2");
	}

	/**
	 * Both latest spellings reach the engine as its absent explicit-tag selector.
	 */
	@Test
	public void latestHasNoExplicitTag()
	{
		Arguments omitted = Arguments.parse(new String[]{"install", "--harness", "harness-a"},
			Set.of("harness-a"));
		Arguments latest = Arguments.parse(new String[]{"install", "--harness", "harness-a", "latest"},
			Set.of("harness-a"));
		requireThat(omitted.tag(), "omittedTag").isNull();
		requireThat(latest.tag(), "latestTag").isNull();
	}

	/**
	 * Status without a harness selects the installation's own harness.
	 */
	@Test
	public void parsesStatus()
	{
		requireThat(Arguments.parse(new String[]{"status"}, Set.of("harness-a")).harness(), "harness").isEmpty();
	}

	/**
	 * Status asks for the exact permission and sandbox entries with {@code --verbose} after the arguments that select
	 * the installation, and lists them only then.
	 */
	@Test
	public void parsesVerboseStatus()
	{
		Set<String> harnesses = Set.of("harness-a");

		Arguments own = Arguments.parse(new String[]{"status", "--verbose"}, harnesses);
		Arguments named = Arguments.parse(new String[]{"status", "--harness", "harness-a", "--verbose"}, harnesses);
		Arguments plain = Arguments.parse(new String[]{"status", "--harness", "harness-a"}, harnesses);

		requireThat(own.verbose(), "own").isTrue();
		requireThat(own.harness(), "ownHarness").isEmpty();
		requireThat(named.verbose(), "named").isTrue();
		requireThat(named.harness(), "namedHarness").isEqualTo("harness-a");
		requireThat(plain.verbose(), "plain").isFalse();
	}

	/**
	 * Install names the harness, and optionally its home before the operation; without a home it installs at the home
	 * that the user's environment selects.
	 */
	@Test
	public void parsesInstall()
	{
		Path home = Path.of(System.getProperty("cat.installer.test.directory")).resolve("harness-home");
		Arguments homed = Arguments.parse(new String[]{"--home", home.toString(), "install", "--harness",
			"harness-a"}, Set.of("harness-a"));
		Arguments unhomed = Arguments.parse(new String[]{"install", "--harness", "harness-a"}, Set.of("harness-a"));
		requireThat(homed.operation(), "operation").isEqualTo(Operation.INSTALL);
		requireThat(homed.harness(), "harness").isEqualTo("harness-a");
		requireThat(homed.home(), "home").isEqualTo(home);
		requireThat(homed.tag(), "tag").isNull();
		requireThat(unhomed.operation(), "unhomedOperation").isEqualTo(Operation.INSTALL);
		requireThat(unhomed.home(), "unhomedHome").isNull();
	}

	/**
	 * Uninstall removes the installation and keeps its plugin data unless asked to delete it; the harness option only
	 * names the installation's own harness.
	 */
	@Test
	public void parsesUninstall()
	{
		Arguments bare = Arguments.parse(new String[]{"uninstall"}, Set.of("harness-a"));
		Arguments kept = Arguments.parse(new String[]{"uninstall", "--harness", "harness-a"}, Set.of("harness-a"));
		Arguments deleted = Arguments.parse(new String[]{"uninstall", "--harness", "harness-a", "--delete-data"},
			Set.of("harness-a"));
		Arguments bareDeleted = Arguments.parse(new String[]{"uninstall", "--delete-data"}, Set.of("harness-a"));
		requireThat(bare.operation(), "operation").isEqualTo(Operation.UNINSTALL);
		requireThat(bare.harness(), "bareHarness").isEmpty();
		requireThat(bare.deleteData(), "bareDeleteData").isFalse();
		requireThat(kept.harness(), "harness").isEqualTo("harness-a");
		requireThat(kept.deleteData(), "keptDeleteData").isFalse();
		requireThat(deleted.deleteData(), "deleteData").isTrue();
		requireThat(bareDeleted.harness(), "bareDeletedHarness").isEmpty();
		requireThat(bareDeleted.deleteData(), "bareDeletedDeleteData").isTrue();
	}

	/**
	 * Install accepts the user's permission to create a permission profile before the operation, in either order with
	 * the harness home; without it, install has no such permission.
	 */
	@Test
	public void parsesProfilePermission()
	{
		Path home = Path.of(System.getProperty("cat.installer.test.directory")).resolve("harness-home");
		Arguments bare = Arguments.parse(new String[]{"--create-profile", "install", "--harness", "harness-a"},
			Set.of("harness-a"));
		Arguments full = Arguments.parse(new String[]{"--home", home.toString(), "--create-profile", "install",
			"--harness", "harness-a", "v1.2"}, Set.of("harness-a"));
		Arguments swapped = Arguments.parse(new String[]{"--create-profile", "--home", home.toString(), "install",
			"--harness", "harness-a"}, Set.of("harness-a"));
		Arguments without = Arguments.parse(new String[]{"--home", home.toString(), "install", "--harness",
			"harness-a", "v1.2"}, Set.of("harness-a"));
		requireThat(bare.createProfile(), "bare").isTrue();
		requireThat(bare.tag(), "bareTag").isNull();
		requireThat(full.createProfile(), "full").isTrue();
		requireThat(full.home(), "fullHome").isEqualTo(home);
		requireThat(full.tag(), "fullTag").isEqualTo("v1.2");
		requireThat(swapped.createProfile(), "swapped").isTrue();
		requireThat(swapped.home(), "swappedHome").isEqualTo(home);
		requireThat(without.createProfile(), "without").isFalse();
	}

	/**
	 * Every operation accepts a request for the JSON result before the operation, in any order with the options
	 * that only install takes; without it, the user has not asked for JSON.
	 */
	@Test
	public void parsesJsonRequest()
	{
		Path home = Path.of(System.getProperty("cat.installer.test.directory")).resolve("harness-home");
		Set<String> harnesses = Set.of("harness-a");
		Arguments install = Arguments.parse(new String[]{"--json", "install", "--harness", "harness-a", "v1.2"},
			harnesses);
		Arguments installWithOptions = Arguments.parse(new String[]{"--create-profile", "--json", "--home",
			home.toString(), "install", "--harness", "harness-a"}, harnesses);
		Arguments status = Arguments.parse(new String[]{"--json", "status"}, harnesses);
		Arguments uninstall = Arguments.parse(new String[]{"--json", "uninstall", "--harness", "harness-a",
			"--delete-data"}, harnesses);
		Arguments without = Arguments.parse(new String[]{"install", "--harness", "harness-a"}, harnesses);
		requireThat(install.json(), "install").isTrue();
		requireThat(install.tag(), "installTag").isEqualTo("v1.2");
		requireThat(installWithOptions.json(), "installWithOptions").isTrue();
		requireThat(installWithOptions.createProfile(), "createProfile").isTrue();
		requireThat(installWithOptions.home(), "home").isEqualTo(home);
		requireThat(status.json(), "status").isTrue();
		requireThat(status.operation(), "statusOperation").isEqualTo(Operation.STATUS);
		requireThat(uninstall.json(), "uninstall").isTrue();
		requireThat(uninstall.deleteData(), "deleteData").isTrue();
		requireThat(without.json(), "without").isFalse();
	}

	/**
	 * Every operation accepts a request for the JSON result as the last argument, after the operation and its
	 * arguments, so that the command that asks for it still starts with the operation.
	 */
	@Test
	public void parsesTrailingJsonRequest()
	{
		Set<String> harnesses = Set.of("harness-a");
		Arguments install = Arguments.parse(new String[]{"install", "--harness", "harness-a", "--json"}, harnesses);
		Arguments release = Arguments.parse(new String[]{"install", "--harness", "harness-a", "v1.2", "--json"},
			harnesses);
		Arguments latest = Arguments.parse(new String[]{"install", "--harness", "harness-a", "latest", "--json"},
			harnesses);
		Arguments status = Arguments.parse(new String[]{"status", "--json"}, harnesses);
		Arguments statusOfHarness = Arguments.parse(new String[]{"status", "--harness", "harness-a", "--json"},
			harnesses);
		Arguments verbose = Arguments.parse(new String[]{"status", "--harness", "harness-a", "--verbose", "--json"},
			harnesses);
		Arguments uninstall = Arguments.parse(new String[]{"uninstall", "--json"}, harnesses);
		Arguments uninstallHarness = Arguments.parse(new String[]{"uninstall", "--harness", "harness-a", "--json"},
			harnesses);
		Arguments deleteData = Arguments.parse(new String[]{"uninstall", "--harness", "harness-a", "--delete-data",
			"--json"}, harnesses);
		Arguments withOptions = Arguments.parse(new String[]{"--create-profile", "install", "--harness", "harness-a",
			"v1.2", "--json"}, harnesses);
		requireThat(install.json(), "install").isTrue();
		requireThat(install.operation(), "installOperation").isEqualTo(Operation.INSTALL);
		requireThat(install.tag(), "installTag").isNull();
		requireThat(release.json(), "release").isTrue();
		requireThat(release.tag(), "releaseTag").isEqualTo("v1.2");
		requireThat(latest.json(), "latest").isTrue();
		requireThat(latest.tag(), "latestTag").isNull();
		requireThat(status.json(), "status").isTrue();
		requireThat(status.harness(), "statusHarness").isEmpty();
		requireThat(statusOfHarness.json(), "statusOfHarness").isTrue();
		requireThat(statusOfHarness.harness(), "statusOfHarnessName").isEqualTo("harness-a");
		requireThat(verbose.json(), "verbose").isTrue();
		requireThat(verbose.verbose(), "verboseListsEntries").isTrue();
		requireThat(uninstall.json(), "uninstall").isTrue();
		requireThat(uninstall.deleteData(), "uninstallDeleteData").isFalse();
		requireThat(uninstallHarness.json(), "uninstallHarness").isTrue();
		requireThat(deleteData.json(), "deleteData").isTrue();
		requireThat(deleteData.deleteData(), "deletesData").isTrue();
		requireThat(withOptions.json(), "withOptions").isTrue();
		requireThat(withOptions.createProfile(), "createProfile").isTrue();
		requireThat(withOptions.tag(), "withOptionsTag").isEqualTo("v1.2");
	}

	/**
	 * An install that gives a consent option after the operation is refused with the command that gives it before the
	 * operation, which keeps the {@code --json} that the refused command asked for as its last argument.
	 */
	@Test
	public void lateOptionRefusalKeepsJsonRequest()
	{
		try
		{
			Arguments.parse(new String[]{"install", "--harness", "harness-a", "--create-profile", "--json"},
				Set.of("harness-a"));
		}
		catch (IllegalArgumentException e)
		{
			requireThat(e.getMessage(), "message").endsWith("installer --create-profile install --harness harness-a " +
				"--json");
			return;
		}
		throw new AssertionError("A late option was accepted");
	}

	/**
	 * The usage diagnostic names the option that asks for the JSON result.
	 */
	@Test
	public void usageNamesJsonOption()
	{
		try
		{
			Arguments.parse(new String[]{"rollback"}, Set.of("harness-a"));
		}
		catch (IllegalArgumentException e)
		{
			requireThat(e.getMessage(), "message").contains("[--json]");
			return;
		}
		throw new AssertionError("rollback was accepted");
	}

	/**
	 * Supplies install commands that give the profile permission or the harness home after the operation, with the
	 * form that the installer accepts.
	 *
	 * @return the refused command and the accepted form, which the refusal names
	 */
	@DataProvider
	public Object[][] lateOptions()
	{
		return new Object[][]
		{
			{new String[]{"install", "--harness", "harness-a", "--create-profile"},
				"installer --create-profile install --harness harness-a"},
			{new String[]{"install", "--harness", "harness-a", "v1.2", "--create-profile"},
				"installer --create-profile install --harness harness-a v1.2"},
			{new String[]{"install", "--harness", "harness-a", "--home", "/home/a"},
				"installer --home /home/a install --harness harness-a"},
			{new String[]{"install", "--harness", "harness-a", "--home", "/home/a", "v1.2", "--create-profile"},
				"installer --home /home/a --create-profile install --harness harness-a v1.2"}
		};
	}

	/**
	 * An install that gives the profile permission or the harness home after the operation is refused, and the
	 * refusal names the form that the installer accepts, with the option before the operation. A command that starts
	 * with the operation is the one that harness permission entries allow without asking, so the option that gives
	 * the user's permission or redirects the installation must not follow it.
	 *
	 * @param args     the refused command
	 * @param accepted the accepted form
	 */
	@Test(dataProvider = "lateOptions")
	public void lateOptionNamesAcceptedForm(String[] args, String accepted)
	{
		try
		{
			Arguments.parse(args, Set.of("harness-a"));
		}
		catch (IllegalArgumentException e)
		{
			requireThat(e.getMessage(), "message").startsWith("Usage:").contains(accepted);
			return;
		}
		throw new AssertionError("The installer accepted an option after the operation: " + String.join(" ", args));
	}

	/**
	 * Supplies complete vectors that would expand project authority or bypass the documented grammar.
	 *
	 * @return rejected argument vectors
	 */
	@DataProvider
	public Object[][] rejectedArguments()
	{
		return new Object[][]
		{
			{new String[]{}},
			{new String[]{"upgrade-self"}},
			{new String[]{"update", "--harness", "harness-a"}},
			{new String[]{"update", "--harness", "harness-a", "v1.0"}},
			{new String[]{"install", "--harness=harness-a"}},
			{new String[]{"install", "--harness", "harness-a", "--"}},
			{new String[]{"install", "--harness", "harness-a", "--url", "https://untrusted.invalid"}},
			{new String[]{"install", "--harness", "harness-a", "--harness", "harness-a"}},
			{new String[]{"install", "--harness", "", "--harness", "harness-a"}},
			{new String[]{"install", "--harness", "harness-a", "v1.0", "v1.1"}},
			{new String[]{"install", "--harness", "harness-a", "latest", "latest"}},
			{new String[]{"install", "--harness", "harness-a", "../v1.0"}},
			{new String[]{"install", "--harness", "harness-a", "v1.0?source=other"}},
			{new String[]{"install", "--harness", "harness-a", "v1.0", "--home", "/home/a"}},
			{new String[]{"--home", "/home/a", "v1.0", "install", "--harness", "harness-a"}},
			{new String[]{"--home"}},
			{new String[]{"--home", "/home/a"}},
			{new String[]{"--home", "/home/a", "--home", "/home/b", "install", "--harness", "harness-a"}},
			{new String[]{"--home", "/home/a", "status"}},
			{new String[]{"--home", "/home/a", "uninstall", "--harness", "harness-a"}},
			{new String[]{"--create-profile"}},
			{new String[]{"--create-profile", "--create-profile", "install", "--harness", "harness-a"}},
			{new String[]{"--create-profile", "status"}},
			{new String[]{"--create-profile", "uninstall", "--harness", "harness-a"}},
			{new String[]{"--delete-data", "uninstall", "--harness", "harness-a"}},
			{new String[]{"--json"}},
			{new String[]{"--json", "--json", "install", "--harness", "harness-a"}},
			{new String[]{"--json", "install", "--harness", "harness-a", "--json"}},
			{new String[]{"install", "--harness", "harness-a", "--json", "--json"}},
			{new String[]{"install", "--harness", "harness-a", "--json", "v1.0"}},
			{new String[]{"status", "--json", "--harness", "harness-a"}},
			{new String[]{"status", "--verbose", "--json", "--json"}},
			{new String[]{"uninstall", "--json", "--harness", "harness-a"}},
			{new String[]{"uninstall", "--harness", "harness-a", "--json", "--delete-data"}},
			{new String[]{"--home", "--json"}},
			{new String[]{"rollback", "--harness", "harness-a"}},
			{new String[]{"rollback", "--harness", "harness-a", "v1.0"}},
			{new String[]{"status", "extra"}},
			{new String[]{"setup", "--harness", "harness-a", "--home", "/home/a"}},
			{new String[]{"install"}},
			{new String[]{"install", "--harness", "other"}},
			{new String[]{"install", "--harness", "harness-a", "--home"}},
			{new String[]{"--home", "/home/a", "install", "--harness", "harness-a", "extra"}},
			{new String[]{"--home", "/home/a", "install", "--harness", "harness-a", "--plugin", "/plugin"}},
			{new String[]{"install", "--harness", "harness-a", "--plugin", "/plugin"}},
			{new String[]{"--home", "/home/a", "install", "--harness", "harness-a", "--delete-data"}},
			{new String[]{"uninstall", "--harness", "other"}},
			{new String[]{"uninstall", "--delete-data", "--harness", "harness-a"}},
			{new String[]{"uninstall", "--harness", "harness-a", "--delete-data", "--delete-data"}},
			{new String[]{"uninstall", "--harness", "harness-a", "extra"}},
			{new String[]{"install", "--harness", "harness-a", "--delete-data"}},
			{new String[]{"status", "--delete-data"}},
			{new String[]{"install", "--harness", "harness-a", "--create-profile"}},
			{new String[]{"install", "--harness", "harness-a", "--create-profile", "v1.0"}},
			{new String[]{"install", "--harness", "harness-a", "--create-profile", "--home", "/home/a"}},
			{new String[]{"install", "--harness", "harness-a", "--create-profile", "--create-profile"}},
			{new String[]{"install", "--harness", "harness-a", "--home", "/home/a"}},
			{new String[]{"install", "--harness", "harness-a", "--home", "/home/a", "v1.0"}},
			{new String[]{"uninstall", "--harness", "harness-a", "--create-profile"}},
			{new String[]{"status", "--create-profile"}},
			{new String[]{"--verbose", "status"}},
			{new String[]{"status", "--verbose", "--verbose"}},
			{new String[]{"status", "--verbose", "--harness", "harness-a"}},
			{new String[]{"status", "--harness", "harness-a", "--verbose", "extra"}},
			{new String[]{"install", "--harness", "harness-a", "--verbose"}},
			{new String[]{"uninstall", "--verbose"}},
			{new String[]{"uninstall", "--harness", "harness-a", "--verbose"}}
		};
	}

	/**
	 * Unsupported vectors fail before a command can select paths or perform an operation.
	 *
	 * @param args the unsupported argument vector
	 */
	@Test(dataProvider = "rejectedArguments", expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "Usage:.*")
	public void refusesUnsupportedArguments(String[] args)
	{
		Arguments.parse(args, Set.of("harness-a"));
	}

	/**
	 * The usage diagnostic names only the operations that the installer performs.
	 */
	@Test
	public void usageNamesOnlySupportedOperations()
	{
		try
		{
			Arguments.parse(new String[]{"rollback", "--harness", "harness-a"}, Set.of("harness-a"));
		}
		catch (IllegalArgumentException e)
		{
			requireThat(e.getMessage(), "message").startsWith("Usage: installer ").contains(" install --harness").
				contains("installer status [--harness <id>] [--verbose]").contains("installer uninstall").
				doesNotContain("rollback").doesNotContain("setup").doesNotContain("update");
			return;
		}
		throw new AssertionError("rollback was accepted");
	}
}
