# =====================================================================
# Anh Docker cua AI-Agent.
#
# Hai tang (build + run) de anh chay khong mang theo Maven va ma nguon:
# anh cuoi chi co JRE + mot file jar.
#
# Vi sao KHONG dung `mvn package` tren may roi COPY jar vao: nhu the ban
# dung duoc anh tu may cua ai thi chay may do, va mot ngay nao do se co
# ban dung tu may co JDK khac. O day build nam trong anh nen ket qua giong
# nhau o moi noi.
# =====================================================================

# --------------------------------------------------- Tang build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy rieng pom truoc de tang cache phu thuoc khong bi pha moi lan sua code.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# Bo qua test khi dong goi: test cham DB can Docker, ma o day ta DANG o trong
# Docker. Test duoc chay o buoc CI rieng (xem .github/workflows/ci.yml).
RUN mvn -B clean package -DskipTests

# --------------------------------------------------- Tang chay
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Chay bang tai khoan thuong, khong phai root. Mot loi RCE trong thu vien xu ly
# tai lieu (POI/PDFBox doc file do nguoi dung tai len) se bi gioi han trong pham
# vi tai khoan nay thay vi chiem ca container.
RUN groupadd --system --gid 1001 aiagent \
 && useradd  --system --uid 1001 --gid aiagent --create-home aiagent

# tzdata + fontconfig: fontconfig can cho PDFBox ket xuat trang thanh anh (OCR).
# Thieu no thi OCR chay duoc nhung chu bi thay bang o vuong.
RUN apt-get update \
 && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core curl \
 && rm -rf /var/lib/apt/lists/*

ENV TZ=Asia/Ho_Chi_Minh
ENV LANG=C.UTF-8

COPY --from=build /build/target/AIAgent-*.jar /app/app.jar
RUN mkdir -p /app/logs && chown -R aiagent:aiagent /app

USER aiagent
EXPOSE 8080

# Container biet gioi han bo nho cua chinh no; de mac dinh thi JVM lay theo RAM
# cua CA MAY CHU va se bi OOM-kill khi container bi gioi han.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Dfile.encoding=UTF-8"

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
