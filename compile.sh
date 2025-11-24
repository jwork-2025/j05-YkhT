#!/usr/bin/env bash

set -e

echo "Compiling game sources..."

# create output dirs
mkdir -p build/classes
mkdir -p build

# collect java sources into a response file to avoid long commandline
SRC_LIST="build/sources_compile.txt"
find src/main/java -name '*.java' > "$SRC_LIST"

if [ ! -s "$SRC_LIST" ]; then
    echo "No Java sources found in src/main/java"
    exit 1
fi

javac -encoding UTF-8 -d build/classes @"$SRC_LIST"

if [ $? -eq 0 ]; then
    echo "Compile successful. Output -> build/classes"
else
    echo "Compile failed"
    exit 1
fi
