#!/usr/bin/env bash
# Builds KozyrChunkyQueue.jar without any build system (Maven/Gradle) - just javac + jar.
# Downloads compile-time-only dependencies into .deps/ (git-ignored) on first run.
set -euo pipefail
cd "$(dirname "$0")"

PAPER_API_VERSION="${PAPER_API_VERSION:-26.2.build.87-stable}"
CHUNKY_VERSION_ID="${CHUNKY_VERSION_ID:-MdY6JATr}"      # Modrinth version id for Chunky-Bukkit-1.5.3.jar
ADVENTURE_VERSION="5.2.0"
BUNGEECORD_CHAT_VERSION="1.21-R0.2-deprecated+build.21"
ANNOTATIONS_VERSION="26.0.1"

DEPS_DIR=".deps"
OUT_DIR="out"
JAVAC="${JAVAC:-javac}"
JAR="${JAR:-jar}"

mkdir -p "$DEPS_DIR" "$OUT_DIR/classes" build

dl() {
  local url="$1" dest="$2"
  if [ -f "$dest" ]; then return; fi
  echo "Downloading $(basename "$dest")..."
  curl -sfL "$url" -o "$dest"
}

dl "https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/${PAPER_API_VERSION}/paper-api-${PAPER_API_VERSION}.jar" "$DEPS_DIR/paper-api.jar"
dl "https://api.modrinth.com/v2/version/${CHUNKY_VERSION_ID}" "$DEPS_DIR/chunky-version.json"
CHUNKY_URL=$(grep -o '"url":"[^"]*Chunky-Bukkit[^"]*"' "$DEPS_DIR/chunky-version.json" | head -1 | sed 's/"url":"//;s/"$//')
dl "$CHUNKY_URL" "$DEPS_DIR/chunky-bukkit.jar"
dl "https://repo.papermc.io/repository/maven-public/net/kyori/adventure-api/${ADVENTURE_VERSION}/adventure-api-${ADVENTURE_VERSION}.jar" "$DEPS_DIR/adventure-api.jar"
dl "https://repo.papermc.io/repository/maven-public/net/kyori/adventure-key/${ADVENTURE_VERSION}/adventure-key-${ADVENTURE_VERSION}.jar" "$DEPS_DIR/adventure-key.jar"
dl "https://repo.papermc.io/repository/maven-public/net/md-5/bungeecord-chat/${BUNGEECORD_CHAT_VERSION}/bungeecord-chat-${BUNGEECORD_CHAT_VERSION}.jar" "$DEPS_DIR/bungeecord-chat.jar"
dl "https://repo1.maven.org/maven2/org/jetbrains/annotations/${ANNOTATIONS_VERSION}/annotations-${ANNOTATIONS_VERSION}.jar" "$DEPS_DIR/annotations.jar"

CP="$DEPS_DIR/paper-api.jar:$DEPS_DIR/chunky-bukkit.jar:$DEPS_DIR/adventure-api.jar:$DEPS_DIR/adventure-key.jar:$DEPS_DIR/bungeecord-chat.jar:$DEPS_DIR/annotations.jar"

rm -rf "$OUT_DIR/classes"
mkdir -p "$OUT_DIR/classes"
"$JAVAC" --release 25 -encoding UTF-8 -cp "$CP" -d "$OUT_DIR/classes" $(find src/main/java -name '*.java')
cp src/main/resources/*.yml "$OUT_DIR/classes/"

VERSION=$(grep '^version:' src/main/resources/plugin.yml | awk '{print $2}')
( cd "$OUT_DIR/classes" && "$JAR" cf "../../build/KozyrChunkyQueue-${VERSION}.jar" . )

echo "Built build/KozyrChunkyQueue-${VERSION}.jar"
