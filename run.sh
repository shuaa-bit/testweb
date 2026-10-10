#!/bin/sh
# Compile and start the gym site on http://localhost:8080  (needs JDK 17 or newer)
set -e
cd "$(dirname "$0")"
mkdir -p out
javac -d out src/gym/*.java
java -cp out gym.Main
