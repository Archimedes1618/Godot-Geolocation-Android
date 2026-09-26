package com.karimibrahim.godot.android.geolocation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.godotengine.godot.Dictionary
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo
import org.godotengine.godot.plugin.UsedByGodot

/**
 * Industriestandard Android Geolocation Plugin für Ambiqore.
 * Nutzt den Google FusedLocationProviderClient (High Accuracy) mit automatischem Fallback
 * auf den nativen Android LocationManager. Umgeht aggressives Akkusparen von Huawei/Samsung.
 */
class GodotAndroidPlugin(godot: Godot) : GodotPlugin(godot) {

    companion object {
        private const val PLUGIN_NAME = "GeolocationPlugin"
        private const val PLUGIN_VERSION = "2.0.0-FUSED"
        private const val LOCATION_PERMISSION_REQUEST_CODE: Int = 1001
        private const val TAG = "GeolocationPlugin"
    }

    private val locationPermissionSignal =
        SignalInfo("locationPermission", Boolean::class.javaObjectType)
    private val locationUpdateSignal = SignalInfo("locationUpdate", Dictionary::class.java)

    private var fusedClient: FusedLocationProviderClient? = null
    private var fusedLocationCallback: LocationCallback? = null

    private fun getLocationManager(): LocationManager? {
        val act = activity ?: return null
        return act.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    private var isListeningForGeolocationUpdates = false
    private var lastLocation: Location? = null
    private var fixesReceived = 0
    private var lastRegisteredProvidersCount = 0

    // Nativer LocationManager Listener als stabiler Fallback
    private val nativeLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleNewLocation(location, "native_" + (location.provider ?: "gps"))
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {
            Log.i(TAG, "HARDWARE_PROVIDER_ENABLED: $provider")
        }
        override fun onProviderDisabled(provider: String) {
            Log.i(TAG, "HARDWARE_PROVIDER_DISABLED: $provider")
        }
    }

    override fun getPluginName() = PLUGIN_NAME

    override fun getPluginSignals() = setOf(
        locationPermissionSignal,
        locationUpdateSignal
    )

    private fun handleNewLocation(location: Location, providerTag: String) {
        fixesReceived++
        lastLocation = location
        Log.i(TAG, "LIVE_GPS_UPDATE #$fixesReceived [$providerTag]: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m, alt=${location.altitude}m")

        val dict = Dictionary()
        dict["latitude"] = location.latitude
        dict["longitude"] = location.longitude
        dict["accuracy"] = location.accuracy.toDouble()
        dict["altitude"] = location.altitude
        dict["speed"] = location.speed.toDouble()
        dict["time"] = location.time
        dict["provider"] = location.provider ?: providerTag
        dict["fixes"] = fixesReceived

        activity?.runOnUiThread {
            try {
                emitSignal(locationUpdateSignal.name, dict)
            } catch (e: Exception) {
                Log.e(TAG, "Error emitting locationUpdate signal", e)
            }
        }
    }

    @UsedByGodot
    fun ping(): String {
        val pingString = "$PLUGIN_NAME-$PLUGIN_VERSION (Fixes: $fixesReceived, Listening: $isListeningForGeolocationUpdates)"
        Log.i(TAG, "PLUGIN_PING: $pingString")
        return pingString
    }

