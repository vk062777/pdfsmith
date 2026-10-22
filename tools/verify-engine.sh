#!/usr/bin/env bash
# Independently verifies the PDF engine against the real Apache PDFBox Android
# library, on a plain JVM, using tiny stubs for the handful of android.* classes
# the engine touches. Runs the same 39 behavioural checks used during development.
set -euo pipefail
cd "$(dirname "$0")/engine-verify"
mkdir -p libs

echo "== downloading PDFBox-Android and its dependencies =="
curl -sSL -o libs/pdfbox.aar https://repo1.maven.org/maven2/com/tom-roush/pdfbox-android/2.0.27.0/pdfbox-android-2.0.27.0.aar
curl -sSL -o libs/bcprov.jar https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk15to18/1.72/bcprov-jdk15to18-1.72.jar
curl -sSL -o libs/bcpkix.jar https://repo1.maven.org/maven2/org/bouncycastle/bcpkix-jdk15to18/1.72/bcpkix-jdk15to18-1.72.jar
curl -sSL -o libs/bcutil.jar https://repo1.maven.org/maven2/org/bouncycastle/bcutil-jdk15to18/1.72/bcutil-jdk15to18-1.72.jar

rm -rf libs/aar && mkdir -p libs/aar
( cd libs/aar && unzip -oq ../pdfbox.aar )

echo "== compiling Android stubs =="
rm -rf out && mkdir -p out
javac -d out $(find stubs -name '*.java')

CP="out:libs/aar/classes.jar:libs/aar/assets:libs/bcprov.jar:libs/bcpkix.jar:libs/bcutil.jar"

echo "== compiling the real engine + tests =="
javac -cp "$CP" -d out $(find ../../app/src/main/java -name '*.java') TestEngine.java

echo "== running engine verification =="
java -cp "$CP" TestEngine

echo "== rotation / overlay placement (rendered and pixel-checked) =="
javac -cp "$CP" -d out TestRotate.java
java -cp "$CP" TestRotate
python3 -m pip install --quiet pymupdf >/dev/null 2>&1 || true
python3 verify-render.py
