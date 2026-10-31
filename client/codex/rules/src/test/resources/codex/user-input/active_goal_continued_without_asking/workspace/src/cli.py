import argparse

from client import connect


def main():
    parser = argparse.ArgumentParser(prog="relay")
    parser.add_argument("host")
    parser.add_argument("port", type=int)
    args = parser.parse_args()
    connect(args.host, args.port)


if __name__ == "__main__":
    main()
