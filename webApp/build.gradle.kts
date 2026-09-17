import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    js {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            // Webpack's default dev devtool wraps modules in eval(), and
            // `import.meta` — which the Kotlin/Wasm glue emits — is a SyntaxError
            // inside eval(). Without this the dev server serves a bundle that
            // throws before Compose can mount.
            commonWebpackConfig {
                devtool = "source-map"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))

            implementation(libs.compose.ui)
        }
    }
}