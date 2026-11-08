package com.holdmyspot.cat.codex.runner;

import com.holdmyspot.cat.codex.policy.CodexCommandSession;
import com.holdmyspot.cat.codex.policy.CodexPolicyReader;
import com.holdmyspot.cat.codex.policy.CodexRollout;
import com.holdmyspot.cat.codex.policy.CodexSandbox;
import com.holdmyspot.cat.codex.policy.CodexSession;
import com.holdmyspot.cat.codex.policy.CodexSystemFiles;
import com.holdmyspot.cat.codex.policy.PermissionEntry;
import com.holdmyspot.cat.common.runner.DirectLaunch;
import com.holdmyspot.cat.common.runner.launcher.ParentPolicySource;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
	 * its sandbox while the session's permissions deny a read, and names the profile and each denied entry.
	 * Otherwise, it names the causes that keep the launcher sandboxed and the path to run.
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
		Optional<CodexSandbox> turn;
		Optional<String> profile;
		try
		{
			CodexCommandSession found = CodexCommandSession.find(environment, accountHome, List.of());
			turn = CodexRollout.latestTurn(found.rollout(), environment, slashTmp);
			profile = CodexRollout.latestProfile(found.rollout());
		}
		catch (IOException | PolicyRefusal _)
		{
			turn = Optional.empty();
			profile = Optional.empty();
		}
		List<Path> denied = turn.map(CodexSandbox::entries).orElse(List.of()).stream().
			filter(entry -> entry.access() == PermissionEntry.Access.DENY).map(PermissionEntry::path).toList();
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
		return "CAT cannot start the session: this Codex session runs the launcher inside its sandbox, where no " +
			"session that a harness sandboxes can start. Codex runs the launcher outside its sandbox only through " +
			"the rule that CAT's install adds for " + launcher + ", and only for a command that is that absolute " +
			"path followed by the launcher's options, with nothing in front of it and no pipe or redirection. A " +
			"session that started before that install has no such rule. Run " + launcher + " as such a command, " +
			"or start a new Codex session and run it there.";
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
