def load(path):
	with open(path) as handle:
		return [line.strip() for line in handle]