    @UsedByGodot
    fun isLocationEnabled(): Boolean {
        val act = activity ?: return true
        val lm = act.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true

        // 1. Android 9+ (API 28+) Standard-Methode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                if (lm.isLocationEnabled) {
                    Log.i(TAG, "isLocationEnabled: true via lm.isLocationEnabled")
                    return true
                }
            } catch (e: Exception) {}
        }

        // 2. AndroidX LocationManagerCompat
        try {
            if (LocationManagerCompat.isLocationEnabled(lm)) {
                Log.i(TAG, "isLocationEnabled: true via LocationManagerCompat")
                return true
            }
        } catch (e: Exception) {}

        // 3. Provider-Checks
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                Log.i(TAG, "isLocationEnabled: true via isProviderEnabled")
                return true
            }
        } catch (e: Exception) {}

        // 4. Settings.Secure
        try {
            val mode = Settings.Secure.getInt(act.contentResolver, Settings.Secure.LOCATION_MODE)
            if (mode != Settings.Secure.LOCATION_MODE_OFF) {
                Log.i(TAG, "isLocationEnabled: true via Settings.Secure.LOCATION_MODE ($mode)")
                return true
            }
        } catch (e: Exception) {}

        // 5. Wenn Berechtigung da ist und Listener läuft
        if (hasLocationPermission()) {
            return true
        }

        return false
    }

    @UsedByGodot
    fun hasLocationPermission(): Boolean {
        val act = activity ?: return false
        val fine = ContextCompat.checkSelfPermission(act, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(act, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasPerm = fine || coarse
        return hasPerm
    }

    @UsedByGodot
    fun requestLocationPermission() {
        val act = activity ?: return
        Log.i(TAG, "REQUESTING_LOCATION_PERMISSIONS on UI thread...")
        act.runOnUiThread {
            ActivityCompat.requestPermissions(
                act,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    @UsedByGodot
    fun openAppSettings() {
        val act = activity ?: return
        Log.i(TAG, "OPENING_APP_SETTINGS for ${act.packageName}...")
        act.runOnUiThread {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:" + act.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                act.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "OPENING_APP_SETTINGS failed", e)
            }
        }
    }

    @UsedByGodot
    fun openLocationSettings() {
        val act = activity ?: return
        Log.i(TAG, "OPENING_LOCATION_SETTINGS...")
        act.runOnUiThread {
            try {
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                act.startActivity(intent)
            } catch (e: Exception) {
                openAppSettings()
            }
        }
    }

    @UsedByGodot
    fun isListeningForGeolocationUpdates() = isListeningForGeolocationUpdates

    @UsedByGodot
    fun startLocationUpdates(): Boolean {
        return startGeolocationListener(1000L, 0.0f)
    }

    @UsedByGodot
    fun startGeolocationListener(minTimeMs: Long, minDistanceM: Float): Boolean {
        val act = activity ?: return false

        act.runOnUiThread {
            // 1. Google FusedLocationProviderClient initialisieren
            try {
                if (fusedClient == null) {
                    fusedClient = LocationServices.getFusedLocationProviderClient(act)
                }

                val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, minTimeMs)
                    .setMinUpdateDistanceMeters(minDistanceM)
                    .setMinUpdateIntervalMillis(minTimeMs / 2)
                    .setWaitForAccurateLocation(false)
                    .build()

                fusedLocationCallback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        val loc = result.lastLocation ?: return
                        handleNewLocation(loc, "fused")
                    }
                }

                fusedClient?.requestLocationUpdates(
                    locationRequest,
                    fusedLocationCallback!!,
                    Looper.getMainLooper()
                )?.addOnSuccessListener {
                    Log.i(TAG, "FUSED_LOCATION_LISTENER: Successfully registered with PRIORITY_HIGH_ACCURACY!")
                    isListeningForGeolocationUpdates = true
                }?.addOnFailureListener { e ->
                    Log.e(TAG, "FUSED_LOCATION_LISTENER_FAILED: Falling back to native LocationManager", e)
                }

                // Letzten bekannten Fused-Standort sofort abholen
                fusedClient?.lastLocation?.addOnSuccessListener { loc ->
                    if (loc != null) {
                        Log.i(TAG, "FUSED_LAST_KNOWN_LOCATION: lat=${loc.latitude}, lon=${loc.longitude}")
                        handleNewLocation(loc, "fused_last_known")
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException while setting up FusedLocationProviderClient", e)
            } catch (e: Exception) {
                Log.e(TAG, "Exception while setting up FusedLocationProviderClient", e)
            }

            // 2. Paralleler nativer LocationManager Listener als 100% Fallback
            val lm = getLocationManager()
            if (lm != null) {
                var registeredCount = 0
                val providers = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                )

                for (provider in providers) {
                    try {
                        lm.requestLocationUpdates(
                            provider,
                            minTimeMs,
                            minDistanceM,
                            nativeLocationListener,
                            Looper.getMainLooper()
                        )
                        registeredCount++
                        Log.i(TAG, "NATIVE_LOCATION_LISTENER_ATTACHED: provider=$provider")
                    } catch (e: Exception) {}
                }
                lastRegisteredProvidersCount = registeredCount
                isListeningForGeolocationUpdates = true
            }
        }

        return true
    }

    @UsedByGodot
    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(): Dictionary {
        val dict = Dictionary()
        val loc = lastLocation ?: getBestLastKnownLocation()
        if (loc != null) {
            dict["latitude"] = loc.latitude
            dict["longitude"] = loc.longitude
            dict["accuracy"] = loc.accuracy.toDouble()
            dict["altitude"] = loc.altitude
            dict["speed"] = loc.speed.toDouble()
            dict["time"] = loc.time
            dict["provider"] = loc.provider ?: "network"
            dict["fixes"] = fixesReceived
        }
        return dict
    }

    @UsedByGodot
    fun getDiagnosticInfo(): Dictionary {
        val dict = Dictionary()
        val lm = getLocationManager()

        val fine = hasLocationPermission()
        val hwOn = isLocationEnabled()

        var gpsEnabled = false
        var netEnabled = false

        if (lm != null) {
            try {
                gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            } catch (e: Exception) {}
            try {
                netEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            } catch (e: Exception) {}
        }

        dict["has_fine_permission"] = fine
        dict["has_coarse_permission"] = fine
        dict["has_permission"] = fine
        dict["is_location_enabled"] = hwOn
        dict["gps_enabled"] = gpsEnabled
        dict["network_enabled"] = netEnabled
        dict["is_listening"] = isListeningForGeolocationUpdates
        dict["fixes_received"] = fixesReceived
        dict["registered_providers"] = lastRegisteredProvidersCount

        val loc = lastLocation ?: getBestLastKnownLocation()
        if (loc != null) {
            dict["last_latitude"] = loc.latitude
            dict["last_longitude"] = loc.longitude
            dict["last_accuracy"] = loc.accuracy.toDouble()
            dict["last_provider"] = loc.provider ?: "fused"
            dict["last_time"] = loc.time
        } else {
            dict["last_latitude"] = 0.0
            dict["last_longitude"] = 0.0
            dict["last_accuracy"] = 0.0
            dict["last_provider"] = "fused"
            dict["last_time"] = 0L
        }

        return dict
    }

    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocation(): Location? {
        val lm = getLocationManager() ?: return null
        var bestLocation: Location? = null

        for (provider in lm.allProviders) {
            try {
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || loc.accuracy < bestLocation.accuracy || loc.time > bestLocation.time) {
                    bestLocation = loc
                }
            } catch (e: Exception) {}
        }
        return bestLocation
    }

    @UsedByGodot
    fun stopGeolocationListener() {
        val act = activity ?: return
        act.runOnUiThread {
            try {
                if (fusedLocationCallback != null && fusedClient != null) {
                    fusedClient?.removeLocationUpdates(fusedLocationCallback!!)
                }
                val lm = getLocationManager()
                lm?.removeUpdates(nativeLocationListener)
                isListeningForGeolocationUpdates = false
                Log.i(TAG, "STOPPED_ALL_GEOLOCATION_UPDATES.")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping location updates", e)
            }
        }
    }

    override fun onMainResume() {
        super.onMainResume()
        startLocationUpdates()
    }

    override fun onMainRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>?,
        grantResults: IntArray?
    ) {
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            val granted = grantResults?.any { it == PackageManager.PERMISSION_GRANTED } ?: false
            emitSignal(locationPermissionSignal.name, granted)
            if (granted) {
                startLocationUpdates()
            }
        }
    }
}
