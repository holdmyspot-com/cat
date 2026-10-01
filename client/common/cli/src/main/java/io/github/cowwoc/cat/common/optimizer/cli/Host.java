package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.execution.SliceSettings;
import io.github.cowwoc.cat.common.optimizer.port.HostInterruptSignal;
import io.github.cowwoc.cat.common.optimizer.port.SessionPort;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * What a harness's {@code main} class resolves from its environment and hands to the core. The core reads no
 * environment of its own.
 * <p>
 * <b>Thread Safety:</b> This class is immutable. Thread safety otherwise depends on its components.
 *
 * @param harness the harness that runs the launcher
 * @param sessions starts nested sessions through the harness's runner
 * @param interrupts reports whether the host user interrupted the host session
 * @param clock the clock
 * @param random the source of campaign identifiers and draw seeds
 * @param json the operation-owned JSON mapper
 * @param directory the launcher's working directory, below the project
 * @param harnessVersion reports the harness's version. It runs only when a command starts a session, so {@code status}
 *   never runs it.
 * @param judgeModel the name of the judge model, or {@code harness-default}
 * @param loaderConfiguration the files, relative to the project, that decide which prompt files the harness loads
 * @param settings the slice's time budget
 * @param ceiling the most nested sessions that may run at once
 */
public record Host(Harness harness, SessionPort sessions, HostInterruptSignal interrupts, Clock clock,
	RandomGenerator random, JsonMapper json, Path directory, Supplier<String> harnessVersion, String judgeModel,
	List<Path> loaderConfiguration, SliceSettings settings, int ceiling)
{
	/**
	 * Creates a host.
	 *
	 * @param harness the harness that runs the launcher
	 * @param sessions starts nested sessions through the harness's runner
	 * @param interrupts reports whether the host user interrupted the host session
	 * @param clock the clock
	 * @param random the source of campaign identifiers and draw seeds
	 * @param json the operation-owned JSON mapper
	 * @param directory the launcher's working directory, below the project
	 * @param harnessVersion reports the harness's version
	 * @param judgeModel the name of the judge model, or {@code harness-default}
	 * @param loaderConfiguration the files, relative to the project, that decide which prompt files the harness loads
	 * @param settings the slice's time budget
	 * @param ceiling the most nested sessions that may run at once
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code judgeModel} is blank or {@code ceiling} is not positive
	 */
	public Host
	{
		requireThat(harness, "harness").isNotNull();
		requireThat(sessions, "sessions").isNotNull();
		requireThat(interrupts, "interrupts").isNotNull();
		requireThat(clock, "clock").isNotNull();
		requireThat(random, "random").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(directory, "directory").isNotNull();
		requireThat(harnessVersion, "harnessVersion").isNotNull();
		requireThat(judgeModel, "judgeModel").isNotBlank();
		requireThat(loaderConfiguration, "loaderConfiguration").isNotNull();
		requireThat(settings, "settings").isNotNull();
		requireThat(ceiling, "ceiling").isPositive();
		loaderConfiguration = List.copyOf(loaderConfiguration);
	}

	/**
	 * Creates a host with the default judge model, slice budget and ceiling.
	 * <p>
	 * The default slice is the 8-minute budget plus the 1-minute grace period that fits the skill's 10-minute tool
	 * timeout (design &sect;3.8).
	 *
	 * @param harness the name of the harness that runs the launcher, {@code claude} or {@code codex}
	 * @param sessions starts nested sessions through the harness's runner
	 * @param interrupts reports whether the host user interrupted the host session
	 * @param clock the clock
	 * @param random the source of campaign identifiers and draw seeds
	 * @param json the operation-owned JSON mapper
	 * @param directory the launcher's working directory, below the project
	 * @param harnessVersion reports the harness's version
	 * @param loaderConfiguration the files, relative to the project, that decide which prompt files the harness loads
	 * @return the host
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code harness} names no harness
	 */
	public static Host withDefaults(String harness, SessionPort sessions, HostInterruptSignal interrupts,
		Clock clock, RandomGenerator random, JsonMapper json, Path directory, Supplier<String> harnessVersion,
		List<Path> loaderConfiguration)
	{
		requireThat(harness, "harness").isNotNull();
		return new Host(Harness.valueOf(harness.toUpperCase(java.util.Locale.ROOT)), sessions, interrupts, clock,
			random, json, directory, harnessVersion, "harness-default", loaderConfiguration, SliceSettings.DEFAULT,
			Campaign.DEFAULT_CEILING);
	}
}
