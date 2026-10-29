package com.example.usage.api;

import java.util.List;

public interface ReportQueue
{
	List<Report> drain();
	int size();
}
