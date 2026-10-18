package com.example.runner;

import java.io.IOException;
import org.testng.annotations.Test;

public final class CommandSessionTest
{
	@Test
	@SuppressWarnings("PMD.CloseResource")
	public void closeStopsCommand() throws IOException, InterruptedException
	{
		Process process = new ProcessBuilder("sleep", "60").start();
		try (CommandSession _ = new CommandSession(process))
		{
		}
		process.waitFor();
	}
}
