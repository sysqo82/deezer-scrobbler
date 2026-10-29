# Deezer Scrobbler

A lightweight, native Windows desktop application that scrobbles music played on Deezer to Last.fm.

## Features

- **Dual Detection Strategy**:
  - Uses Windows System Media Transport Controls (GSMTC) via a dedicated C# helper (`MediaBridge`).
  - Fallback to JNA window title polling for Deezer.
- **Last.fm Integration**:
  - OAuth login flow.
  - "Now Playing" updates.
  - Configurable scrobble thresholds (percentage & minimum seconds played).
  - Offline scrobble queue for automatic syncing when internet or service recovers.
- **Compose for Desktop UI**: Built with Jetpack Compose for Desktop and Kotlin.

## Requirements

- **OS**: Windows 10 / 11
- **JDK**: Java 17 or higher
- **Last.fm Account**: API key and secret configured in the application.

## Build & Run

### Run from Source
```cmd
gradlew.bat run
```

### Run Tests
```cmd
gradlew.bat test
```

### Create Installers & Packages
- **Generate `.exe` installer**:
  ```cmd
  gradlew.bat packageExe
  ```
- **Generate `.msi` installer**:
  ```cmd
  gradlew.bat packageMsi
  ```
- **Generate all desktop distributions**:
  ```cmd
  gradlew.bat packageDistributionForCurrentOS
  ```

Output binaries are located in `build/compose/binaries/main/`.
