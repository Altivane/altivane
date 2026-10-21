plugins {
    id("com.android.application") version "8.9.2" apply false
    // Kotlin 2.2：MapLibre 13.6.1 以 Kotlin 2.2 metadata 编译，2.0 消费不了
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("com.google.devtools.ksp") version "2.2.20-2.0.2" apply false
}
