package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The options of a runner launch that bound the session that the launch starts in place: where its run is kept, what
 * it may not read, and the file of the extra permissions that its commands need. The runner that serves the launch
 * validates every option; these are read before it starts, so other options are passed over.
 *
 * @param runRoot          the absolute folder that holds the session's run
 * @param deniedReads      the absolute paths that the launch denies the session
 * @param extraPermissions the file of extra permissions that the launch names, or {@code null} if it names none
 */
record InPlaceOptions(Path runRoot, List<Path> deniedReads, Path extraPermissions)
{
	/**
	 * Creates the options.
	 *
	 * @param runRoot          the absolute folder that holds the session's run
	 * @param deniedReads      the absolute paths that the launch denies the session
	 * @param extraPermissions the file of extra permissions that the launch names, or {@code null} if it names none
	 * @throws NullPointerException if {@code runRoot} or {@code deniedReads} are null
	 */
	InPlaceOptions
	{
		requireThat(runRoot, "runRoot").isNotNull();
		deniedReads = List.copyOf(deniedReads);
	}

	/**
	 * Reads the options of a launch.
	 *
	 * @param args          the launch's options, optionally starting with {@code serve}
	 * @param temporaryRoot where the run is kept when the launch names no {@code --run-root}
	 * @return the options
	 * @throws NullPointerException      if any of the arguments are null
	 * @throws InvalidArgumentsException if one of the options lacks its value, a denied read is not absolute, or the
	 *                                   launch names no {@code --run-root} outside a harness session
	 */
	static InPlaceOptions parse(String[] args, HarnessTemporaryRoot temporaryRoot)
	{
		requireThat(args, "args").isNotNull();
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		String runRoot = null;
		List<Path> deniedReads = new ArrayList<>();
		Path extraPermissions = null;
		for (int index = 0; index < args.length; ++index)
		{
			String option = args[index];
			boolean bounds = option.equals("--run-root") || option.equals("--deny-read") ||
				option.equals(ExtraPermissions.OPTION);
			if (!bounds)
				continue;
			if (index + 1 == args.length || args[index + 1].startsWith("--"))
				throw new InvalidArgumentsException("Missing value for " + option);
			index += 1;
			String value = args[index];
			switch (option)
			{
				case "--run-root" -> runRoot = value;
				case "--deny-read" ->
				{
					Path deniedRead = Path.of(value);
					if (!deniedRead.isAbsolute())
						throw new InvalidArgumentsException("Each denied read must be absolute: " + deniedRead);
					deniedReads.add(deniedRead);
				}
				default -> extraPermissions = Path.of(value).toAbsolutePath();
			}
		}
		return new InPlaceOptions(temporaryRoot.runRoot(runRoot), deniedReads, extraPermissions);
	}
}
