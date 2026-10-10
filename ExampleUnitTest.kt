name: Build debug APK

on:
  push:
    branches:
      - '**'
  workflow_dispatch:

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v6
        with:
          fetch-depth: 0

      # В архиве/форке без сабмодулей папка scrcpy пустая, а сборка берёт код сервера из scrcpy/server.
      # Подтягиваем ту же версию scrcpy, что использует проект (коммит 3fcc177).
      - name: Fetch scrcpy sources
        run: |
          if [ ! -d scrcpy/server/src/main/java ]; then
            rm -rf scrcpy
            git clone https://github.com/Genymobile/scrcpy.git scrcpy
            git -C scrcpy checkout 3fcc177da5b6b4514d0e8e8d90d7d58d6731eac9
          fi
          ls scrcpy/server/src/main/java/com/genymobile/scrcpy | head

      - name: Set up JDK 21
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '21'
          cache: gradle

      # На runner ubuntu-latest Android SDK уже установлен (ANDROID_HOME).
      # Лицензии принимаем записью файлов, без интерактивного sdkmanager --licenses (он падал).
      - name: Accept Android SDK licenses
        run: |
          mkdir -p "$ANDROID_HOME/licenses"
          printf "\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n8933bad161af4178b1185d1a37fbf41ea5269c55\nd56f5187479451eabf01fb78af6dfcb131a6481e\n" > "$ANDROID_HOME/licenses/android-sdk-license"
          printf "\n84831b9409646a918e30573bab4c9c91346d8abd\n" > "$ANDROID_HOME/licenses/android-sdk-preview-license"

      - name: Install Android 36 platform
        run: |
          SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
          "$SDKM" "platforms;android-36" "build-tools;36.0.0"

      - name: Grant execute permission for Gradle
        run: chmod +x gradlew

      - name: Build debug APK
        run: ./gradlew assembleDebug --stacktrace --no-daemon -Pkotlin.compiler.execution.strategy=in-process

      - name: Upload debug APK artifact
        uses: actions/upload-artifact@v7
        with:
          name: app-debug-apk
          path: app/build/outputs/apk/debug/*.apk
          if-no-files-found: error
