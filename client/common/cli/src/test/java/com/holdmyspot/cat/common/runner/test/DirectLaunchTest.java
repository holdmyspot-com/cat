package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.TestToolScope;
import com.holdmyspot.cat.common.runner.DirectLaunch;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.InvalidArgumentsException;
import com.holdmyspot.cat.common.runner.launcher.ChildNeedsSource;
import com.holdmyspot.cat.common.runner.launcher.ParentPolicySource;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyBoundFile;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that a runner launcher that a harness session runs checks, before it starts anything, that it runs outside the
 * session's sandbox, which session runs it, that session's policy, and the paths that the launch names, and then
 * bounds the new session by that policy, through {@link DirectLaunch}.
 */
public final class DirectLaunchTest
{
	/**
	 * The reason that a launcher inside the session's sandbox gives.
	 */
	private static final String SANDBOXED = "the launcher runs inside the sandbox";
	/**
	 * Needs that no check-failing launch may reach.
	 */
	private static final ChildNeedsSource NO_NEEDS = (_, _, _, _) ->
	{
		throw new AssertionError("No needs are read");
	};

	/**
	 * Creates the test.
	 */
	public DirectLaunchTest()
	{
	}

	/**
	 * Verifies that a launch that the session's policy allows is bounded: the launcher appends the session's project
	 * folder, which the launch does not name, and the file of the bound, which lies inside the launch's run root and
	 * lets the new session write only its run folder.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void boundsAllowedLaunch() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path input = Files.writeString(fixture.project.resolve("turns.jsonl"), "");
			Path output = fixture.project.resolve("transcript.jsonl");
			List<String> args = List.of("--input", input.toString(), "--output", output.toString(), "--run-root",
				fixture.runs.toString());
			List<Path> runFolders = new ArrayList<>();

			List<String> bounded = fixture.bound(args, fixture.session(Optional.empty()),
				(_, runFolder, _, _) ->
				{
					runFolders.add(runFolder);
					return new ChildNeeds(runFolder, List.of(), List.of(), Set.of());
				});

			List<String> expected = new ArrayList<>(args);
			expected.addAll(List.of("--project", fixture.project.toString(), PolicyBoundFile.OPTION));
			requireThat(bounded.subList(0, expected.size()), "bounded").isEqualTo(expected);
			requireThat(bounded.size(), "boundedSize").isEqualTo(expected.size() + 1);
			Path file = Path.of(bounded.getLast());
			requireThat(file.startsWith(fixture.runs), "insideRunRoot").isTrue();
			ChildPolicy child = PolicyBoundFile.read(JsonMapper.builder().build(), file);
			requireThat(runFolders.size(), "runFolders").isEqualTo(1);
			requireThat(child.writeRoots(), "writeRoots").isEqualTo(List.of(runFolders.getFirst()));
		}
	}

	/**
	 * Verifies that a launcher that cannot write its plugin's folder, as inside the session's sandbox, is refused with
	 * the sandbox's reason before it identifies the session.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesLauncherInsideSandbox() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path plugin = fixture.root.resolve("missing plugin");

			SessionRefusedException refusal = refusal(() -> DirectLaunch.boundArguments("harness-b",
				new String[]{"--run-root", fixture.runs.toString()}, plugin, () ->
				{
					throw new AssertionError("No session is identified");
				}, _ -> new TestToolScope(), NO_NEEDS, () -> SANDBOXED, fixture.cleanup));

			requireThat(refusal.getMessage(), "message").isEqualTo(SANDBOXED);
		}
	}

	/**
	 * Verifies that a launch whose sources identify no session safely is refused with the reason and its fix.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesUnidentifiedSession() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			SessionRefusedException refusal = refusal(() -> DirectLaunch.boundArguments("harness-b",
				new String[]{"--run-root", fixture.runs.toString()}, fixture.plugin, () ->
				{
					throw new PolicyRefusal("no transcript of the session names it", "Start a new session");
				}, _ -> new TestToolScope(), NO_NEEDS, () -> SANDBOXED, fixture.cleanup));

			requireThat(refusal.getMessage(), "message").contains("no transcript of the session names it").
				contains("Start a new session");
		}
	}

	/**
	 * Verifies that a launch is refused when the session's policy lets its commands write the transcript by which
	 * the launcher found the session, since the commands could then choose the session that the launcher finds.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesWritableTranscript() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.writeRoots.add(fixture.transcript.getParent());

			SessionRefusedException refusal = refusal(() -> fixture.bound(List.of("--run-root",
				fixture.runs.toString()), fixture.session(Optional.empty()), NO_NEEDS));

			requireThat(refusal.getMessage(), "message").contains(fixture.transcript.toString());
		}
	}

	/**
	 * Verifies that a launch is refused when the session's policy lets its commands write the launcher's plugin,
	 * since the commands could then change the launcher.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesWritablePlugin() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.writeRoots.add(fixture.plugin);

			SessionRefusedException refusal = refusal(() -> fixture.bound(List.of("--run-root",
				fixture.runs.toString()), fixture.session(Optional.empty()), NO_NEEDS));

			requireThat(refusal.getMessage(), "message").contains(fixture.plugin.toString());
		}
	}

	/**
	 * Verifies that a launch that names a path that the session's policy does not let its commands read is refused,
	 * naming the path and its option, before the launcher reads what the new session needs.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesUnreadablePath() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path secret = Files.writeString(fixture.secrets.resolve("turns.jsonl"), "");

			SessionRefusedException refusal = refusal(() -> fixture.bound(List.of("--input", secret.toString(),
				"--run-root", fixture.runs.toString()), fixture.session(Optional.empty()), NO_NEEDS));

			requireThat(refusal.getMessage(), "message").contains("does not let its commands read " + secret +
				", which --input names");
		}
	}

	/**
	 * Verifies that a launch that names no project folder is refused when the session's policy does not let its
	 * commands read the session's own project folder, which the launch then takes.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesUnreadableDefaultProject() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.readDenials.add(fixture.project);

			SessionRefusedException refusal = refusal(() -> fixture.bound(List.of("--run-root",
				fixture.runs.toString()), fixture.session(Optional.empty()), NO_NEEDS));

			requireThat(refusal.getMessage(), "message").contains("does not let its commands read " +
				fixture.project + ", which --project names");
		}
	}

	/**
	 * Verifies that a launch that names paths that the session's policy does not let its commands write is refused,
	 * naming the path, its option and the settings entry that allows it, for an output file, a conversation folder and
	 * a run root.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesUnwritablePaths() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path elsewhere = Files.createDirectory(fixture.root.resolve("elsewhere"));
			Map<String, List<String>> launches = Map.of(
				"--output", List.of("--output", elsewhere.resolve("out.jsonl").toString(), "--run-root",
					fixture.runs.toString()),
				"--conversation", List.of("--conversation", elsewhere.toString(), "--run-root",
					fixture.runs.toString()),
				"--run-root", List.of("--run-root", elsewhere.resolve("runs").toString()));
			for (Map.Entry<String, List<String>> launch : launches.entrySet())
			{
				SessionRefusedException refusal = refusal(() -> fixture.bound(launch.getValue(),
					fixture.session(Optional.empty()), NO_NEEDS));

				requireThat(refusal.getMessage(), "message").contains("does not let its commands write").
					contains("which " + launch.getKey() + " names").contains("add " + elsewhere);
			}
		}
	}

	/**
	 * Verifies that the paths of a launch from a thread of the session are checked against that thread's policy,
	 * which may be narrower than the main thread's.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void checksPathsAgainstAskingThread() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.threadWriteRoots.put("thread-b", List.of(fixture.runs));
			Path output = fixture.project.resolve("out.jsonl");

			SessionRefusedException refusal = refusal(() -> fixture.bound(List.of("--output", output.toString(),
				"--run-root", fixture.runs.toString()), fixture.session(Optional.of("thread-b")), NO_NEEDS));

			requireThat(refusal.getMessage(), "message").contains("does not let its commands write " + output);
		}
	}

	/**
	 * Verifies that a harness session's launch that names an option that only CAT's own starters pass is refused,
	 * naming the option, and that a launch without one is not.
	 */
	@Test
	public void refusesInternalOptions()
	{
		for (String option : DirectLaunch.INTERNAL_OPTIONS)
		{
			Optional<String> reason = DirectLaunch.internalOptionRefusal(new String[]{"--project", "project", option,
				"value"});

			requireThat(reason.orElseThrow(), "reason").contains("only CAT itself passes " + option);
		}
		requireThat(DirectLaunch.internalOptionRefusal(new String[]{"--project", "project", "--model", "model-a"}),
			"reason").isEmpty();
	}

