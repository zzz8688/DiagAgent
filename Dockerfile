FROM openjdk:17-slim

WORKDIR /app

COPY target/diag-agent-*.jar app.jar

EXPOSE 8081

ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
ENV JAVA_OPTS="-Xms256m -Xmx512m -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"

ENTRYPOINT sh -c "java $JAVA_OPTS -jar app.jar"
