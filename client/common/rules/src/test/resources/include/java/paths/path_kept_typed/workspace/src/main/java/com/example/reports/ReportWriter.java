package com.example.reports;

public final class ReportWriter
{
	private final ReportStore store;

	public ReportWriter(ReportStore store)
	{
		this.store = store;
	}

	public void write(Report report)
	{
		store.save(report);
	}
}
