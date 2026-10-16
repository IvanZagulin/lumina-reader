// The social backend is a standalone build: it shares nothing with the mobile
// app's Gradle graph on purpose, so the KMP/Android build (and its iOS quirks)
// cannot break it and it can be deployed on its own, without the app sources.
rootProject.name = "lumina-social"
