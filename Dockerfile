FROM maven:3.9-eclipse-temurin-11-noble AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY rules rules
COPY platform platform
COPY src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:11-jre-noble
WORKDIR /app
COPY --from=build /build/target/heatnet.jar heatnet.jar
ENV DATA_DIR=/data
ENV JAVA_OPTS="-XX:MaxNewSize=512m"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/heatnet.jar \"$@\"", "--"]
