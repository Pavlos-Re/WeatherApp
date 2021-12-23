package com.example.weatherapp

import android.Manifest
import android.annotation.SuppressLint
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
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.weatherapp.models.WeatherResponse
import com.example.weatherapp.network.WeatherService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import com.google.android.gms.location.*
import retrofit2.*
import retrofit2.converter.gson.GsonConverterFactory
import com.example.weatherapp.*
import com.google.gson.Gson
import kotlinx.android.synthetic.main.activity_main.*
import java.text.SimpleDateFormat
import java.util.*


class MainActivity : AppCompatActivity() {

    private lateinit var mFusedLocationClient: FusedLocationProviderClient

    private var mProgressDialog: Dialog? = null

    private lateinit var mSharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        mSharedPreferences = getSharedPreferences(Constants.PREFERENCES_NAME, Context.MODE_PRIVATE)

        setUpUI()

        if (!isLocationEnabled()) {
            Toast.makeText(this, "Please turn on your location provider", Toast.LENGTH_SHORT).show()

            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(intent)
        } else {
            Dexter.withActivity(this)
                .withPermissions(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ).withListener(object : MultiplePermissionsListener {
                    override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
                        if (report!!.areAllPermissionsGranted()) {
                            requestLocationData()
                        }

                        if (report.isAnyPermissionPermanentlyDenied) {
                            Toast.makeText(
                                this@MainActivity,
                                "You have denied location permission. Please allow it is mandatory.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    override fun onPermissionRationaleShouldBeShown(
                        permissions: MutableList<PermissionRequest>?,
                        token: PermissionToken?
                    ) {
                        showRationalDialogForPermissions()
                    }
                }).onSameThread().check()
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager: LocationManager =
            getSystemService(LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(
            LocationManager.NETWORK_PROVIDER
        )
    }

    private fun showCustomDialog() {
        mProgressDialog = Dialog(this)

        mProgressDialog!!.setContentView(R.layout.dialog_custom_progress)

        mProgressDialog!!.show()
    }

    private fun hideProgressDialog() {
        if (mProgressDialog != null) {
            mProgressDialog!!.dismiss()
        }
    }

    private fun showRationalDialogForPermissions() {
        AlertDialog.Builder(this)
            .setMessage("It Looks like you have turned off permissions required for this feature. It can be enabled under Application Settings")
            .setPositiveButton(
                "GO TO SETTINGS"
            ) { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    val uri = Uri.fromParts("package", packageName, null)
                    intent.data = uri
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    e.printStackTrace()
                }
            }
            .setNegativeButton("Cancel") { dialog,
                                           _ ->
                dialog.dismiss()
            }.show()
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationData() {

        val mLocationRequest = LocationRequest()
        mLocationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY

        mFusedLocationClient.requestLocationUpdates(
            mLocationRequest, mLocationCallback,
            Looper.myLooper()!!
        )
    }

    private val mLocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val mLastLocation: Location = locationResult.lastLocation
            val latitude = mLastLocation.latitude
            Log.i("Current Latitude", "$latitude")

            val longitude = mLastLocation.longitude
            Log.i("Current Longitude", "$longitude")

            getLocationWeatherDetails(latitude, longitude)
        }

    }