	/**
	 * Verifies that a harness option that the session names, in any of its forms, never reaches the new session's
	 * command line: the launcher bounds the launch as the session named it, and the runner that serves the bounded
	 * launch refuses the option before it builds a command line.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void passesNoHarnessOption() throws IOException
	{
		for (List<String> option : List.of(List.of("--bypass-sandbox"), List.of("--sandbox-mode", "off"),
			List.of("--sandbox-mode=off"), List.of("-c", "sandbox=off"), List.of("-yc")))
		{
			try (Fixture fixture = new Fixture();
				TestToolScope tools = new TestToolScope())
			{
				List<String> args = new ArrayList<>(List.of("--model", "model-a", "--effort", "low", "--run-root",
					fixture.runs.toString()));
				args.addAll(option);
				List<String> bounded = fixture.bound(args, fixture.session(Optional.empty()),
					(_, runFolder, _, _) -> new ChildNeeds(runFolder, List.of(), List.of(), Set.of()));
				requireThat(bounded, "bounded").containsAll(option);

				try
				{
					HarnessRunnerOptions.parse(HarnessRunnerOptions.withArtifact(bounded.toArray(String[]::new),
						fixture.plugin), "harness-b", HarnessTemporaryRoot.from(tools));
				}
				catch (InvalidArgumentsException e)
				{
					requireThat(e.getMessage(), "message").isEqualTo("Unsupported option: " + option.getFirst());
					continue;
				}
				throw new AssertionError("The runner accepted " + option);
			}
		}
	}

	/**
	 * Verifies that a launch whose new session would take the user's home folder as its project is refused before the
	 * launcher bounds it: the project is the folder that {@code --project} names, resolved against the working
	 * directory, or else the project folder that the session's transcript names, and a project below the home folder
	 * is accepted either way.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesHomeProject() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Map<String, String> environment = Map.of(Platform.current().homeVariable(), fixture.root.toString());
			DirectLaunch.Session belowHome = fixture.session(Optional.empty());
			DirectLaunch.Session atHome = new DirectLaunch.Session(belowHome.policy(), fixture.transcript,
				fixture.root, Optional.empty());
			Path elsewhere = fixture.runs;

			SessionRefusedException fromTranscript = refusal(() -> find(DirectLaunch.refusingHomeProject(
				() -> atHome, new String[]{"--model", "model-a"}, elsewhere, environment, Platform.current())));
			SessionRefusedException named = refusal(() -> find(DirectLaunch.refusingHomeProject(() -> belowHome,
				new String[]{"--project", fixture.root.toString()}, elsewhere, environment, Platform.current())));

			for (SessionRefusedException refusal : List.of(fromTranscript, named))
				requireThat(refusal.getMessage(), "message").contains("cannot be a CAT project");
			requireThat(find(DirectLaunch.refusingHomeProject(() -> belowHome, new String[]{"--model", "model-a"},
				fixture.root, environment, Platform.current())), "fromTranscript").isEqualTo(belowHome);
			requireThat(find(DirectLaunch.refusingHomeProject(() -> atHome, new String[]{"--project", "project"},
				fixture.root, environment, Platform.current())), "named").isEqualTo(atHome);
		}
	}

	/**
	 * Verifies that a command that a harness session runs outside its sandbox is refused, naming its option and the
	 * path, when it would read a path that the session's sandbox denies, and is not refused for a path that the
	 * session may read.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesUnreadableCommandPath() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			DirectLaunch.Session session = fixture.session(Optional.empty());
			Path denied = fixture.secrets.resolve("artifact");

			SessionRefusedException refusal = refusal(() -> DirectLaunch.requireReadable("harness-a",
				"--verify-artifact", denied, fixture.plugin, () -> session, () -> SANDBOXED));

			requireThat(refusal.getMessage(), "message").contains("--verify-artifact").
				contains("does not let its commands read " + denied);
			DirectLaunch.requireReadable("harness-a", "--verify-artifact", fixture.project.resolve("artifact"),
				fixture.plugin, () -> session, () -> SANDBOXED);
		}
	}

	/**
	 * Returns the session that a finder finds.
	 *
	 * @param sessions the finder
	 * @return the session
	 * @throws IOException    if the finder refuses the launch or cannot read its sources
	 * @throws AssertionError if the finder identifies no session
	 */
	private static DirectLaunch.Session find(DirectLaunch.SessionFinder sessions) throws IOException
	{
		try
		{
			return sessions.find();
		}
		catch (PolicyRefusal e)
		{
			throw new AssertionError("The finder identified no session", e);
		}
	}

