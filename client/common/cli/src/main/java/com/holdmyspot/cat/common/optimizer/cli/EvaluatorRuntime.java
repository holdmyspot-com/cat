package com.holdmyspot.cat.common.optimizer.cli;

import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;

import java.io.IOException;
import java.util.Objects;

/**
 * Describes the installed evaluator's measurement and native evidence environment.
 *
 * @param harnessVersion     the version of the installed native harness
 * @param environmentVersion the evaluator's native session environment version
 * @param environmentRelease the CAT release that introduced that environment
 * @param judgeVersion       the native evidence reader and judge version
 * @param reportsExitStatus  whether the evaluator reports shell command exit status
 * @param login              the evaluator's credential classification, which contains no secret values
 */
public record EvaluatorRuntime(String harnessVersion, int environmentVersion, String environmentRelease,
	int judgeVersion, boolean reportsExitStatus, HostLogin login)
{
	/**
	 * Constructs the runtime identity.
	 *
	 * @throws NullPointerException     if an argument is null
	 * @throws IllegalArgumentException if a version is negative, or a version or the environment release is blank
	 */
	public EvaluatorRuntime
	{
		Objects.requireNonNull(environmentRelease, "environmentRelease");
		Objects.requireNonNull(login, "login");
		Objects.requireNonNull(harnessVersion, "harnessVersion");
		if (environmentVersion < 0 || judgeVersion < 0 || environmentRelease.isBlank() || harnessVersion.isBlank())
			throw new IllegalArgumentException("Invalid evaluator runtime identity");
	}

	/**
	 * Reads the evaluator's identity without starting a trial.
	 *
	 * @param harnessVersion the native harness version
	 * @param sessions       the evaluator's native adapter
	 * @return its runtime identity
	 * @throws IOException if the login cannot be classified
	 */
	static EvaluatorRuntime read(String harnessVersion, SessionPort sessions) throws IOException
	{
		return new EvaluatorRuntime(harnessVersion, sessions.sessionEnvironmentVersion(),
			sessions.sessionEnvironmentRelease(), sessions.judgeVersion(), sessions.reportsExitStatus(),
			sessions.hostLogin());
	}
}
