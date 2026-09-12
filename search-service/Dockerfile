# Kervan Search Service — çok aşamalı (multi-stage) imaj.
#
# ÖNEMLİ: Bu Dockerfile repo KÖKÜNDEN build edilir (parent POM'a erişmek için):
#   docker build -f search-service/Dockerfile -t kervan/search-service:local .

# ---------- 1) Derleme aşaması ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
# Parent POM tum modulleri listeler; Maven reactor'u kurabilmek icin HEPSININ POM'u
# gerekir, derlenen tek modul olsa bile. Yeni modul eklenince bu satirlar
# guncellenmezse imaj derlemesi kirilir (testler yesil kalir).
COPY event-contracts/pom.xml event-contracts/pom.xml
COPY catalog-service/pom.xml catalog-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
COPY search-service/pom.xml search-service/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
RUN mvn -B -pl search-service -am dependency:go-offline -DskipTests || true

# Bu servis event-contracts'a bagimli DEGIL: tukettigi akis Debezium'un ham
# degisiklik kaydidir, surumlu bir Avro sozlesmesi degil (ADR-0004). O yuzden
# yalnizca kendi kaynagi kopyalanir.
COPY search-service/src search-service/src
RUN mvn -B -pl search-service -am -DskipTests clean package

# ---------- 2) Çalışma aşaması ----------
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

RUN groupadd -r kervan && useradd -r -g kervan kervan

COPY --from=build /workspace/search-service/target/search-service-*.jar app.jar
USER kervan

# Is trafigi 8087, actuator (saglik + metrik) 9087.
# Yonetim portu uygulama tarafindan dinlenir ama compose onu
# disariya ACMAZ; Prometheus agin icinden okur.
EXPOSE 8087 9087
ENTRYPOINT ["java", "-jar", "app.jar"]
