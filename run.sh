#!/usr/bin/env bash

set -e

echo "Starting game (HuluSnake)..."

# compile first
./compile.sh

echo "Running HuluSnake..."
java -cp build/classes com.gameengine.example.HuluSnake
