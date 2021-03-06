package com.codehospital.sayit
/**
 *
 * Created by maysam on 17/02/17.
 *
 */


import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.experimental.runBlocking
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.*
import retrofit2.http.Headers
import java.io.File
import java.io.IOException
import java.util.*

object Platform {
    fun runLater(fn: () -> Unit) {
        fn.invoke()
//        object : Thread() {
//
//        }.start()
    }

}

object api {
    var hard_password: String? = "newpassword123"
    var hard_username: String? = "rgaudet@vtexvsi.com"
    var hard_url: String? = "https://eval.nvoq.com:443"

    private var available = true
    var loggedIn : SimpleBooleanProperty = SimpleBooleanProperty(false)
    val username = SimpleStringProperty(null)
    var password = SimpleStringProperty(null)
    private var service: NVOQService? = null

    class HttpAuthInterceptor(private val httpUsername: String, private val httpPassword: String) : Interceptor {
        @Throws(IOException::class)
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val newRequest = chain
                    .request()
                    .newBuilder()
                    .addHeader("Authorization", Credentials.basic(httpUsername, httpPassword))
                    .build()
            return chain.proceed(newRequest)
        }
    }

    interface NVOQService {
        @GET("SCDMValidate/login") fun login(): Call<String>

        @GET("SCVmcServices/changePassword")
        fun changePassword(@Query("username") username: String, @Query("password") password: String): Call<String>

        @FormUrlEncoded
        @POST("SCDictation/rest/sandcherry/factory/dictations/")
        fun getPostLocation(@Field("profile") username: String, @Field("streaming") streaming: Boolean, @Field("audio-format") audioFormat: String, @Field("client-observer-id") rand: Int): Call<ResponseBody>

        @FormUrlEncoded
        @POST("SCDictation/rest/sandcherry/factory/dictations/")
        fun getPostLocation1(@Field("profile") username: String,
                             @Field("audio-url") audioUrl: String,
                             @Field("audio-format") audioFormat: String,
                             @Field("client-observer-id") id:String,
                             @Field("client-observer-expire-seconds") expire:String
        ): Call<ResponseBody>

        @Multipart
        @POST
        fun sendAudioFile(@Url url: String, @Part file: MultipartBody.Part): Call<String>
//        curl -X POST -u ${username}:${password} --data-binary "@${filename}" ${location}/audio

        @FormUrlEncoded
        @POST
        fun sendingDone(@Url url: String, @Field("value") value: Boolean): Call<String>
//        curl -X POST -u ${username}:${password} -d "value=true" ${location}/audio/done &> /dev/null

        @GET
        fun getDictationText(@Url url: String, @Query("X-Content-Range") range: String): Call<String>

        @GET fun get(@Url url: String): Call<String>

        @POST fun post(@Url url: String): Call<String>

        @Headers("Content-Type:audio/x-wav")
        @POST("SCFileserver/audio")
        fun uploadAudio(@Body file: RequestBody): Call<String>
        //    @DELETE("matchingwebservice/form") fun deleteUploadedAudio(@Url audioUrl: String): Call<String>
        @DELETE fun deleteUploadedAudio(@Url audioUrl: String): Call<String>
    }

    fun uploadAudio(context: Context, file: File, setStatus: (String) -> Unit, setRetreivedText: (String) -> Unit) {
        if (!available) {
            Log.w("API","api is busy with previous request")
            Thread {
                Toast.makeText(context, "api is busy with previous request", Toast.LENGTH_SHORT).show()
            }.start()
            return
        }
        available = false
        abstract class notFailingCallback<T> : Callback<T> {
            override fun onFailure(p0: Call<T>?, p1: Throwable?) {
                setStatus("failed: "+p1?.message)
                available = true
            }
        }
        val mediaType = when (file.extension) {
            "ogg" -> "audio/ogg"
            "wav" -> "audio/x-wav"
            else -> ""
        }
        // create RequestBody instance from file
        val requestFile = RequestBody.create(MediaType.parse(mediaType), file)
        service().uploadAudio(requestFile).enqueue(object : notFailingCallback<String>() {
            override fun onResponse(p0: Call<String>?, response: retrofit2.Response<String>?) {
                Log.d("onResponse", response?.errorBody().toString())
                Log.d("onResponse", response?.message())
                Log.d("onResponse", response?.headers().toString())
                val audioLocation = response?.headers()?.get("Location")!!
                setStatus("audioLocation = $audioLocation")
                val audioFormat = "pcm-16khz"
                service().getPostLocation1(username.value!!, audioLocation, audioFormat, "owner", "3600").enqueue(object : notFailingCallback<ResponseBody>() {
                    override fun onResponse(p0: Call<ResponseBody>?, response: Response<ResponseBody>?) {
                        val dictationLocation = response?.headers()?.get("Location")
                        setStatus("dictationLocation = $dictationLocation")
                        fun checkIfDone(): Unit {
                            service().get(dictationLocation+"/done").enqueue(object: notFailingCallback<String>() {
                                override fun onResponse(p0: Call<String>?, done_response: Response<String>?) {
                                    val done = done_response?.body()?.toBoolean() ?: false
                                    setStatus("API: Done body = ${done_response?.body()} and done = $done")
                                    if (done) service().get(dictationLocation+"/text").enqueue(object : notFailingCallback<String>() {
                                        override fun onResponse(p0: Call<String>?, text_response: Response<String>?) {
                                            setStatus("retreived text body is '${text_response?.body()}'")
                                            text_response?.body()?.let { setRetreivedText(it) }
                                            Thread {
                                                val status_response = service().get(dictationLocation + "/stats/Status").execute()
                                                Log.i("API", "Status body = ${status_response?.body()}")
                                                service().deleteUploadedAudio(audioLocation).execute()
                                                val observer_response = service().get(dictationLocation + "/stats/ClientObserverUrl").execute()
                                                val observerLocation = observer_response.body()
                                                service().get(observerLocation + "/deregister").execute()
                                                available = true
                                            }.start()
                                        }
                                    }) else {
                                        service().get(dictationLocation+"/stats/Status").enqueue(object : notFailingCallback<String>() {
                                            override fun onResponse(p0: Call<String>?, status_response: Response<String>?) {
                                                setStatus("Status: ${status_response?.body()}")
                                                checkIfDone()
                                            }
                                        })
                                    }
                                }
                            } )

                        }
                        checkIfDone()
                    }
                })
            }
        })
    }

    var send_location = SimpleStringProperty(null)
    fun startSendingAudioFiles(file_extension: String, updateStatus: (String?) -> Unit, appendText: (String?) -> Unit, onFinish: () -> Unit) {
        if (!available) {
            Log.w("API","api is busy with previous request")
            return
        }
        available = false
        val audioFormat = when (file_extension) {
            "ogg" -> "ogg"
            "wav" -> "pcm-16khz"
            else -> throw Exception("Invalid file extension")
        }
        service().getPostLocation(username.value!!, true, audioFormat, Random().nextInt()).enqueue(object : Callback<ResponseBody> {
            override fun onFailure(p0: Call<ResponseBody>?, p1: Throwable?) {
                Log.e("API",p1?.message)
                available = true
            }

            override fun onResponse(p0: Call<ResponseBody>?, result: retrofit2.Response<ResponseBody>?) {
                val location = result?.headers()?.get("Location")
                send_location.value = location
                Log.i("API","location is $location")
                checkDictationStatus(location, updateStatus, appendText, false, onFinish)
            }
        })
    }

    var isCheckingDictation = false

    fun checkDictationStatus(location: String?, update_status: (String?) -> Unit, update_text: (String?) -> Unit, insider_call: Boolean, onFinish: () -> Unit) {
        !insider_call && isCheckingDictation && return
        isCheckingDictation = true
        fun onFailure(p1: Throwable?): Unit {
            Log.e("API",p1?.message)
            available = true
            isCheckingDictation = false
        }
        service().get("$location/stats/Status").enqueue(object : Callback<String> {
            override fun onResponse(p0: Call<String>?, response: retrofit2.Response<String>?) {
                Log.i("API","stats/Status => ${response?.message()}")
            }
            override fun onFailure(p0: Call<String>?, p1: Throwable?) = onFailure(p1)
        })
        service().getDictationText("$location/text", "bytes 0-").enqueue(object : Callback<String> {
            override fun onResponse(p0: Call<String>?, result: retrofit2.Response<String>?) {
                Log.i("API",result.toString())
                try {
                    if (result?.code() == 200) {
                        val text = result.body()
                        Log.i("API","retreived text is '$text'")
                        if (!text.isNullOrEmpty()) {
                            update_text(text!!)
                        }
                        if (result.headers()?.get("X-nvoq-done")?.toBoolean()!!) {
                            service().get("$location/stats/ClientObserverUrl").enqueue(object : Callback<String> {
                                override fun onResponse(p0: Call<String>?, result: retrofit2.Response<String>?) {
                                    val observerLocation = result?.body()
                                    service().post("$observerLocation/post").enqueue(object : Callback<String> {
                                        override fun onResponse(p0: Call<String>?, result: retrofit2.Response<String>?) {
                                            Platform.runLater {
                                                update_status("finished retrieving text")
                                                onFinish()
                                            }
                                            available = true
                                            isCheckingDictation = false
                                        }
                                        override fun onFailure(p0: Call<String>?, p1: Throwable?) = onFailure(p1)
                                    })
                                }

                                override fun onFailure(p0: Call<String>?, p1: Throwable?) = onFailure(p1)
                            })
                        } else {
                            Thread.sleep(1000)
                            checkDictationStatus(location, update_status, update_text, true, onFinish)
                        }
                    } else {
                        Log.e("API","ERROR: ${result.toString()}")
                        Thread.sleep(1000)
                        checkDictationStatus(location, update_status, update_text, true, onFinish)
                    }
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    Log.e("API",ex.message)
                    Thread.sleep(1000)
                    checkDictationStatus(location, update_status, update_text, true, onFinish)
                }
            }
            override fun onFailure(p0: Call<String>?, p1: Throwable?) = onFailure(p1)
        })
    }

    suspend fun wait_for_location() {
        while (send_location.value === null) {
            Thread.sleep(100)
        }
    }

    fun sendAudioFile(file: File, update_status: (String?) -> Unit) {
        runBlocking { wait_for_location() }
        assert(!send_location.value.isNullOrEmpty())
        val location = send_location.value
        Platform.runLater { update_status("preparing to send ${file.name} to server, please wait") }

        val mediaType = when (file.extension) {
            "ogg" -> "audio/ogg"
            "wav" -> "audio/x-wav"
            else -> ""
        }
        // create RequestBody instance from file
        val requestFile = RequestBody.create(MediaType.parse(mediaType), file)
        val body = MultipartBody.Part.createFormData("uploaded_file", file.name, requestFile)

        service().sendAudioFile("$location/audio", body).enqueue(object : Callback<String> {
            override fun onFailure(p0: Call<String>?, p1: Throwable?) {
                Log.e("API",p1?.message)
                available = true
            }

            override fun onResponse(p0: Call<String>?, result: retrofit2.Response<String>?) {
//        service().sendingDone("$location/audio/done", true).enqueue(object : Callback<String> {
//          override fun onResponse(p0: Call<String>?, result: retrofit2.Response<String>?) {
////            checkDictationStatus(update_status, update_text, false)
//          }
//
//          override fun onFailure(p0: Call<String>?, p1: Throwable?) {
//            Log.e("API",p1?.message)
//            available = true
//          }
//        })
            }
        })
    }

    fun sendAudioFile(files: Array<File>, update_status: (String?) -> Unit, update_text: (String?) -> Unit, onFinish: () -> Unit) {
        files.isEmpty() && return
        if (!available) {
            Log.w("API","api is busy with previous request")
            return
        }
        available = false
        fun beforeEnd() {
            available = true
            files.forEach { file -> file.delete() }
        }
        if (files.size > 1)
            update_status("preparing to send ${files.size} files to server, please wait")
        else
            update_status("preparing to send ${files[0].name} to server, please wait")
//      Supported strings include one of: null, pcm-16khz, ulaw, ogg
        val audioFormat = when (files.first().extension) {
            "ogg" -> "ogg"
            "wav" -> "pcm-16khz"
            else -> ""
        }
        var file_index = 0
        fun sendFile(location: String, file: File) {
            Log.i("API","file chosen = " + file.absolutePath)
            Platform.runLater {
                if (files.size > 1)
                    update_status("sending ${file_index + 1} of ${files.size} files to server, please wait")
                else
                    update_status("sending ${files[0].name} to server, please wait")
            }
            val mediaType = when (file.extension) {
                "ogg" -> "audio/ogg"
                "wav" -> "audio/x-wav"
                else -> ""
            }
            // create RequestBody instance from file
            val requestFile = RequestBody.create(MediaType.parse(mediaType), file)
            val body = MultipartBody.Part.createFormData("uploaded_file", file.name, requestFile)

            service().sendAudioFile("$location/audio", body).enqueue(object : Callback<String> {
                override fun onResponse(p0: Call<String>?, result: retrofit2.Response<String>?) = if (file_index < files.size - 1) {
                    file_index++
                    sendFile(location, files[file_index])
                } else {
                    service().sendingDone("$location/audio/done", true).enqueue(object : Callback<String> {
                        override fun onResponse(p0: Call<String>?, result: retrofit2.Response<String>?) {
                            Platform.runLater { update_status("finished sending audio, waiting for text to come") }
                        }

                        override fun onFailure(p0: Call<String>?, p1: Throwable?) {
                            Log.e("API",p1?.message)
                            beforeEnd()
                        }
                    })

                }

                override fun onFailure(p0: Call<String>?, p1: Throwable?) {
                    Log.e("API",p1?.message)
                    // retry
                    sendFile(location, files[file_index])
                }
            })
        }
        service().getPostLocation(username.value!!, files.size > 1, audioFormat, Random().nextInt()).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(p0: Call<ResponseBody>?, result: retrofit2.Response<ResponseBody>?) {
                if (result?.isSuccessful!!) {
                    val location = result.headers()?.get("Location")
                    if (location != null) {
                        Log.i("API","before sendFile")
                        sendFile(location = location, file = files.first())
                        Log.i("API","after sendFile")
                        checkDictationStatus(location, update_status, update_text, false, onFinish)
                    }
                } else {
                    Log.e("API",result.errorBody().string())
                    beforeEnd()
                    Platform.runLater { update_status("Unable to get valid location") }
                }
            }

            override fun onFailure(p0: Call<ResponseBody>?, p1: Throwable?) {
                p1?.printStackTrace()
                Log.e("API",p1?.stackTrace?.joinToString(" - "))
                Log.e("API",p1?.message)
                beforeEnd()
            }
        })
    }

    var retrofit: Retrofit? = null

    fun service(): NVOQService {
        if (service == null) {
            if (username.value === null) {
                username.set(hard_username)
                password.set(hard_password)
            }
            Log.i("API","using ${username.value} and ${password.value}")
            val loggingInterceptor = HttpLoggingInterceptor()
            loggingInterceptor.level = HttpLoggingInterceptor.Level.HEADERS
            val client = OkHttpClient.Builder()
                    .addInterceptor(HttpAuthInterceptor(username.value!!, password.value!!))
//          .addInterceptor(loggingInterceptor)
                    .build()
            retrofit = Retrofit.Builder()
                    .client(client)
                    .baseUrl(hard_url)
                    .addConverterFactory(ScalarsConverterFactory.create())
                    .build()

            service = retrofit?.create(NVOQService::class.java)
        }
        return service!!
    }

    fun login(user: String, pass: String, onSuccess: () -> Unit, onFailure: () -> Unit) {
        Log.i("API","login as $user")
        username.set(user)
        password.set(pass)
        service = null
        service().login().enqueue(object : Callback<String> {
            override fun onFailure(p0: Call<String>?, p1: Throwable?) {
                Platform.runLater { onFailure() }
            }

            override fun onResponse(p0: Call<String>?, x: retrofit2.Response<String>?) {
                if (x?.isSuccessful!!) {
                    loggedIn.set(true)
//                    loggedIn.apply { this.value = true }
//                    compareValuesBy(lhs, rhs, {it.active}, {it.lastName}, {it.firstName})
                    Platform.runLater { onSuccess() }
                } else {
                    Platform.runLater { onFailure() }
                }
                Log.d("API","login attempt was " + x.message()) // Ok // Unauthorized
                Log.d("API","response body = " + x.body()) // dictation matching // null
            }
        })
    }

    fun change_password(new_password: String, onSuccess: () -> Unit, onFailure: () -> Unit) {
        service().changePassword(username.value!!, new_password).enqueue(object : Callback<String> {
            override fun onResponse(p0: Call<String>?, resp: retrofit2.Response<String>?) {
                Log.i("API","change password attempt was " + resp?.message()) // Ok // Unauthorized
                Log.d("API","response body = " + resp?.body()) // dictation matching // null
                Log.w("API","http header code = " + resp?.code()) // 200 // 401
                if (resp?.code() == 200) {
                    Platform.runLater { onSuccess() }
                } else {
                    Platform.runLater { onFailure() }
                }
            }

            override fun onFailure(p0: Call<String>?, p1: Throwable?) {
                Platform.runLater { onFailure() }
            }
        })
    }

    fun logout() {
        loggedIn.value = false
        username.value = null
        password.value = null
    }
}

class SimpleStringProperty(var value: String?) {
    fun  set(user: String?) {
        value = user
    }

}

class SimpleBooleanProperty(var value: Boolean?) {
    fun set(b: Boolean?) {
        value = b
    }

}
