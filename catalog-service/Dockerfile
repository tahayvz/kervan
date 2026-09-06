# Kervan Catalog Service — çok aşamalı (multi-stage) imaj.
#
# Neden multi-stage? Derleme araçları (Maven, JDK) nihai imaja SIZMAZ. Sonuç:
# küçük, saldırı yüzeyi düşük, sadece JRE + jar içeren bir çalışma imajı.
#
# ÖNEMLİ: Bu Dockerfile repo KÖKÜNDEN build edilir (parent POM'a erişmek için):
#   docker build -f catalog-service/Dockerfile -t kervan/catalog-service:local .

# ---------- 1) Derleme aşaması ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Önce sadece POM'ları kopyala → bağımlılık katmanı Docker cache'inde kalır,
# kaynak değişince baştan indirilmez.
COPY pom.xml .
# Parent POM tum modulleri listeler; Maven reactor'u kurabilmek icin
# HEPSININ POM'u gerekir, derlenen tek modul olsa bile. Yeni modul eklenince
# bu satirlar guncellenmezse imaj derlemesi kirilir (testler yesil kalir).
COPY event-contracts/pom.xml event-contracts/pom.xml
COPY catalog-service/pom.xml catalog-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
RUN mvn -B -pl catalog-service -am dependency:go-offline -DskipTests || true

# Sonra kaynağı kopyala ve derle (testler imaj build'inde çalışmaz; onlar CI'da — Faz 8)
COPY catalog-service/src catalog-service/src
RUN mvn -B -pl catalog-service -am -DskipTests clean package

# ---------- 2) Çalışma aşaması ----------
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# Güvenlik: root olmayan kullanıcı ile çalış
RUN groupadd -r kervan && useradd -r -g kervan kervan

COPY --from=build /workspace/catalog-service/target/catalog-service-*.jar app.jar
USER kervan

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
