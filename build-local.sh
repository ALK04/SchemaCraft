#!/bin/sh
# Build sans Gradle : compile avec le JDK 25 de CurseForge contre Minecraft 26.3 + Fabric.
# Usage : ./build-local.sh            -> build/libs/schemacraft-<version>.jar
#         ./build-local.sh install    -> copie aussi le jar dans l'instance "Jules"
set -e
cd "$(dirname "$0")"
VERSION=$(grep '^version=' gradle.properties | cut -d= -f2)
JDK=/c/Users/richa/curseforge/minecraft/Install/runtime/java-runtime-epsilon/windows-x64/java-runtime-epsilon/bin
INSTANCE=/c/Users/richa/curseforge/minecraft/Instances/Jules
CP=$(sed 's#^/c/#C:/#' .buildlib/cp.txt | tr '\n' ';')

rm -rf build/classes build/res build/libs
mkdir -p build/classes build/res build/libs
find src/main/java -name '*.java' > build/sources.txt
"$JDK/javac.exe" --release 25 -encoding UTF-8 -Xlint:-options -nowarn -cp "$CP" -d build/classes @build/sources.txt
cp -r src/main/resources/. build/res/
sed -i "s/\${version}/$VERSION/" build/res/fabric.mod.json
JAR=build/libs/schemacraft-$VERSION.jar
"$JDK/jar.exe" --create --file "$JAR" -C build/classes . -C build/res .
echo "OK -> $JAR"
if [ "$1" = "install" ]; then
  rm -f "$INSTANCE"/mods/schemacraft-*.jar
  cp "$JAR" "$INSTANCE/mods/"
  echo "Installé dans $INSTANCE/mods"
fi
