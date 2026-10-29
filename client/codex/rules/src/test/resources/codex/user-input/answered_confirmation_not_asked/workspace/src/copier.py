from reader import load
from writer import save


def copy(source, target):
	lines = load(source)
	if lines:
		save(target, lines)
	return len(lines)
