# Kodiak Maps for Android

Source code for Kodiak Maps for Android 1.5.14, made by Victor.

An offline map app for Kodiak Island: USGS topo or OpenTopoMap base maps, offline area downloads, GPS position and tracks, trails, ANCSA 17(b) easements, road and land layers, fishing spots and regulations, tides and points of interest. The military layer draws the structures and features from the old 1965 Navy and Army maps on [kadiak.org](http://kadiak.org/) (credited in the app) as points and outlines on the map, with info cards on what each structure was, plus field-GPS-surveyed WWII sites. Military, fishing, harbor, trail and library pins can show photos, each with its own credit and link.

## Download

Download and install the app for free from [kodiakking.com](https://kodiakking.com).

## Build

Requirements: JDK 17 or 21, Android SDK (compileSdk 35), or Android Studio.

```
./gradlew assembleDebug        # debug APK: app/build/outputs/apk/debug/
./gradlew assembleRelease      # release APK: app/build/outputs/apk/release/
```

Windows: use `gradlew.bat` instead of `./gradlew`. Create a `local.properties` with `sdk.dir=...` if `ANDROID_HOME` is not set (Android Studio does this for you).

### Signing (optional)

Release signing reads `keystore/keystore.properties`, which is not in the repository. Copy `keystore/keystore.properties.example` to `keystore/keystore.properties`, put your own keystore in `keystore/`, and fill in the values. Without it the release build is unsigned.

## Notes

**Property parcels:** the Kodiak Island Borough parcel layer (`app/src/main/assets/kodiak/parcels.geojson.gz`) is an empty placeholder in this repository because the borough data includes property owner names. The released app on the website includes the full layer. To rebuild it, download the public KIB_Parcels data from the Kodiak Island Borough GIS service and save it as a gzipped GeoJSON FeatureCollection at that path.

**Photos:** the photos in `app/src/main/assets/kodiak/photos/` are not covered by any license for this source code. Each photo keeps its own credit, licence and source link in the JSON manifest next to it (shown in the app): historic military photos via [kadiak.org](http://kadiak.org/) with their named photographers, and Wikimedia Commons / USFWS photos under their stated licences (public domain, CC BY, CC BY-SA). EXIF/GPS metadata is stripped.

## License

No license has been chosen for this project yet.
