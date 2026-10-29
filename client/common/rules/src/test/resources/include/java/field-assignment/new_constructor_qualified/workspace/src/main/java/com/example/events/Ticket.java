package com.example.events;

public final class Ticket
{
	private static int {{counter_field}};

	private final String event;
	private final int seat;
	private final long number;

	public String event()
	{
		return event;
	}

	public int seat()
	{
		return seat;
	}

	public long number()
	{
		return number;
	}
}
