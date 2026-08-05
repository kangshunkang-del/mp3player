FROM gradle:8.2.1-jdk17 AS gradle-runtime

FROM ghcr.io/cirruslabs/android-sdk:34 AS android-build
COPY --from=gradle-runtime /opt/gradle /opt/gradle
ENV PATH="/opt/gradle/bin:${PATH}"
WORKDIR /workspace
COPY . .
RUN gradle testDebugUnitTest lintDebug assembleRelease --no-daemon

FROM nginx:1.27-alpine
COPY cloudbuild/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=android-build /workspace/app/build/outputs/apk/release/app-release-unsigned.apk /usr/share/nginx/html/mp3-player-4.1.0-unsigned.apk
EXPOSE 80