    private fun getLocationWeatherDetails(latitude: Double, longitude: Double) {
        if (Constants.isNetWorkAvalable(this)) {

            val retrofit: Retrofit = Retrofit.Builder()
                                    .baseUrl(Constants.BASE_URL)
                                    .addConverterFactory(GsonConverterFactory.create())
                                    .build()

            val service : WeatherService = retrofit.create(WeatherService::class.java)

            val listCall: Call<WeatherResponse> = service.getWeather(
                latitude, longitude, Constants.METRIC_UNIT, Constants.APP_ID
            )

            showCustomDialog()

            listCall.enqueue(object : Callback<WeatherResponse> {
                override fun onFailure(call: Call<WeatherResponse>, t: Throwable) {

                    hideProgressDialog()

                    Log.e("ERROR", t.message.toString())
                }

                override fun onResponse(
                    call: Call<WeatherResponse>,
                    response: Response<WeatherResponse>
                ) {
                    if(response.isSuccessful) {

                        hideProgressDialog()

                        val weatherList: WeatherResponse = response.body()!!

                        val weatherResponseJsonString = Gson().toJson(weatherList)
                        val editor = mSharedPreferences.edit()
                        editor.putString(Constants.WEATHER_RESPONSE_DATA, weatherResponseJsonString)
                        editor.apply()


                        setUpUI()
                        Log.i("Response Result", "$weatherList")

                    } else {
                        val rc = response.code()
                        when(rc) {
                            400 -> {
                                Log.e("Error 400", "Bad connection")
                            }
                            404 -> {
                                Log.e("Error 404", "Not found")
                            } else -> {
                                Log.e("ERROR", "!!--ERROR--!!")
                            }
                        }
                    }
                }
            })
        }
        else {
           Toast.makeText(this@MainActivity, "There is not a connection to the Internet",Toast.LENGTH_SHORT).show()
        }
    }

    private fun setUpUI() {

        val weatherResponseJsonString = mSharedPreferences.getString(Constants.WEATHER_RESPONSE_DATA, "")

        if(!weatherResponseJsonString.isNullOrEmpty()) {

            val weatherList = Gson().fromJson(weatherResponseJsonString, WeatherResponse::class.java)

            for(i in weatherList.weather.indices) {

                Log.i("Weather name", "weatherList.weather.toString()")

                tv_main.text = weatherList.weather[i].main
                tv_main_description.text = weatherList.weather[i].description
                tv_temp.text = weatherList.main.temp.toString() + getUnit(application.resources.configuration.locales.toString())

                tv_humidity.text = weatherList.main.humidity.toString() + " per cent"
                tv_min.text = weatherList.main.temp_min.toString() + " min"
                tv_max.text = weatherList.main.temp_max.toString() + " max"
                tv_speed.text = weatherList.wind.speed.toString()
                tv_name.text = weatherList.name
                tv_country.text = weatherList.sys.country

                tv_sunrise_time.text = unixTime(weatherList.sys.sunrise)
                tv_sunset_time.text = unixTime(weatherList.sys.sunset)

                when(weatherList.weather[i].icon) {
                    "01d" -> iv_main.setImageResource(R.drawable.sunny)
                    "02d" -> iv_main.setImageResource(R.drawable.cloud)
                    "03d" -> iv_main.setImageResource(R.drawable.cloud)
                    "04d" -> iv_main.setImageResource(R.drawable.cloud)
                    "04n" -> iv_main.setImageResource(R.drawable.cloud)
                    "10d" -> iv_main.setImageResource(R.drawable.rain)
                    "11d" -> iv_main.setImageResource(R.drawable.storm)
                    "13d" -> iv_main.setImageResource(R.drawable.snowflake)
                    "01n" -> iv_main.setImageResource(R.drawable.cloud)
                    "02n" -> iv_main.setImageResource(R.drawable.cloud)
                    "03n" -> iv_main.setImageResource(R.drawable.cloud)
                    "10n" -> iv_main.setImageResource(R.drawable.cloud)
                    "11n" -> iv_main.setImageResource(R.drawable.rain)
                    "13n" -> iv_main.setImageResource(R.drawable.snowflake)
                }

            }

        }

    }

    private fun getUnit(value: String): String {
        var value = "°C"
        if ("US" == value || "LR" == value || "MM" == value) {
            value = "°F"
        }
        return value
    }

    private fun unixTime(timex: Long) : String? {
        val date = Date(timex * 1000L)
        val sdf = SimpleDateFormat("HH:mm", Locale.US)
        sdf.timeZone = TimeZone.getDefault()

        return sdf.format(date)

    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when(item.itemId) {
            R.id.action_refresh -> {
                requestLocationData()
                true
            } else -> super.onOptionsItemSelected(item)
        }
    }

}
