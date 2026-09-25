# Подключается командой source из других скриптов и Makefile, из корня репозитория.
# JDK 11 берётся из JAVA_HOME, если он задан; иначе на macOS — из Homebrew (openjdk@11).
if [ -z "${JAVA_HOME:-}" ] && command -v brew > /dev/null 2>&1 && brew --prefix openjdk@11 > /dev/null 2>&1; then
  JAVA_HOME="$(brew --prefix openjdk@11)"
fi
if [ -n "${JAVA_HOME:-}" ]; then
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"
fi
export COMPOSE="${COMPOSE:-docker compose}"

# Пересобирает target/heatnet.jar, если его нет или исходники новее.
ensure_jar() {
  if [ ! -f target/heatnet.jar ] || [ -n "$(find pom.xml rules src -newer target/heatnet.jar -type f -print -quit)" ]; then
    mvn -q -B -DskipTests package
  fi
}
