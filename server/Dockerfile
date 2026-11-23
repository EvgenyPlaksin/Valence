FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace
COPY protocol/ protocol/
COPY server/ server/
WORKDIR /workspace/server
RUN chmod +x gradlew && ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre-noble
RUN useradd --system --create-home --uid 10001 valence
WORKDIR /app
COPY --from=build /workspace/server/build/install/server/ /app/
USER valence
ENV PORT=8081
EXPOSE 8081
ENTRYPOINT ["/app/bin/server"]
