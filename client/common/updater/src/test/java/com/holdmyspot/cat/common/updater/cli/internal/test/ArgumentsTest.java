package com.holdmyspot.cat.common.updater.cli.internal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.updater.cli.internal.Arguments;
import com.holdmyspot.cat.common.updater.cli.internal.Arguments.Operation;
import java.nio.file.Path;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests the updater's complete strict argument grammar.
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
	 * Routine updates accept only a selected provider and an optional approved release selector.
	 */
	@Test
	public void parsesRoutineUpdate()
	{
		Arguments args = Arguments.parse(new String[]{"update", "--harness", "harness-a", "v1.2.3"},
			Set.of("harness-a"));
		requireThat(args.operation(), "operation").isEqualTo(Operation.UPDATE);
		requireThat(args.harness(), "harness").isEqualTo("harness-a");
		requireThat(args.tag(), "tag").isEqualTo("v1.2.3");
	}

	/**
	 * Both latest spellings reach the engine as its absent explicit-tag selector.
	 */
	@Test
	public void latestHasNoExplicitTag()
	{
		Arguments omitted = Arguments.parse(new String[]{"update", "--harness", "harness-a"},
			Set.of("harness-a"));
		Arguments latest = Arguments.parse(new String[]{"update", "--harness", "harness-a", "latest"},
			Set.of("harness-a"));
		requireThat(omitted.tag(), "omittedTag").isNull();
		requireThat(latest.tag(), "latestTag").isNull();
	}

	/**
	 * Status without a harness selects the installed providers, while setup retains the explicit absolute home.
	 */
	@Test
	public void parsesStatusAndSetup()
	{
		requireThat(Arguments.parse(new String[]{"status"}, Set.of("harness-a")).harness(), "harness").isEmpty();
		Path home = Path.of(System.getProperty("cat.updater.test.directory")).resolve("harness-home");
		Arguments args = Arguments.parse(new String[]{"setup", "--harness", "harness-a", "--home",
			home.toString()}, Set.of("harness-a"));
		requireThat(args.home(), "home").isEqualTo(home);
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
			{new String[]{"update", "--harness=harness-a"}},
			{new String[]{"update", "--harness", "harness-a", "--"}},
			{new String[]{"update", "--harness", "harness-a", "--url", "https://untrusted.invalid"}},
			{new String[]{"update", "--harness", "harness-a", "--home", "/other"}},
			{new String[]{"update", "--harness", "harness-a", "--harness", "harness-a"}},
			{new String[]{"update", "--harness", "", "--harness", "harness-a"}},
			{new String[]{"update", "--harness", "other"}},
			{new String[]{"update", "--harness", "harness-a", "v1.0", "v1.1"}},
			{new String[]{"update", "--harness", "harness-a", "latest", "latest"}},
			{new String[]{"update", "--harness", "harness-a", "../v1.0"}},
			{new String[]{"update", "--harness", "harness-a", "v1.0?source=other"}},
			{new String[]{"rollback", "--harness", "harness-a", "v1.0"}},
			{new String[]{"status", "extra"}},
			{new String[]{"setup", "--harness", "harness-a"}}
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
}
