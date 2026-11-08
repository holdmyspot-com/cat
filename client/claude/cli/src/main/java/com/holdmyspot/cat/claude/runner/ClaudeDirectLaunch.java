package com.holdmyspot.cat.claude.runner;

import com.holdmyspot.cat.claude.policy.ClaudeCommandSession;
import com.holdmyspot.cat.claude.policy.ClaudePolicyReader;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.DirectLaunch;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Identifies the Claude Code session that runs one of the Claude plugin's runner launchers, which Claude Code runs
 * outside the session's sandbox, and says why a launcher that the session runs inside its sandbox is refused.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class ClaudeDirectLaunch
{
	/**
	 * Prevents instantiation.
	 */
	private ClaudeDirectLaunch()
	{
	}

	/**
	 * Returns the finder of the Claude Code session that runs this process.
	 *
	 * @param environment the process's environment
	 * @return the finder
	 * @throws NullPointerException if {@code environment} is null
	 */
	public static DirectLaunch.SessionFinder sessions(Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		String userName = System.getProperty("user.name", "");
		return () -> sessions(environment, Platform.current().homeDirectory(environment), userName,
			HarnessProcesses.system(), HarnessProcesses.ancestorsOf(ProcessHandle.current()),
			JsonMapper.builder().build()).find();
	}

	/**
	 * Returns the finder of the Claude Code session that runs a command, as {@link ClaudeCommandSession} finds it,
	 * whose policy {@link ClaudePolicyReader} reads.
	 *
	 * @param environment the command's environment
	 * @param userHome    the user's absolute home folder
	 * @param userName    the user's login name
	 * @param processes   describes the session's Claude Code process
	 * @param ancestors   the processes that run the command, from its parent up
	 * @param json        parses the session's transcript
	 * @return the finder
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static DirectLaunch.SessionFinder sessions(Map<String, String> environment, Path userHome,
		String userName, HarnessProcesses processes, List<ProcessDescription> ancestors, JsonMapper json)
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(userHome, "userHome").isNotNull();
		requireThat(userName, "userName").isNotNull();
		requireThat(processes, "processes").isNotNull();
		requireThat(ancestors, "ancestors").isNotNull();
		requireThat(json, "json").isNotNull();
		Map<String, String> copy = Map.copyOf(environment);
		List<ProcessDescription> chain = List.copyOf(ancestors);
		return () ->
		{
			ClaudeCommandSession found = ClaudeCommandSession.find(copy, userHome, userName, chain, json);
			return new DirectLaunch.Session(ClaudeSessionLauncherCli.policySource(new ClaudePolicyReader(processes),
				found::session), found.transcript(), found.session().projectDirectory(), Optional.empty());
		};
	}

	/**
	 * Returns why a runner launcher that a Claude Code session runs inside its sandbox is refused.
	 *
	 * @param launcher the absolute path of the launcher
	 * @return the reason, which names the causes that keep the launcher sandboxed and the path to run
	 * @throws NullPointerException if {@code launcher} is null
	 */
	public static String sandboxed(Path launcher)
	{
		requireThat(launcher, "launcher").isNotNull();
		return "CAT cannot start the session: this Claude Code session runs the launcher inside its sandbox, where " +
			"no session that a harness sandboxes can start. Claude Code runs the launcher outside its sandbox only " +
			"through the sandbox exception that CAT's install adds for " + launcher + ", and only for a command " +
			"that is that absolute path followed by the launcher's options, with nothing in front of it and no pipe " +
			"or redirection. A session that started before that install has no such exception, and a session in " +
			"dontAsk mode keeps the command sandboxed. Run " + launcher + " as such a command, or start a new " +
			"session that is not in dontAsk mode and run it there.";
	}
}
