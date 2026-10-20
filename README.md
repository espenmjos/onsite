# Onsite

Android-app for foto, video og lydopptak fra oppdrag, sortert i prosjektmapper
og holdt utenfor vanlig galleri. Logo: `docs/logo/onsite.svg` (marine #13293D + gul #FFC400).

- Kotlin + Jetpack Compose + CameraX + Media3, minSdk 26
- Data = vanlige mapper og filer (`.jpg`, `.mp4`, `.m4a`) under `Pictures/Onsite/` på intern lagring
  (overlever avinstallering; `.nomedia` holder dem ute av galleriet, kan slås av i innstillinger).
  Krever «Tilgang til alle filer» (MANAGE_EXTERNAL_STORAGE) på Android 11+.
  Mappetreet speiles 1:1 ved fremtidig skysynk.

## Installere

Last ned `Onsite-<versjon>.apk` fra [Releases](../../releases) og åpne den på telefonen
(tillat installasjon fra ukjente kilder). Første gang ber appen om «Tilgang til alle filer».

## Bygge

    JAVA_HOME=~/.local/opt/jdk ./gradlew assembleDebug     # debug
    JAVA_HOME=~/.local/opt/jdk ./gradlew assembleRelease   # signert release

Release signeres med nøkkelen beskrevet i en properties-fil utenfor repoet
(`~/.android-keys/onsite-release.properties`, eller sti i `ONSITE_SIGNING`) med
`storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Uten den blir release-APK-en usignert.
Ta vare på nøkkelen: oppdateringer må signeres med samme nøkkel.
