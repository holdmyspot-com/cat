package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.LaunchRoute;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.policy.PolicyBoundFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that {@link LaunchRoute#decide} sends each launch where the environment of the command that runs it says.
 */
public final class LaunchRouteTest
{
	/**
	 * The options of a launch that starts a session.
	 */
	private static final List<String> LAUNCH = List.of("--project", "project", "--model", "model-a", "--effort",
		"effort-a");
	/**
	 * The options of a launch that a run's runner started.
	 */
	private static final List<String> BOUND_LAUNCH = List.of("--project", "project", "--model", "model-a",
		"--effort", "effort-a", PolicyBoundFile.OPTION, "/run/policy-bound.json");

	/**
	 * Creates the test.
	 */
	public LaunchRouteTest()
	{
	}

	/**
	 * Verifies that inside a run whose runner lets its session request sessions, every launch becomes a request to
	 * that runner, whichever harness runs the command.
	 */
	@Test
	public void forwardsInsideRunWithRequests()
	{
		// Requirement: R-I11
		Map<String, String> environment = Map.of(HarnessEnvironment.RUN_VARIABLE, HarnessEnvironment.RUN_VALUE,
			SessionRequestAccess.REQUESTS_VARIABLE, "/run/requests");

		LaunchRoute route = LaunchRoute.decide("harness-a", "harness-a", Set.of("harness-a", "harness-b"),
			LAUNCH.toArray(String[]::new), environment);

		requireThat(route, "route").isEqualTo(new LaunchRoute.Forward(Path.of("/run/requests")));
	}

	/**
	 * Verifies that inside a run whose runner lets its session request no sessions, a launch is refused with the
	 * option that lets it, even one that names a policy bound.
	 */
	@Test
	public void refusesInsideRunWithoutRequests()
	{
		// Requirement: R-I11
		Map<String, String> environment = Map.of(HarnessEnvironment.RUN_VARIABLE, HarnessEnvironment.RUN_VALUE);

		for (List<String> launch : List.of(LAUNCH, BOUND_LAUNCH))
		{
			LaunchRoute route = LaunchRoute.decide("harness-a", "harness-a", Set.of("harness-a"),
				launch.toArray(String[]::new), environment);

			requireThat(route, "route").isEqualTo(new LaunchRoute.Refused(HarnessEnvironment.inRunRefusal(
				"harness-a", environment).orElseThrow()));
		}
	}

	/**
	 * Verifies that a launch that names a policy bound, from a command that no harness runs, serves its session in
	 * its own process within that bound, as CAT's own starters, a request server and a run's runner, start it.
	 */
	@Test
	public void servesBoundLaunch()
	{
		// Requirement: R-I11
		LaunchRoute route = LaunchRoute.decide("harness-a", "harness-a", Set.of(),
			BOUND_LAUNCH.toArray(String[]::new), Map.of());

		requireThat(route, "route").isEqualTo(new LaunchRoute.Bounded());
	}

	/**
	 * Verifies that a harness session's launch may not name the option with which a server starts a runner in a
	 * retained run, so that only a server that found the run starts its runner there.
	 */
	@Test
	public void refusesResumeRunOption()
	{
		Optional<String> refusal = LaunchRoute.internalOptionRefusal(new String[]{"--resume-run",
			"5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90"});

		requireThat(refusal.orElse(""), "refusal").contains("--resume-run");
	}

	/**
	 * Verifies that a launch that resumes a session, from a command that no harness runs, is refused, bound or not,
	 * since only a request server or a run's runner keeps an interrupted session's run.
	 */
	@Test
	public void refusesResumeOutsideHarnessSession()
	{
		for (List<String> launch : List.of(LAUNCH, BOUND_LAUNCH))
		{
			List<String> resume = new ArrayList<>(launch);
			resume.addAll(List.of("--resume", "5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90"));

			LaunchRoute route = LaunchRoute.decide("harness-a", "harness-a", Set.of(), resume.toArray(String[]::new),
				Map.of());

			requireThat(route, "route").isInstanceOf(LaunchRoute.Refused.class);
			requireThat(((LaunchRoute.Refused) route).reason(), "reason").contains("--resume");
		}
	}

	/**
	 * Returns each option that only CAT's own starters pass, with the harnesses that run the command.
	 *
	 * @return the option and the harnesses
	 */
	@DataProvider
	public Object[][] internalOptions()
	{
		List<Object[]> cases = new ArrayList<>();
		for (String option : LaunchRoute.INTERNAL_OPTIONS)
		{
			cases.add(new Object[]{option, Set.of("harness-a")});
			cases.add(new Object[]{option, Set.of("harness-b")});
		}
		return cases.toArray(Object[][]::new);
	}

	/**
	 * Verifies that a launch from a command that a harness runs is refused, naming the option, when it names an
	 * option that only CAT's own starters pass, because such an option would let the session choose what bounds the
	 * new session.
	 *
	 * @param option    the option
	 * @param harnesses the harnesses that run the command
	 */
	@Test(dataProvider = "internalOptions")
	public void refusesInternalOptionFromHarness(String option, Set<String> harnesses)
	{
		List<String> launch = new ArrayList<>(LAUNCH);
		launch.addAll(List.of(option, "value"));

		LaunchRoute route = LaunchRoute.decide("harness-a", "harness-a", harnesses, launch.toArray(String[]::new),
			Map.of());

		requireThat(route, "route").isInstanceOf(LaunchRoute.Refused.class);
		requireThat(((LaunchRoute.Refused) route).reason(), "reason").contains("only CAT itself passes " + option).
			contains("without " + option);
	}

	/**
	 * Verifies that a launch from a command that the plugin's own harness runs outside a run goes to the session's
	 * request server.
	 */
	@Test
	public void sendsOwnHarnessToServer()
	{
		// Requirement: R-I11
		LaunchRoute route = LaunchRoute.decide("harness-a", "harness-a", Set.of("harness-a"),
			LAUNCH.toArray(String[]::new), Map.of());

		requireThat(route, "route").isEqualTo(new LaunchRoute.Served());
	}

	/**
	 * Verifies that when the environment names both harnesses, the plugin's own harness wins and the launch goes to the
	 * session's request server.
	 */
	@Test
	public void ownHarnessWinsOverOther()
	{
		// Requirement: R-I11
		LaunchRoute route = LaunchRoute.decide("harness-a", "harness-a", Set.of("harness-b", "harness-a"),
			LAUNCH.toArray(String[]::new), Map.of());

		requireThat(route, "route").isEqualTo(new LaunchRoute.Served());
	}

	/**
	 * Verifies that a launch from a command that only the other harness runs is refused, naming that harness and the
	 * plugin to use, since this plugin's request server serves no session of that harness.
	 */
	@Test
	public void refusesOtherHarness()
	{
		// Requirement: R-I11
		LaunchRoute route = LaunchRoute.decide("harness-b", "harness-a", Set.of("harness-b"),
			LAUNCH.toArray(String[]::new), Map.of());

		requireThat(route, "route").isEqualTo(new LaunchRoute.Refused("CAT cannot start the session: harness-b " +
			"runs this command, and CAT's harness-a plugin holds no request server for a harness-b session. Run the " +
			"runner of CAT's harness-b plugin instead."));
	}

	/**
	 * Verifies that a launch from a command that no harness session runs, such as one in a terminal or a script, is
	 * refused with a message that says to run the launcher from a Claude Code or Codex session.
	 */
	@Test
	public void refusesUnmarkedLaunch()
	{
		// Requirement: R-I11
		LaunchRoute route = LaunchRoute.decide("harness-a", "harness-a", Set.of(),
			LAUNCH.toArray(String[]::new), Map.of("PATH", "/bin"));

		requireThat(route, "route").isEqualTo(new LaunchRoute.Refused("CAT cannot start the session: CAT starts " +
			"sessions only for a Claude Code or Codex session, and none runs this command. Run this command from a " +
			"Claude Code or Codex session."));
	}
}
