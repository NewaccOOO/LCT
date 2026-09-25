FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY rules rules
COPY src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:11-jre
WORKDIR /app
COPY --from=build /build/target/heatnet.jar heatnet.jar
ENV DATA_DIR=/data
# молодое поколение кучи не больше 512 МБ: иначе на большом входе G1 раздувает его на гигабайты сверх данных,
# и при нехватке памяти машина уходит в подкачку
ENV JAVA_OPTS="-XX:MaxNewSize=512m"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/heatnet.jar \"$@\"", "--"]
