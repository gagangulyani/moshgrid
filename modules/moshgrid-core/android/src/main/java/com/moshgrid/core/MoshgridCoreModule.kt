package com.moshgrid.core

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class MoshgridCoreModule : Module() {
    override fun definition() = ModuleDefinition {
        Name("MoshgridCore")

        Function("nativeInfo") {
            val context = appContext.reactContext
                ?: throw IllegalStateException("No react context")

            val bleSupported = context.packageManager
                .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? BluetoothManager)?.adapter

            // isLeExtendedAdvertisingSupported is API 26; minSdk here is 24.
            // Queried, never assumed — defect 5 exists because the source PDF
            // assumed extended advertising was available.
            val extended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                adapter?.isLeExtendedAdvertisingSupported ?: false
            } else {
                false
            }

            val info = NativeInfoProvider.describe(
                sdkInt = Build.VERSION.SDK_INT,
                bleSupported = bleSupported,
                extendedAdvertisingSupported = extended,
            )

            mapOf(
                "moduleVersion" to info.moduleVersion,
                "androidSdkInt" to info.androidSdkInt,
                "bleSupported" to info.bleSupported,
                "extendedAdvertisingSupported" to info.extendedAdvertisingSupported,
            )
        }
    }
}
