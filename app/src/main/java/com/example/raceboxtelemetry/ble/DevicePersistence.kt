package com.example.raceboxtelemetry.ble

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

class DevicePersistence(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "racebox_devices"
        private const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"
        private const val KEY_LAST_DEVICE_NAME = "last_device_name"
        private const val KEY_DEVICE_ALIASES = "device_aliases"
    }

    // Save the last connected device
    fun saveLastConnectedDevice(address: String, name: String) {
        prefs.edit().apply {
            putString(KEY_LAST_DEVICE_ADDRESS, address)
            putString(KEY_LAST_DEVICE_NAME, name)
            apply()
        }
    }

    // Get the last connected device info
    fun getLastConnectedDevice(): Pair<String?, String?>? {
        val address = prefs.getString(KEY_LAST_DEVICE_ADDRESS, null)
        val name = prefs.getString(KEY_LAST_DEVICE_NAME, null)

        return if (address != null && name != null) {
            Pair(address, name)
        } else {
            null
        }
    }

    // Clear the last connected device
    fun clearLastConnectedDevice() {
        prefs.edit().apply {
            remove(KEY_LAST_DEVICE_ADDRESS)
            remove(KEY_LAST_DEVICE_NAME)
            apply()
        }
    }

    // Save an alias for a device
    fun saveDeviceAlias(address: String, alias: String) {
        val aliases = getDeviceAliases().toMutableMap()
        aliases[address] = alias
        saveDeviceAliases(aliases)
    }

    // Get alias for a specific device
    fun getDeviceAlias(address: String): String? {
        return getDeviceAliases()[address]
    }

    // Get all device aliases
    fun getDeviceAliases(): Map<String, String> {
        val aliasesJson = prefs.getString(KEY_DEVICE_ALIASES, null) ?: return emptyMap()

        return try {
            val jsonObject = JSONObject(aliasesJson)
            val aliases = mutableMapOf<String, String>()

            jsonObject.keys().forEach { key ->
                aliases[key] = jsonObject.getString(key)
            }

            aliases
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // Remove alias for a device
    fun removeDeviceAlias(address: String) {
        val aliases = getDeviceAliases().toMutableMap()
        aliases.remove(address)
        saveDeviceAliases(aliases)
    }

    // Save all device aliases
    private fun saveDeviceAliases(aliases: Map<String, String>) {
        val jsonObject = JSONObject(aliases)
        prefs.edit().apply {
            putString(KEY_DEVICE_ALIASES, jsonObject.toString())
            apply()
        }
    }
}
