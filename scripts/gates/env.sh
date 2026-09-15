# Подключается командой source из других скриптов гейтов, из корня репозитория.
JAVA_HOME="$(brew --prefix openjdk@11)"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
export COMPOSE="${COMPOSE:-docker compose}"

# Пересобирает target/heatnet.jar, если его нет или исходники новее.
ensure_jar() {
  if [ ! -f target/heatnet.jar ] || [ -n "$(find pom.xml rules src -newer target/heatnet.jar -type f -print -quit)" ]; then
    mvn -q -B -DskipTests package
  fi
}