	/**
	 * Returns the refusal of a launch.
	 *
	 * @param launch bounds the launch
	 * @return the refusal
	 * @throws AssertionError if the launch is not refused
	 */
	private static SessionRefusedException refusal(Launch launch)
	{
		try
		{
			launch.run();
		}
		catch (SessionRefusedException e)
		{
			return e;
		}
		catch (IOException e)
		{
			throw new AssertionError("The launch failed instead of being refused", e);
		}
		throw new AssertionError("The launch was not refused");
	}

	/**
	 * A launch that may be refused.
	 */
	@FunctionalInterface
	private interface Launch
	{
		/**
		 * Bounds the launch.
		 *
		 * @throws IOException if the launch is refused or fails
		 */
		void run() throws IOException;
	}

	/**
	 * The folders of a session that runs a launcher, and its sandbox policy, which lets the session's commands write
	 * its project folder and its runs' folder and denies them a folder of secrets.
	 */
	private static final class Fixture implements AutoCloseable
	{
		/**
		 * Removes the fixture's folders.
		 */
		final TemporaryDirectories cleanup = new TemporaryDirectories();
		/**
		 * The fixture's real root folder.
		 */
		final Path root;
		/**
		 * The plugin that holds the launcher.
		 */
		final Path plugin;
		/**
		 * The session's transcript.
		 */
		final Path transcript;
		/**
		 * The session's project folder.
		 */
		final Path project;
		/**
		 * The folder of the launches' run roots.
		 */
		final Path runs;
		/**
		 * The folder that the session's commands may not read.
		 */
		final Path secrets;
		/**
		 * The folders that the main thread's commands may write.
		 */
		final List<Path> writeRoots = new ArrayList<>();
		/**
		 * The folders that the commands may not read.
		 */
		final List<Path> readDenials = new ArrayList<>();
		/**
		 * The folders that each other thread's commands may write.
		 */
		final Map<String, List<Path>> threadWriteRoots = new java.util.HashMap<>();

