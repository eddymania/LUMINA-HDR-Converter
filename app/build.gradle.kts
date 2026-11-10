plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.lumina.hdr"; compileSdk = 35
 defaultConfig { applicationId = "com.lumina.hdr"; minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "1.0.0" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
