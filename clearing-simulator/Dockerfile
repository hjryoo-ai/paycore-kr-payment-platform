# 사전에 `./gradlew bootJar` 로 빌드된 jar 를 담는 런타임 이미지.
# (compose build 마다 전체 Gradle 빌드를 돌리지 않기 위한 의도적 선택 — scripts/build-images.sh 참고)
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl tzdata \
 && cp /usr/share/zoneinfo/Asia/Seoul /etc/localtime \
 && echo "Asia/Seoul" > /etc/timezone \
 && addgroup -S app && adduser -S app -G app

WORKDIR /app
# 이름은 build.gradle.kts 에서 고정한다. glob 을 쓰면 -plain.jar 와 둘 다 매칭되어
# 어느 쪽이 남는지가 파일명 정렬 순서에 달리게 된다.
COPY build/libs/app.jar /app/app.jar
RUN mkdir -p /app/data && chown -R app:app /app
USER app

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -Duser.timezone=Asia/Seoul"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
