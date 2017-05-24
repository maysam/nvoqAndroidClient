package com.codehospital.sayit

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.TargetApi
import android.content.pm.PackageManager
import android.media.*
import android.support.v7.app.AppCompatActivity

import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.inputmethod.EditorInfo

import android.net.Uri
import android.support.v4.app.ActivityCompat
import android.util.Log
import android.widget.*
import android.widget.Toast.LENGTH_LONG
import java.io.*

/**
 * A login screen that offers login via email/password.
 */
class RecorderActivity : AppCompatActivity() {
    /**
     * Keep track of the login task to ensure we can cancel it if requested.
     */

    var waveFilename: String? = null
    val LOG_TAG = "AudioRecordTest"
    val REQUEST_RECORD_AUDIO_PERMISSION = 200

    var isRecording = false

    // Requesting permission to RECORD_AUDIO
    var permissionToRecordAccepted = false
    private val permissions = arrayOf(Manifest.permission.RECORD_AUDIO)

    // UI references.
    private var mEmailView: AutoCompleteTextView? = null
    private var mPasswordView: EditText? = null
    private var mProgressView: View? = null
    private var mLoginFormView: View? = null

    fun getResourceAsFile(resourcePath: String): File? {
        try {
            val assetManager = applicationContext.resources.assets // Use AsstManager to load SVM detector parameters into memory
            val fin = assetManager.open(resourcePath)            // Load parameter file to SVM detector
            val tempFile = File.createTempFile(fin.hashCode().toString(), resourcePath.split('.').last())
            tempFile.deleteOnExit()

            FileOutputStream(tempFile).use { out ->
                //copy stream
                val buffer = ByteArray(1024)
                var bytesRead: Int
                bytesRead = fin.read(buffer)
                while (bytesRead != -1) {
                    out.write(buffer, 0, bytesRead)
                    bytesRead = fin.read(buffer)
                }
            }
            return tempFile
        } catch (e: IOException) {
            e.printStackTrace()
            return null
        }

    }

    private var retreivedText: TextView? = null
    private var statusText: TextView? = null
    val setRetreivedText = fun(text: String): Unit {
        showProgress(false)
        retreivedText?.text = text
    }
    val setStatus = fun(text: String): Unit {
        showProgress(false)
        statusText?.text = text
        Log.i("Status", text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.recorder_activity)
        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)

        // Set up the login form.
        mEmailView = findViewById(R.id.email) as AutoCompleteTextView

        mPasswordView = findViewById(R.id.password) as EditText
        mPasswordView!!.setOnEditorActionListener(TextView.OnEditorActionListener { _, id, _ ->
            if (id == R.id.login || id == EditorInfo.IME_NULL) {
                attemptLogin()
                return@OnEditorActionListener true
            }
            false
        })
        retreivedText = findViewById(R.id.retrieved_text) as TextView
        statusText = findViewById(R.id.status_bar) as TextView
        val mEmailSignInButton = findViewById(R.id.email_sign_in_button) as Button
        mEmailSignInButton.setOnClickListener {
            //            attemptLogin()
            val file = getResourceAsFile("raw/extended_service.wav")
            if (file != null) {
                showProgress(true)
                api.uploadAudio(applicationContext, file, setStatus, setRetreivedText)
            }
        }

        val retryButton = findViewById(R.id.retry_button) as Button
        retryButton.setOnClickListener {
            if (waveFilename != null)
                api.uploadAudio(applicationContext, File(waveFilename), setStatus, setRetreivedText)
        }

        val playButton = findViewById(R.id.play_button) as Button
        playButton.setOnClickListener {
            if (waveFilename != null) {
                with(MediaPlayer()) {
                    setDataSource(waveFilename)
                    prepare()
                    start()
                }
            }
        }

        val recordButton = findViewById(R.id.record_button) as ToggleButton
        recordButton.setOnCheckedChangeListener { _, started ->
            if (started) {
                // start recording
                startRecording()
            } else {
                // stop recording
                stopRecording()
                // send audio file

            }
        }

