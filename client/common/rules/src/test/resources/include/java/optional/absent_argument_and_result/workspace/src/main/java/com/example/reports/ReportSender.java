package com.example.reports;

import java.util.Optional;

public final class ReportSender
{
	private final ReportStore store;

	public ReportSender(ReportStore store)
	{
		this.store = store;
	}

	public Optional<Report> find(String id)
	{
		return store.lookup(id);
	}

	public void send(Report report)
	{
		store.upload(report);
	}
}
