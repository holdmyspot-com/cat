package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.LaunchRoute;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.policy.PolicyBoundFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	 * The options of a launch that a session launcher or a run's runner started.
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
	 * Verifies that a launch that names a policy bound serves its session in place with that bound, though the
	 * environment names the harness of the plugin, as it does for a runner that a session launcher started.
	 */
	@Test
	public void servesBoundLaunch()
	{
		LaunchRoute route = LaunchRoute.decide("harness-a", "harness-a", Set.of("harness-a"),
			BOUND_LAUNCH.toArray(String[]::new), Map.of());

		requireThat(route, "route").isEqualTo(new LaunchRoute.Bounded());
	}

	/**
	 * Verifies that a launch from a command that the plugin's own harness runs goes to that harness's session
	 * launcher.
	 */
	@Test
	public void sendsOwnHarnessToLauncher()
	{
		LaunchRoute route = LaunchRoute.decide("harness-a", "harness-a", Set.of("harness-a"),
			LAUNCH.toArray(String[]::new), Map.of());

		requireThat(route, "route").isEqualTo(new LaunchRoute.Launcher());
	}

	/**
	 * Verifies that when the environment names both harnesses, the plugin's own harness wins and the launch goes to
	 * its session launcher.
	 */
	@Test
	public void ownHarnessWinsOverOther()
	{
		LaunchRoute route = LaunchRoute.decide("harness-a", "harness-a", Set.of("harness-b", "harness-a"),
			LAUNCH.toArray(String[]::new), Map.of());

		requireThat(route, "route").isEqualTo(new LaunchRoute.Launcher());
	}

	/**
	 * Verifies that a launch from a command that only the other harness runs is refused, naming that harness and the
	 * plugin to use, since this plugin cannot read that harness's sandbox policy.
	 */
	@Test
	public void refusesOtherHarness()
	{
		LaunchRoute route = LaunchRoute.decide("harness-b", "harness-a", Set.of("harness-b"),
			LAUNCH.toArray(String[]::new), Map.of());

		requireThat(route, "route").isEqualTo(new LaunchRoute.Refused("CAT cannot start the session: harness-b " +
			"runs this command, and CAT's harness-a plugin cannot read a harness-b session's sandbox policy. Run the " +
			"runner of CAT's harness-b plugin instead."));
	}

	/**
	 * Verifies that a launch from a command that no harness runs starts its session in place.
	 */
	@Test
	public void startsUnmarkedLaunchInPlace()
	{
		LaunchRoute route = LaunchRoute.decide("harness-a", "harness-a", Set.of(),
			LAUNCH.toArray(String[]::new), Map.of("PATH", "/bin"));

		requireThat(route, "route").isEqualTo(new LaunchRoute.InPlace());
	}
}
