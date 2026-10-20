# Prosjektkamera

Android-kamera som lagrer bilder i egne prosjektmapper, utenfor vanlig galleri.

- Kotlin + Jetpack Compose + CameraX, minSdk 26
- Data = vanlige mapper/JPEG under `Pictures/Prosjektkamera/` på intern lagring
  (overlever avinstallering; `.nomedia` holder dem ute av galleriet, kan slås av i menyen).
  Krever «Tilgang til alle filer» (MANAGE_EXTERNAL_STORAGE) på Android 11+.
  Mappetreet speiles 1:1 ved fremtidig skysynk.

Bygg: `JAVA_HOME=~/.local/opt/jdk ./gradlew assembleDebug`
