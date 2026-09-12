# Kervan Inventory Service — çok aşamalı (multi-stage) imaj.
#
# ÖNEMLİ: Bu Dockerfile repo KÖKÜNDEN build edilir (parent POM'a erişmek için):
#   docker build -f inventory-service/Dockerfile -t kervan/inventory-service:local .

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
# BuildKit onbellek baglantisi: Maven deposu derlemeler ARASINDA korunur.
# Olmadan her imaj derlemesi butun bagimliliklari yeniden indirir ve alti servisi
# yerelde derlemek saatler surer. Onbellek imaja GIRMEZ; yalnizca derleme
# sirasinda baglanir, yani calisma imaji buyumez.
RUN --mount=type=cache,target=/root/.m2 mvn -B -pl inventory-service -am dependency:go-offline -DskipTests || true

# event-contracts bu servisin bagimliligi; -am onu da derler, kaynagi gerekli.
COPY event-contracts/src event-contracts/src
COPY inventory-service/src inventory-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -pl inventory-service -am -DskipTests clean package

# ---------- 2) Çalışma aşaması ----------
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# UID/GID ACIKCA sabitleniyor.
#
# `useradd -r` numarayi kendi secer ve taban imaj degisirse numara da degisir.
# Kubernetes tarafinda runAsNonRoot, kullanicinin root OLMADIGINI dogrulamak
# icin SAYISAL bir UID ister; ad yazarsa dogrulayamaz ve pod hic baslamaz:
#   "container has runAsNonRoot and image has non-numeric user (kervan)"
RUN groupadd -r -g 10001 kervan && useradd -r -u 10001 -g kervan kervan

COPY --from=build /workspace/inventory-service/target/inventory-service-*.jar app.jar
# Ad degil NUMARA. Yukaridaki gerekce.
USER 10001:10001

# Is trafigi 8084, actuator (saglik + metrik) 9084.
# Yonetim portu uygulama tarafindan dinlenir ama compose onu
# disariya ACMAZ; Prometheus agin icinden okur.
EXPOSE 8084 9084
ENTRYPOINT ["java", "-jar", "app.jar"]