        mLoginFormView = findViewById(R.id.login_form)
        mProgressView = findViewById(R.id.login_progress)
    }

    /**
     * Callback received when a permissions request has been completed.
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            permissionToRecordAccepted = grantResults[0] == PackageManager.PERMISSION_GRANTED
        }
        if (!permissionToRecordAccepted) finish()
    }

    private val frequency = 16000
    val channelConfiguration = AudioFormat.CHANNEL_IN_MONO
    val EncodingBitRate = AudioFormat.ENCODING_PCM_16BIT

    private fun startRecording() {
        val bitRate = AudioRecord.getMinBufferSize(frequency, channelConfiguration, EncodingBitRate)
        if (bitRate <= 0) {
            Toast.makeText(applicationContext, "bit rate not supported", LENGTH_LONG).show()
            return
        }
        val aRecorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                frequency, channelConfiguration,
                EncodingBitRate,
                bitRate)
        val aFileName = externalCacheDir.absolutePath + "/audiorecordtest.raw"
        isRecording = true
        val recBufSize = 1024
        Log.i(LOG_TAG, "bitrate is $bitRate")
        aRecorder.startRecording()
        fun writeAudioDataToFile(): Unit {
            val data = ByteArray(recBufSize)
            var os: FileOutputStream? = null

            try {
                os = FileOutputStream(aFileName)
            } catch (e: FileNotFoundException) {
                // TODO Auto-generated catch block
                e.printStackTrace()
            }


            if (null != os) {
                while (isRecording) {
                    val read = aRecorder.read(data, 0, recBufSize)
                    if (AudioRecord.ERROR_INVALID_OPERATION != read) {
                        try {
                            os.write(data)
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
                }

                try {
                    os.close()
                    waveFilename = externalCacheDir.absolutePath + "/audiorecordtest.wav"
                    Wave.copyTmpfileToWavfile(aFileName, waveFilename, frequency.toLong(), 1024)
                    aRecorder.stop()
                    aRecorder.release()
                    api.uploadAudio(applicationContext, File(waveFilename), setStatus, setRetreivedText)
                } catch (e: IOException) {
                    e.printStackTrace()
                }

            }
        }
//        recordingThread =
        Thread(Runnable {
            writeAudioDataToFile()
        }, "AudioRecorder Thread").start()
//        recordingThread?.start()
    }

    private fun stopRecording() {
        isRecording = false
        showProgress(true)
    }

    /**
     * Attempts to sign in or register the account specified by the login form.
     * If there are form errors (invalid email, missing fields, etc.), the
     * errors are presented and no actual login attempt is made.
     */
    private fun attemptLogin() {
        // Reset errors.
        mEmailView!!.error = null
        mPasswordView!!.error = null

        // Store values at the time of the login attempt.
        val email = mEmailView!!.text.toString()
        val password = mPasswordView!!.text.toString()

        var cancel = false
        var focusView: View? = null

        // Check for a valid password, if the user entered one.
        if (!TextUtils.isEmpty(password) && !isPasswordValid(password)) {
            mPasswordView!!.error = getString(R.string.error_invalid_password)
            focusView = mPasswordView
            cancel = true
        }

        // Check for a valid email address.
        if (TextUtils.isEmpty(email)) {
            mEmailView!!.error = getString(R.string.error_field_required)
            focusView = mEmailView
            cancel = true
        } else if (!isEmailValid(email)) {
            mEmailView!!.error = getString(R.string.error_invalid_email)
            focusView = mEmailView
            cancel = true
        }

        if (cancel) {
            // There was an error; don't attempt login and focus the first
            // form field with an error.
            focusView!!.requestFocus()
        } else {
            // Show a progress spinner, and kick off a background task to
            // perform the user login attempt.
            showProgress(true)

            val onSuccess = {
                showProgress(false)
//                finish()
                Toast.makeText(applicationContext, "login good", Toast.LENGTH_LONG).show()
            }

            val onFailure = {
                showProgress(false)
                mPasswordView!!.error = getString(R.string.error_incorrect_password)
                mPasswordView!!.requestFocus()
                Unit
            }

            api.login(email, password, onSuccess, onFailure)
        }
    }

    private fun isEmailValid(email: String): Boolean {
        //TODO: Replace this with your own logic
        val e: CharSequence = email
        return e.contains("@")
    }

    private fun isPasswordValid(password: String): Boolean {
        //TODO: Replace this with your own logic
        return password.length > 4
    }

    /**
     * Shows the progress UI and hides the login form.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR2)
    private fun showProgress(show: Boolean) {
        // On Honeycomb MR2 we have the ViewPropertyAnimator APIs, which allow
        // for very easy animations. If available, use these APIs to fade-in
        // the progress spinner.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2) {
            val shortAnimTime = resources.getInteger(android.R.integer.config_shortAnimTime)

            mLoginFormView!!.visibility = if (show) View.GONE else View.VISIBLE
            mLoginFormView!!.animate().setDuration(shortAnimTime.toLong()).alpha(
                    (if (show) 0 else 1).toFloat()).setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    mLoginFormView!!.visibility = if (show) View.GONE else View.VISIBLE
                }
            })

            mProgressView!!.visibility = if (show) View.VISIBLE else View.GONE
            mProgressView!!.animate().setDuration(shortAnimTime.toLong()).alpha(
                    (if (show) 1 else 0).toFloat()).setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    mProgressView!!.visibility = if (show) View.VISIBLE else View.GONE
                }
            })
        } else {
            // The ViewPropertyAnimator APIs are not available, so simply show
            // and hide the relevant UI components.
            mProgressView!!.visibility = if (show) View.VISIBLE else View.GONE
            mLoginFormView!!.visibility = if (show) View.GONE else View.VISIBLE
        }
    }
}

