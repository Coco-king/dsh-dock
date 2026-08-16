rootProject.name = "dsh-idea-plugin"

pluginManagement {
    repositories {
        // 优先使用阿里云镜像 (国内下载快)
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}