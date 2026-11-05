package com.holdmyspot.cat.codex.policy;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A process that started a Codex hook, directly or through other processes.
 *
 * @param executable the path of the process's executable, or an empty string if it cannot be read
 * @param arguments  the process's arguments after the executable, or empty if they cannot be read
 */
public record CodexProcess(String executable, Optional<List<String>> arguments)
{
	/**
	 * Creates a process.
	 *
	 * @param executable the path of the process's executable, or an empty string if it cannot be read
	 * @param arguments  the process's arguments after the executable, or empty if they cannot be read
	 * @throws NullPointerException if any argument is null, or {@code arguments} contains null
	 */
	public CodexProcess
	{
		requireThat(executable, "executable").isNotNull();
		requireThat(arguments, "arguments").isNotNull();
		arguments = arguments.map(List::copyOf);
	}

	/**
	 * Returns the processes that started a process, the nearest first.
	 *
	 * @param process the process
	 * @return the ancestors of {@code process}, starting with its parent
	 * @throws NullPointerException if {@code process} is null
	 */
	public static List<CodexProcess> ancestorsOf(ProcessHandle process)
	{
		requireThat(process, "process").isNotNull();
		List<CodexProcess> ancestors = new ArrayList<>();
		Optional<ProcessHandle> parent = process.parent();
		while (parent.isPresent())
		{
			ProcessHandle.Info info = parent.get().info();
			ancestors.add(new CodexProcess(info.command().orElse(""), info.arguments().map(List::of)));
			parent = parent.get().parent();
		}
		return ancestors;
	}
}
