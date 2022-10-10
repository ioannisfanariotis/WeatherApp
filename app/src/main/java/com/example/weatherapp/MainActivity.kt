package com.example.weatherapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import com.example.weatherapp.databinding.ActivityMainBinding
import com.example.weatherapp.models.WeatherResponse
import com.example.weatherapp.network.WeatherService
import com.google.android.gms.location.*
import com.google.gson.Gson
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private var binding: ActivityMainBinding? = null
    private lateinit var fusedLocation: FusedLocationProviderClient
    private var myLoader: Dialog? = null
    private lateinit var preferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding?.root)

        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        preferences=getSharedPreferences(Constants.PREFERENCE_NAME, Context.MODE_PRIVATE)
        setupUI()

        if (!locationEnabled()){
            Toast.makeText(this, "Access to Location denied", Toast.LENGTH_SHORT).show()
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(intent)
        }else{
            Dexter.withContext(this).withPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                .withListener(object: MultiplePermissionsListener{
                    override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
                        if (report!!.areAllPermissionsGranted()){
                            locationRequest()
                        }
                        if (report.isAnyPermissionPermanentlyDenied){
                            Toast.makeText(this@MainActivity, "Access to Location denied", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onPermissionRationaleShouldBeShown(permissions: MutableList<PermissionRequest>?, token: PermissionToken?) {
                        permissionRationalDialog()
                    }
                }).onSameThread().check()
        }
    }

    private fun locationEnabled(): Boolean{
        val locationManager: LocationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun permissionRationalDialog(){
        AlertDialog.Builder(this).setMessage("All permissions not granted.\nChange that in Settings").setPositiveButton("GO TO SETTINGS"){
                _, _ ->
            try{
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }catch (e: ActivityNotFoundException){
                e.printStackTrace()
            }
        }.setNegativeButton("CANCEL"){ dialog, _ ->
            dialog.dismiss()
        }.show()
    }

    @SuppressLint("MissingPermission")
    private fun locationRequest(){
        startLoading()
        val request = LocationRequest.create().apply {
            interval = 1000
            priority = Priority.PRIORITY_HIGH_ACCURACY
            numUpdates = 1
        }
        fusedLocation.requestLocationUpdates(request, locationCallback, Looper.myLooper())
    }

    private val locationCallback = object : LocationCallback(){
        override fun onLocationResult(locationResult: LocationResult) {
            val lastLocation: Location? = locationResult.lastLocation
            val latitude = lastLocation!!.latitude
            Log.i("Current latitude: ", "$latitude")
            val longitude = lastLocation.longitude
            Log.i("Current longitude: ", "$longitude")
            getWeatherDetails(latitude, longitude)
        }
    }

    private fun getWeatherDetails(latitude: Double, longitude: Double){
        if (Constants.isNetworkAvailable(this)){
            val retrofit: Retrofit = Retrofit.Builder().baseUrl(Constants.BASE_URL).addConverterFactory(GsonConverterFactory.create()).build()
            val service: WeatherService = retrofit.create(WeatherService::class.java)
            val listCall: Call<WeatherResponse> = service.getWeather(latitude, longitude, Constants.METRIC_UNIT, Constants.APP_ID)
            listCall.enqueue(object : Callback<WeatherResponse>{
                override fun onResponse(call: Call<WeatherResponse>, response: Response<WeatherResponse>) {
                    if (response.isSuccessful){
                        cancelLoading()
                        val weatherList = response.body()!!
                        val jsonString = Gson().toJson(weatherList)
                        val editor = preferences.edit()
                        editor.putString(Constants.RESPONSE_DATA, jsonString)
                        editor.apply()
                        setupUI()
                        Log.i("Response Result: ", "$weatherList")
                    }else{
                        val code = response.code()
                        when(code){
                            400 -> {Log.e("Error 400", "Bad Connection")}
                            404 -> {Log.e("Error 404", "Not Found")}
                            else ->{Log.e("Error", "Generic Error")}
                        }
                    }
                }

                override fun onFailure(call: Call<WeatherResponse>, t: Throwable) {
                    Log.e("Error", t.message.toString())
                    cancelLoading()
                }
            })
        }else{
            Toast.makeText(this@MainActivity, "No Internet Connection", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startLoading(){
        myLoader = Dialog(this)
        myLoader?.setContentView(R.layout.loading_spinner)
        myLoader?.show()
    }

    private fun cancelLoading(){
        if (myLoader!=null){
            myLoader?.dismiss()
            myLoader = null
        }
    }

    private fun setupUI(){

        val jsonString = preferences.getString(Constants.RESPONSE_DATA, "")
        if (!jsonString.isNullOrEmpty()){
            val weatherList = Gson().fromJson(jsonString, WeatherResponse::class.java)
            for (i in weatherList.weather.indices){
                Log.i("Weather name: ", weatherList.weather.toString())
                binding?.weatherTitle?.text = weatherList.weather[i].main
                binding?.condition?.text = weatherList.weather[i].description
                binding?.degree?.text = weatherList.main.temp.toString() + getUnit(application.resources.configuration.toString())
                binding?.percent?.text = weatherList.main.humidity.toString() + "%"
                binding?.min?.text = weatherList.main.temp_min.toString() + " min"
                binding?.max?.text = weatherList.main.temp_max.toString() + " max"
                binding?.speed?.text = weatherList.wind.speed.toString()
                binding?.name?.text = weatherList.name
                binding?.country?.text = weatherList.sys.country
                binding?.sunriseTime?.text = getTime(weatherList.sys.sunrise)
                binding?.sunsetTime?.text = getTime(weatherList.sys.sunset)

                when(weatherList.weather[i].icon){
                    "01d" -> binding?.main?.setImageResource(R.drawable.sunny)
                    "01n" -> binding?.main?.setImageResource(R.drawable.cloud)
                    "02d" -> binding?.main?.setImageResource(R.drawable.cloud)
                    "02n" -> binding?.main?.setImageResource(R.drawable.cloud)
                    "03d" -> binding?.main?.setImageResource(R.drawable.cloud)
                    "03n" -> binding?.main?.setImageResource(R.drawable.cloud)
                    "04d" -> binding?.main?.setImageResource(R.drawable.cloud)
                    "04n" -> binding?.main?.setImageResource(R.drawable.cloud)
                    "09d" -> binding?.main?.setImageResource(R.drawable.rain)
                    "09n" -> binding?.main?.setImageResource(R.drawable.rain)
                    "10d" -> binding?.main?.setImageResource(R.drawable.storm)
                    "10n" -> binding?.main?.setImageResource(R.drawable.storm)
                    "11d" -> binding?.main?.setImageResource(R.drawable.storm)
                    "11n" -> binding?.main?.setImageResource(R.drawable.storm)
                    "13d" -> binding?.main?.setImageResource(R.drawable.snowflake)
                    "13n" -> binding?.main?.setImageResource(R.drawable.snowflake)
                }
            }
        }
    }

    private fun getUnit(value: String): String{
        var value = "°C"
        if ("US" == value || "LR" == value || "MM" == value){
            value = "°F"
        }
        return value
    }

    private fun getTime(time: Long): String{
        val date = Date(time*1000L)
        val sdf = SimpleDateFormat("HH:mm", Locale.UK)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(date)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.refresh -> {
                locationRequest()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding = null
    }
}