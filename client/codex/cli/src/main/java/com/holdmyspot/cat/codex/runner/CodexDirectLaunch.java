package com.holdmyspot.cat.codex.runner;

import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.codex.policy.CodexCommandSession;
import com.holdmyspot.cat.codex.policy.CodexPolicyReader;
import com.holdmyspot.cat.codex.policy.CodexRollout;
import com.holdmyspot.cat.codex.policy.CodexSession;
import com.holdmyspot.cat.codex.policy.CodexSystemFiles;
import com.holdmyspot.cat.common.runner.DirectLaunch;
import com.holdmyspot.cat.common.runner.LauncherSpelling;
import com.holdmyspot.cat.common.runner.launcher.ParentPolicySource;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Identifies the Codex session that runs one of the Codex plugin's runner launchers, which Codex runs outside the
 * session's sandbox, and says why a launcher that the session runs inside its sandbox is refused.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class CodexDirectLaunch
{
	/**
	 * The name of the harness, as its users know it.
	 */
	private static final String HARNESS_NAME = "Codex";
	/**
	 * Writes a launcher's path as the JSON string that Codex's rules hold.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Prevents instantiation.
	 */
	private CodexDirectLaunch()
	{
	}

	/**
	 * Returns the finder of the Codex session that runs this process.
	 *
	 * @param environment the process's environment
	 * @return the finder
	 * @throws NullPointerException if {@code environment} is null
	 */
	public static DirectLaunch.SessionFinder sessions(Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		return () -> sessions(environment, accountHome(), HarnessProcesses.ancestorsOf(ProcessHandle.current()),
			new CodexPolicyReader(systemFiles())).find();
	}

	/**
	 * Returns the finder of the Codex session that runs a command, as {@link CodexCommandSession} finds it, whose
	 * policy a {@link CodexPolicyReader} reads.
	 *
	 * @param environment the command's environment
	 * @param accountHome the user's home folder in the account database
	 * @param ancestors   the processes that run the command, from its parent up
	 * @param reader      reads the session's policy
	 * @return the finder
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static DirectLaunch.SessionFinder sessions(Map<String, String> environment, Path accountHome,
		List<ProcessDescription> ancestors, CodexPolicyReader reader)
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(accountHome, "accountHome").isNotNull();
		requireThat(ancestors, "ancestors").isNotNull();
		requireThat(reader, "reader").isNotNull();
		Map<String, String> copy = Map.copyOf(environment);
		List<ProcessDescription> chain = List.copyOf(ancestors);
		return () ->
		{
			CodexCommandSession found = CodexCommandSession.find(copy, accountHome, chain);
			return new DirectLaunch.Session(policySource(reader, found.session()), found.rollout(), found.project(),
				found.askingThread());
		};
	}

	/**
	 * Returns the sandbox policy source of a Codex session that runs a command.
	 *
	 * @param reader  reads the session's policy
	 * @param session the session
	 * @return the source
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static ParentPolicySource policySource(CodexPolicyReader reader, CodexSession session)
	{
		requireThat(reader, "reader").isNotNull();
		requireThat(session, "session").isNotNull();
		return new ParentPolicySource()
		{
			@Override
			public String harnessName()
			{
				return HARNESS_NAME;
			}

			@Override
			public String permissionEntries(List<Path> writes, Set<String> hosts)
			{
				return CodexSessionLauncherCli.permissionEntries(writes, hosts);
			}

			@Override
			public ParentPolicy read(Optional<String> askingThread) throws IOException, PolicyRefusal
			{
				requireThat(askingThread, "askingThread").isNotNull();
				return reader.read(session, askingThread);
			}
		};
	}

	/**
	 * Returns why a runner launcher that this process's Codex session runs inside its sandbox is refused.
	 *
	 * @param launcher    the absolute path of the launcher
	 * @param environment the process's environment
	 * @return the reason
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static String sandboxed(Path launcher, Map<String, String> environment)
	{
		return sandboxed(launcher, environment, accountHome(), systemFiles().slashTmp());
	}

	/**
	 * Returns why a runner launcher that a Codex session runs inside its sandbox is refused. When the latest turn of
	 * the thread that runs it denies any read, the reason says that Codex keeps a command that a rule allows inside
	 * its sandbox while the session's permissions deny a read, and names the profile and each denied path and
	 * pattern. Otherwise, it names each cause that it finds: no rule in Codex's rules folder names the launcher, or the
	 * thread's latest command that names the launcher's file name names it by another path. When it finds neither, it
	 * lists the causes that keep the launcher sandboxed. Each reason names the path to run.
	 *
	 * @param launcher    the absolute path of the launcher
	 * @param environment the command's environment
	 * @param accountHome the user's home folder in the account database
	 * @param slashTmp    the folder that Codex's {@code slash_tmp} special path names, or {@code null} if the machine
	 *                    has none
	 * @return the reason
	 * @throws NullPointerException if {@code launcher}, {@code environment} or {@code accountHome} are null
	 */
	public static String sandboxed(Path launcher, Map<String, String> environment, Path accountHome, Path slashTmp)
	{
		requireThat(launcher, "launcher").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(accountHome, "accountHome").isNotNull();
		List<String> denied = List.of();
		Optional<String> profile = Optional.empty();
		Optional<String> command = Optional.empty();
		try
		{
			CodexCommandSession found = CodexCommandSession.find(environment, accountHome, List.of());
			command = CodexRollout.latestCommandContaining(found.rollout(), launcher.getFileName().toString());
			denied = CodexRollout.latestDeniedReads(found.rollout(), environment, slashTmp);
			profile = CodexRollout.latestProfile(found.rollout());
		}
		catch (IOException | PolicyRefusal _)
		{
			// The thread's rollout cannot be read, so its permissions and commands name no cause
		}
		if (!denied.isEmpty())
		{
			String named = profile.map(name -> "the permissions profile " + name).
				orElse("the session's permissions profile");
			return "CAT cannot start the session: Codex runs a command that a rule allows outside its sandbox only " +
				"when the session's filesystem permissions deny no reads (openai/codex#38318), so it ran the " +
				"launcher inside this session's sandbox, because " + named + " denies reading " +
				denied.stream().map(path -> "\"" + path + "\"").collect(Collectors.joining(", ")) + ". Remove " +
				"those entries from the profile and start a new Codex session, or start the session from a " +
				"terminal by running " + launcher + " there.";
		}
		String start = "CAT cannot start the session: this Codex session runs the launcher inside its sandbox, where " +
			"no session that a harness sandboxes can start. ";
		List<String> causes = new ArrayList<>();
		missingRule(launcher, environment, accountHome).ifPresent(causes::add);
		command.flatMap(text -> LauncherSpelling.otherSpelling(text, launcher)).ifPresent(word -> causes.add(
			"The command ran the launcher as " + word + ", but the rule that CAT's install adds matches only a " +
			"command whose first word is " + launcher + ", spelled exactly so. Run " + launcher + " followed by " +
			"the launcher's options, with nothing in front of it and no pipe or redirection."));
		if (!causes.isEmpty())
			return start + String.join(" ", causes);
		return start + "Codex runs the launcher outside its sandbox only through the rule that CAT's install adds " +
			"for " + launcher + ", and only for a command that is that absolute path followed by the launcher's " +
			"options, with nothing in front of it and no pipe or redirection. A session that started before that " +
			"install has no such rule. Run " + launcher + " as such a command, or start a new Codex session and run " +
			"it there.";
	}

	/**
	 * Returns the cause of a sandboxed launch when no rule in Codex's rules folder names a launcher.
	 *
	 * @param launcher    the absolute path of the launcher
	 * @param environment the command's environment
	 * @param accountHome the user's home folder in the account database
	 * @return the cause and its fix, or empty if a rule names the launcher or Codex's rules cannot be read
	 */
	private static Optional<String> missingRule(Path launcher, Map<String, String> environment, Path accountHome)
	{
		Path rules;
		String quoted;
		try
		{
			rules = CodexInstallation.resolveHome(environment, accountHome, Path.of("").toAbsolutePath()).
				resolve("rules");
			quoted = JSON.writeValueAsString(launcher.toString());
			if (Files.isDirectory(rules))
			{
				try (Stream<Path> files = Files.list(rules))
				{
					for (Path file : files.filter(file -> file.getFileName().toString().endsWith(".rules")).toList())
					{
						if (Files.readString(file).contains(quoted))
							return Optional.empty();
					}
				}
			}
		}
		catch (IOException | UncheckedIOException | JacksonException _)
		{
			return Optional.empty();
		}
		return Optional.of("No rule in " + rules + " names " + launcher + ", so Codex has no rule that runs it " +
			"outside its sandbox. Run CAT's install again, then start a new Codex session, which reads the new rule, " +
			"and run " + launcher + " there.");
	}

	/**
	 * Returns the user's home folder in the account database.
	 *
	 * @return the folder
	 */
	private static Path accountHome()
	{
		return Path.of(System.getProperty("user.home"));
	}

	/**
	 * Returns the machine-wide files that Codex reads on this machine.
	 *
	 * @return the files
	 */
	private static CodexSystemFiles systemFiles()
	{
		return CodexSystemFiles.ofThisMachine(System.getProperty("user.name", ""));
	}
}
