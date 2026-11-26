package com.holdmyspot.cat.common.optimizer.execution;

/**
 * A stage that a check program's run passes through before its source launcher is ready, as the programs of the run's
 * process tree show it.
 */
public enum StartupStage
{
	/** Every program of the process tree is the sandbox program, which is setting up its namespaces and mounts. */
	SANDBOX_SETUP("sandbox setup"),
	/** The sandbox has handed over, so the tree runs a program that is not the sandbox program, such as Java. */
	LAUNCHER_STARTING("launcher starting"),
	/** The host did not let CAT read the path of a program of the tree. */
	NOT_OBSERVED("not observed");

	private final String description;

	/**
	 * Creates a stage.
	 *
	 * @param description the name that a report gives the stage
	 */
	StartupStage(String description)
	{
		this.description = description;
	}

	/**
	 * Returns the name that a report gives the stage.
	 *
	 * @return the name
	 */
	public String description()
	{
		return description;
	}
}