		/**
		 * Creates the fixture.
		 *
		 * @throws IOException if the folders cannot be created
		 */
		Fixture() throws IOException
		{
			try (TestJvmScope jvm = new TestJvmScope())
			{
				root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "direct-launch-")).
					toRealPath();
			}
			plugin = Files.createDirectory(root.resolve("plugin"));
			Path home = Files.createDirectory(root.resolve("home"));
			transcript = Files.writeString(home.resolve("session.jsonl"), "");
			project = Files.createDirectory(root.resolve("project"));
			runs = Files.createDirectory(root.resolve("runs"));
			secrets = Files.createDirectory(root.resolve("secrets"));
			writeRoots.addAll(List.of(project, runs));
			readDenials.add(secrets);
		}

		/**
		 * Returns the session that runs the launcher.
		 *
		 * @param askingThread the thread that runs the launcher, or empty for the main thread
		 * @return the session
		 */
		DirectLaunch.Session session(Optional<String> askingThread)
		{
			ParentPolicySource source = new ParentPolicySource()
			{
				@Override
				public String harnessName()
				{
					return "Harness A";
				}

				@Override
				public String permissionEntries(List<Path> writes, Set<String> hosts)
				{
					return "add " + writes.getFirst() + " to the settings";
				}

				@Override
				public ParentPolicy read(Optional<String> thread)
				{
					List<Path> writes = thread.map(threadWriteRoots::get).orElse(writeRoots);
					return new ParentPolicy(true, writes, List.of(), readDenials, List.of(), HostAccess.ALL, false,
						List.of());
				}
			};
			return new DirectLaunch.Session(source, transcript, project, askingThread);
		}

		/**
		 * Bounds a launch.
		 *
		 * @param args    the launch's options
		 * @param session the session that runs the launcher
		 * @param needs   says what the new session needs
		 * @return the bounded options
		 * @throws IOException if the launch is refused or cannot be bounded
		 */
		List<String> bound(List<String> args, DirectLaunch.Session session, ChildNeedsSource needs)
			throws IOException
		{
			return DirectLaunch.boundArguments("harness-b", args.toArray(String[]::new), plugin, () -> session,
				_ -> new TestToolScope(), needs, () -> SANDBOXED, cleanup);
		}

		@Override
		public void close() throws IOException
		{
			cleanup.close();
		}
	}
}
