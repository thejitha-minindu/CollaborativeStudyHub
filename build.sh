#!/usr/bin/env bash
set -e

echo "==================================================="
echo "  Compiling Collaborative Study Hub (Java SE 17+)  "
echo "==================================================="

mkdir -p out
find src -name "*.java" > sources.txt
javac -encoding UTF-8 -d out @sources.txt
rm sources.txt

echo "[BUILD SUCCESS] All classes compiled to out/"
