# Prosjektkamera

Android-kamera som lagrer bilder i egne prosjektmapper, utenfor vanlig galleri.

- Kotlin + Jetpack Compose + CameraX, minSdk 26
- Data = vanlige mapper/JPEG under `Android/data/no.prosjektkamera/files/Prosjekter/`
  (synlig via USB/Filer-appen, ikke i galleriet). Mappetreet speiles 1:1 ved fremtidig skysynk.
- NB: appens egen lagring slettes hvis appen avinstalleres.

Bygg: `JAVA_HOME=~/.local/opt/jdk ./gradlew assembleDebug`
