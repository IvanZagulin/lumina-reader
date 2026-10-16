# Build the distribution with the wrapper, then run it on a small JRE image.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon installDist

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/install/lumina-social /app
EXPOSE 8080
ENTRYPOINT ["/app/bin/lumina-social"]
