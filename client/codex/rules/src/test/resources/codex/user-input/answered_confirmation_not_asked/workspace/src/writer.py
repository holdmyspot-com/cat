def save(path, lines):
	with open(path, "w") as handle:
		for line in lines:
			handle.write(line + "\n")
