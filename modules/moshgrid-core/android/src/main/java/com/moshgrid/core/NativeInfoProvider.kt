package com.moshgrid.core

data class NativeInfo(
    val moduleVersion: String,
    val androidSdkInt: Int,
    val bleSupported: Boolean,
    val extendedAdvertisingSupported: Boolean,
)

object NativeInfoProvider {
    const val MODULE_VERSION = "0.1.0"

    fun describe(
        sdkInt: Int,
        bleSupported: Boolean,
        extendedAdvertisingSupported: Boolean,
    ): NativeInfo = NativeInfo(
        moduleVersion = MODULE_VERSION,
        androidSdkInt = sdkInt,
        bleSupported = bleSupported,
        extendedAdvertisingSupported = extendedAdvertisingSupported,
    )
}
