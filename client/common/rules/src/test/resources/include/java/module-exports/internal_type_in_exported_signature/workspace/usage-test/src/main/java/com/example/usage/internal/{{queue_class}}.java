package com.example.usage.internal;

import com.example.usage.api.Report;
import com.example.usage.api.ReportQueue;
import java.nio.file.Path;
import java.util.List;

public final class {{queue_class}} implements ReportQueue
{
	public {{queue_class}}(Path file, SigningKeys keys) { ... }
	public List<Report> drain() { ... }
	public int size() { ... }
	public void overwrite(Path file) { ... }
}
