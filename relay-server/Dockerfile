# Compilacion en dos etapas: la imagen final no lleva Gradle ni el codigo
# fuente, solo el JAR y un JRE. Pasa de unos 900 MB a unos 250 MB.

FROM eclipse-temurin:21-jdk-alpine AS construccion
WORKDIR /obra

# Copiamos primero lo que cambia poco, para que Docker reutilice la capa de
# dependencias cuando solo se toca el codigo.
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# wget para el healthcheck de docker-compose.
RUN apk add --no-cache wget

# Usuario sin privilegios: si alguien encuentra un fallo en el servidor, no
# empieza siendo root dentro del contenedor.
RUN addgroup -S relay && adduser -S relay -G relay
USER relay

COPY --from=construccion /obra/build/libs/relay-server.jar app.jar

# El commit del que sale la imagen. Lo pasa el pipeline con --build-arg y el
# servidor lo ensena en GET /api/servidor. Va al final para que cambiarlo no
# invalide ninguna de las capas de arriba.
ARG COMMIT=""
ENV SERVIDOR_COMMIT=$COMMIT

EXPOSE 8080

# MaxRAMPercentage en vez de -Xmx fijo: la JVM respeta el limite de memoria
# del contenedor sin tener que recompilar la imagen.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
