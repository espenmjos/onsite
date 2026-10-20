# Onsite

Android-app for foto, video og lydopptak fra oppdrag, sortert i prosjektmapper
og holdt utenfor vanlig galleri. Logo: `docs/logo/onsite.svg` (marine #13293D + gul #FFC400).

- Kotlin + Jetpack Compose + CameraX + Media3, minSdk 26
- Data = vanlige mapper og filer (`.jpg`, `.mp4`, `.m4a`) under `Pictures/Onsite/` på intern lagring
  (overlever avinstallering; `.nomedia` holder dem ute av galleriet, kan slås av i innstillinger).
  Krever «Tilgang til alle filer» (MANAGE_EXTERNAL_STORAGE) på Android 11+.
  Mappetreet speiles 1:1 ved fremtidig skysynk.

Bygg: `JAVA_HOME=~/.local/opt/jdk ./gradlew assembleDebug`
