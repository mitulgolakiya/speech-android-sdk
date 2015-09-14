/**
 * Copyright IBM Corporation 2015
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package com.ibm.cio.watsonsdk;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.java_websocket.util.Base64;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import com.ibm.cio.audio.IAudioConsumer;
import com.ibm.cio.audio.AudioCaptureThread;
import com.ibm.cio.audio.ChuckOggOpusEnc;
import com.ibm.cio.audio.ChuckRawEnc;
import com.ibm.cio.audio.RecognizerIntentService;
import com.ibm.cio.dto.SpeechConfiguration;
import com.ibm.cio.audio.ISpeechEncoder;
import com.ibm.cio.audio.ChuckWebSocketUploader;
import com.ibm.cio.audio.IChunkUploader;
import com.ibm.cio.audio.RecognizerIntentService.RecognizerBinder;
import com.ibm.cio.audio.RecognizerIntentService.State;
import com.ibm.cio.dto.QueryResult;
import com.ibm.cio.util.Logger;
import com.ibm.crl.speech.vad.RawAudioRecorder;

// HTTP library
import org.apache.http.HttpResponse;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.DefaultHttpClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**Speech Recognition Class for SDK functions
 * @author Viney Ugave (vaugave@us.ibm.com)
 *
 */
public class SpeechToText {
    protected static final String TAG = "SpeechToText";

    private String transcript;

    private Context appCtx;
    private SpeechConfiguration sConfig;
    private AudioCaptureThread audioCaptureThread = null;
    private boolean shouldStopRecording;
    private boolean doneUploadData;

    private IChunkUploader uploader = null;
    private Thread onHasDataThread;
    private SpeechDelegate delegate = null;
    private String username;
    private String password;
    private String model;
    private TokenProvider tokenProvider = null;
    private URI hostURL;

    /** Audio encoder. */
    private ISpeechEncoder encoder;
    /** Service to record audio. */
    private RecognizerIntentService mService;
    private boolean mStartRecording = false;
    /** Flag <code>true/<code>false</code>. <code>True</code> if recording service was connected. */
    private boolean mIsBound = false;
    /** Save audio data runnable. */
    private Runnable mRunnableBytes;
    /** Current offset of audio data. */
    private int currentOffset = 0;
    /** Flag <code>true/<code>false</code>. <code>True</code> if user has tapped on "X" button to dismiss recording diaLogger. */
    private volatile boolean isCancelled = false;
    /** Flag <code>true/<code>false</code>. <code>True</code> if user has tapped on mic button to stop recording process. */
    private volatile boolean stopByUser = false;
    /** Recorded audio data. */
    private BlockingQueue<byte[]> recordedData;
    /** Number chunk of recorded audio data. */
    private volatile int numberData = 0;
    /** Handler to schedule save audio data runnable. */
    private Handler mHandlerBytes = new Handler();
    /** Update the byte count every 250 ms. */
    private static final int TASK_BYTES_INTERVAL = 100;//250;
    /** Start the task almost immediately. */
    private static final int TASK_BYTES_DELAY = 100; //to be edit 10 = immediately
    /** Time interval to check for VAD pause / max time limit. */
    private static final int TASK_STOP_INTERVAL = 0;//600;
    /** Delay of stopping runnable. */
    private static final int TASK_STOP_DELAY = 1000;//1500;
    /** Stopping runnable. */
    private Runnable mRunnableStop;
    /** Max recording time in milliseconds (VAD timeout). */
    private int mMaxRecordingTime = 30000;
    /** Handler to schedule stopping runnable. */
    private Handler mHandlerStop = new Handler();
    /** Begin thinking (recognizing, query and return result) time. */
    private long beginThinking = 0;
    /** Max thinking time in milliseconds. */
    private int THINKING_TIMEOUT = 500; // 30000
    /** UPLOADING TIIMEOUT  */
    private int UPLOADING_TIMEOUT = 5000; // default duration of closing connection

    /**
     * Constructor
     */
    public SpeechToText() {
        this.setTimeout(0);
        this.sConfig = null;
    }

    /**Speech Recognition Shared Instance
     *
     */
    private static SpeechToText _instance = null;

    public static SpeechToText sharedInstance(){
        if(_instance == null){
            synchronized(SpeechToText.class){
                _instance = new SpeechToText();
            }
        }
        return _instance;
    }

    /**
     * Init the shared instance with the context
     * @param uri
     * @param ctx
     * @param sc
     */
    public void initWithContext(URI uri, Context ctx, SpeechConfiguration sc){
        this.setHostURL(uri);
        this.appCtx = ctx;
        this.sConfig = sc;

        if(this.sConfig.isUsingVAD)
            this.initVadService();
        else
            this.doUnbindService();
    }

    /**
     * Connection to monitor the audio recording service
     */
    private ServiceConnection mConnection = new ServiceConnection() {

        public void onServiceConnected(ComponentName className, IBinder service) {
            Logger.i(TAG, "Service connected");
            mService = ((RecognizerBinder) service).getService();

            if (mStartRecording && ! mService.isWorking()) {
                recognize();
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            mService = null;
            Logger.i(TAG, "Service disconnected");
        }
    };

    /**
     * Connect to recording service {@link RecognizerIntentService}.
     */
    private void doBindService() {
        try {
            // This can be called also on an already running service
            this.appCtx.startService(new Intent(this.appCtx, RecognizerIntentService.class));
            this.appCtx.bindService(new Intent(this.appCtx, RecognizerIntentService.class), mConnection, Context.BIND_AUTO_CREATE);
            mIsBound = true;
            Logger.i(TAG, "Service is bound");
        } catch (Exception e) {
            // TODO: handle exception
            Logger.e(TAG, "FAIL doBindService");
            e.printStackTrace();
        }
    }
    /**
     * Disconnect from recording service {@link RecognizerIntentService}.
     */
    private void doUnbindService() {
        if (mIsBound) {
            mService.stop();
            this.appCtx.unbindService(mConnection);
            mIsBound = false;
            mService = null;
            Logger.i(TAG, "Service is unbound");
        }
    }

    /** After Logging in, initiate recorder.
     * Construct Runnable to save audio data.
     * Connect to recording service.
     */
    public void initVadService() {
        RawAudioRecorder.CreateInstance(SpeechConfiguration.SAMPLE_RATE);

        // Save the current recording data to a temp array and send it to Vad processing
        mRunnableBytes = new Runnable() {
            public void run() {
                if (mService != null && mService.getLength() > 0) {
                    if (isCancelled) {
                        Logger.i(TAG, "mRunnableBytes is cancelled");
                        return;
                    }
                    try {
                        byte[] allAudio = mService.getCompleteRecording();
                        byte[] tmp = new byte[allAudio.length - currentOffset];
                        System.arraycopy(allAudio, currentOffset, tmp, 0, tmp.length);
                        if (tmp.length > 0) {
                            recordedData.put(tmp);
                            currentOffset += tmp.length;
                            numberData++;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                mHandlerBytes.postDelayed(this, TASK_BYTES_INTERVAL);
            }
        };
        // Decide if we should stop recording
        // 1. Max recording time has passed
        // 2. Speaker stopped speaking
        // 3. User tap mic to stop
        mRunnableStop = new Runnable() {
            public void run() {
                if (mService != null) {
                    if (isCancelled) {
                        Logger.i(TAG, "Recording is cancelled as USER hit cancel");
                        return;
                    }
                    else if (mMaxRecordingTime < (SystemClock.elapsedRealtime() - mService.getStartTime())) {
                        Logger.i(TAG, "Max recording time exceeded");
                        stopServiceRecording();
                    } else if (mService.isPausing()) {
                        Logger.i(TAG, "Speaker finished speaking");
                        stopServiceRecording();
                    } else if (stopByUser) {
                        Logger.i(TAG, "Stop by USER/ hit the mic button while recording");
                        stopServiceRecording();
                    } else {
                        mHandlerStop.postDelayed(this, TASK_STOP_INTERVAL);
                    }
                }
            }
        };

        doBindService();
    }

    /**
     * Remove any pending post of Runnable. Stop recording service and reset flags.
     */
    private void stopServiceRecording() {
        shouldStopRecording = true;
        mHandlerBytes.removeCallbacks(mRunnableBytes);
        mHandlerStop.removeCallbacks(mRunnableStop);
        mService.stop();
        handleRecording();
    }

    /**
     * Control recording process based on its status ({@link RecognizerIntentService}).
     */
    private void handleRecording() {
        if (mService == null) {
            return;
        }
        switch(mService.getState()) {
            case RECORDING:
                this.prepareRecording();
                break;
            case PROCESSING:
                this.stopRecognition();
                break;
            case ERROR:
                Log.e(TAG, "Error while recording audio from handlerRecording()");
                break;
            default:
                break;
        }
    }

    /**
     * 1. Start {@link Handler} to save audio data recorded.
     * <br>
     * 2. Start thread to detect the end moment of recording process.
     */
    private void prepareRecording() {
        Logger.i(TAG, "prepareRecording: " + shouldStopRecording);
        // Schedule save byte runnable
        mHandlerBytes.postDelayed(mRunnableBytes, TASK_BYTES_DELAY);
        // Schedule stopping runnable
        mHandlerStop.postDelayed(mRunnableStop, TASK_STOP_DELAY);
    }

    /**
     * Will be called after VAD detecting or VAD timeout.<br>
     * Waiting for audio data has been uploaded, then get query result and return to Javascript.
     */
    private void finishRecord() {
        Logger.i(TAG, "finishRecord");
        beginThinking = SystemClock.elapsedRealtime();
        // Listen to onHasDataThread for getting result of recognizing
        if (!doneUploadData) { // DON'T wait when data has been uploaded (when recording time quite long)
            synchronized (uploader) {
                try {
                    uploader.wait(THINKING_TIMEOUT); // Wait for done upload data. Active after 5s if NOT received notification
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        final long gettingTranscriptTimeout = THINKING_TIMEOUT - (SystemClock.elapsedRealtime() - beginThinking);
        Logger.i(TAG, "gettingTranscriptTimeout = " + gettingTranscriptTimeout);
        if (!isCancelled) {
            if (gettingTranscriptTimeout > 0) {
                if (!uploader.isUploadPrepared()) { // FAIL to prepare connection for uploading audio
                    Logger.e(TAG, "uploader prepare thread NOT done!!!");
                    if (onHasDataThread != null)
                        onHasDataThread.interrupt();
                    uploader.stopUploaderPrepareThread();
                    stopAllHandler();
                    mService.processContinu();

                    QueryResult result = null;

                    if (uploader.getUploadErrorCode() < 0) {
                        result = new QueryResult(QueryResult.CONNECTION_CLOSED, QueryResult.CONNECTION_CLOSED_MESSAGE);
                    } else {
                        result = new QueryResult(QueryResult.CONNECTION_FAILED, QueryResult.CONNECTION_FAILED_MESSAGE);
                    }
                    isCancelled = true; // To stop onHasDataThread if failed interrupt it
                    this.sendMessage(SpeechDelegate.ERROR, result);
                } else {
                    getVADTranscript(gettingTranscriptTimeout);
                }
            } else { // Timeout prepare uploader (>15s), alert "Thinking timeout"
                if (onHasDataThread != null)
                    onHasDataThread.interrupt();
                isCancelled = true; // To stop onHasDataThread if failed interrupt it
                uploader.stopUploaderPrepareThread();
                stopAllHandler();
                mService.processContinu();
                Logger.i(TAG, "Timeout prepare uploader (>15s)");
                this.sendMessage(SpeechDelegate.ERROR, new QueryResult(QueryResult.TIME_OUT, QueryResult.TIME_OUT_MESSAGE));
            }
        } else {
            Logger.i(TAG, "Thinking cancelled");
            this.sendMessage(SpeechDelegate.ERROR, new QueryResult(QueryResult.CANCEL_ALL, QueryResult.CANCEL_ALL_MESSAGE));
        }
    }

    /**
     * Remove any pending posts of Runnable r that are in the message queue. Clear recorded audio.
     */
    private void stopAllHandler() {
        Logger.i(TAG, "stopAllHandler");
        try {
            isCancelled = true;
            mHandlerBytes.removeCallbacks(mRunnableBytes);
            mHandlerStop.removeCallbacks(mRunnableStop);
            recordedData.clear();
        } catch (Exception e) {
            // TODO: handle exception
            Logger.d(TAG, "removeCallbacks FAIL");
        }
        // Reset current offset of audio data[]
        currentOffset = 0;
    }

    /**
     * Send message to the delegate
     *
     * @param code
     * @param result
     */
    private void sendMessage(int code, QueryResult result){
        if(this.delegate != null){
            Logger.w(TAG, "INVOKING sendMessage FROM SpeechToText");
            this.delegate.onMessage(code, result);
        }
        else{
            Logger.w(TAG, "INVOKING sendMessage FAILED FROM SpeechToText");
        }
    }

    /**
     * Get transcript and show result. Then, reset all.
     * @param timeout
     */
    public void getVADTranscript(long timeout) {
        QueryResult	result;
        result = uploader.getQueryResultByAudio(timeout);
        if (!isCancelled) {
            if (result != null) {
                //Set transcript received from iTrans
                String transcript = result.getTranscript();
                setTranscript(transcript);
                this.sendMessage(SpeechDelegate.MESSAGE, result);
            }
            else {
                Logger.w(TAG, "Query result: ERROR code 401");
            }
            stopAllHandler();
            mService.processContinu();
        } else
            Logger.i(TAG, "getVADTranscript has been cancelled");

    }

    private class STTIAudioConsumer implements IAudioConsumer {
        private IChunkUploader mUploader = null;

        public STTIAudioConsumer(IChunkUploader uploader) {
            mUploader = uploader;
        }

        public void consume(byte [] data) {
            mUploader.onHasData(data);
        }

        @Override
        public void onAmplitude(double amplitude, double volume) {
            if(delegate != null){
                delegate.onAmplitude(amplitude, volume);
            }
        }
    }

    /**
     * Start recording process with VAD:
     */
    private void startRecordingWithoutVAD() {
        Logger.i(TAG, "-> startRecordingWithoutVAD");
        uploader.prepare();
        STTIAudioConsumer audioConsumer = new STTIAudioConsumer(uploader);

        audioCaptureThread = new AudioCaptureThread(SpeechConfiguration.SAMPLE_RATE, audioConsumer);
        audioCaptureThread.start();
    }

    /**
     * Start recording process with VAD:
     * <br>1. Prepare uploader. Start thread to listen if have audio data, then upload it.
     * <br>
     * 2. Start service to record audio.
     */
    private void startRecordingWithVAD() {
        Logger.i(TAG, "-> startRecordingWithVAD");

        isCancelled = false;
        numberData = 0;

        recordedData = new LinkedBlockingQueue<byte[]>();

        if (mIsBound) {
            if (mService.getState() == State.RECORDING) {
                stopServiceRecording();
            } else {
                // Prepare uploader with thread
                uploader.prepare();
                onHasDataThread = new Thread() { // wait for uploading audio data
                    public void run() {
                        while (!isCancelled) {
                            // uploader prepare FAIL or uploading data DONE, notify to stop recording
                            // NOTE: Need time to have recording audio data
                            if ((shouldStopRecording && numberData == 0)/* || !uploader.isUploadPrepared()*/) {
                                doneUploadData = true;
                                synchronized (uploader) {
                                    uploader.notify();
                                }
                                break;
                            }
                            try {
                                if (numberData > 0) {
                                    byte[] dataToUpload = recordedData.take();
                                    if (dataToUpload != null) {
                                        uploader.onHasData(dataToUpload);
                                        numberData--;
                                    }
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }

                    }
                };
                onHasDataThread.setName("onHasDataThread");
                onHasDataThread.start();

                if (mService.init()) {
                    Logger.i(TAG, "startServiceRecording");

                    STTIAudioConsumer audioConsumer = new STTIAudioConsumer(uploader);
                    mService.start(SpeechConfiguration.SAMPLE_RATE, audioConsumer); // recording was started, State = State.RECORDING
                    handleRecording();
                }
            }
        } else {
            mStartRecording = true;
            doBindService();
        }
    }

    /**
     * Start recording audio
     */
    public void recognize() {
        Log.i(TAG, "startRecording");
        shouldStopRecording = false;
        doneUploadData = false;
        // Initiate Uploader, Encoder

        try {
            HashMap<String, String> header = new HashMap<String, String>();
            if(sConfig.audioFormat.equals(SpeechConfiguration.AUDIO_FORMAT_DEFAULT)) {
                encoder = new ChuckRawEnc();
            }
            else if(sConfig.audioFormat.equals(SpeechConfiguration.AUDIO_FORMAT_OGGOPUS)){
                encoder = new ChuckOggOpusEnc();
            }

            header.put("Content-Type", sConfig.audioFormat);

            if(sConfig.isAuthNeeded) {
                if (this.tokenProvider != null) {
                    header.put("X-Watson-Authorization-Token", this.tokenProvider.getToken());
                    Logger.e(TAG, "ws connecting with token based authentication");
                } else {
                    String auth = "Basic " + Base64.encodeBytes((this.username + ":" + this.password).getBytes(Charset.forName("UTF-8")));
                    header.put("Authorization", auth);
                    Logger.e(TAG, "ws connecting with Basic Authentication");
                }
            }

            String wsURL = getHostURL().toString() + "/v1/recognize" + (this.model != null ? ("?model=" + this.model) : "");

            uploader = new ChuckWebSocketUploader(encoder, wsURL, header, sConfig);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        uploader.setTimeout(UPLOADING_TIMEOUT); // default timeout
        uploader.setDelegate(this.delegate);
        if (this.sConfig.isUsingVAD) { // Record audio in service
            startRecordingWithVAD();
        } else {
			startRecordingWithoutVAD();
        }
    }

    public void stopRecognition() {
        if(audioCaptureThread != null)
            audioCaptureThread.end();

        if(this.sConfig.isUsingVAD){
            finishRecord();
        }

        if(uploader != null)
            uploader.close();
    }

    private void buildAuthenticationHeader(HttpGet httpGet) {
        // use token based authentication if possible, otherwise Basic Authentication will be used
        if (this.tokenProvider != null) {
            Log.d(TAG, "using token based authentication");
            httpGet.setHeader("X-Watson-Authorization-Token",this.tokenProvider.getToken());
        } else {
            Log.d(TAG, "using basic authentication");
            httpGet.setHeader(BasicScheme.authenticate(new UsernamePasswordCredentials(this.username, this.password), "UTF-8",false));
        }
    }

    // get the list of models for the speech to text service
    public JSONObject getModels() {
        JSONObject object = null;

        try {
            HttpClient httpClient = new DefaultHttpClient();
            String strHTTPURL = this.hostURL.toString().replace("wss","https").replace("ws", "http");
            HttpGet httpGet = new HttpGet(strHTTPURL+"/v1/models");
            this.buildAuthenticationHeader(httpGet);
            httpGet.setHeader("accept","application/json");
            HttpResponse executed = httpClient.execute(httpGet);
            InputStream is=executed.getEntity().getContent();

            // get the JSON object containing the models from the InputStream
            BufferedReader streamReader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder responseStrBuilder = new StringBuilder();
            String inputStr;
            while ((inputStr = streamReader.readLine()) != null)
                responseStrBuilder.append(inputStr);
            object = new JSONObject(responseStrBuilder.toString());
            Log.d(TAG, object.toString());
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }
        return object;
    }

    // get information about the model
    public JSONObject getModelInfo(String strModel) {

        JSONObject object = null;

        try {
            HttpClient httpClient = new DefaultHttpClient();
            String strHTTPURL = this.hostURL.toString().replace("wss", "https").replace("ws", "http");
            HttpGet httpGet = new HttpGet(strHTTPURL+"/v1/models/en-US_NarrowbandModel");
            this.buildAuthenticationHeader(httpGet);
            httpGet.setHeader("accept","application/json");
            HttpResponse executed = httpClient.execute(httpGet);
            InputStream is=executed.getEntity().getContent();

            // get the JSON object containing the models from the InputStream
            BufferedReader streamReader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder responseStrBuilder = new StringBuilder();
            String inputStr;
            while ((inputStr = streamReader.readLine()) != null)
                responseStrBuilder.append(inputStr);
            object = new JSONObject(responseStrBuilder.toString());
            Log.d(TAG, object.toString());

        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }
        return object;
    }
    /**
     * Change default timeout
     *
     * @param timeout
     */
    public void setTimeout(int timeout){
        this.UPLOADING_TIMEOUT = timeout;
    }
    /**
     * @return the appCtx
     */
    public Context getAppCtx() { return appCtx; }
    /**
     * @param appCtx the appCtx to set
     */
    public void setAppCtx(Context appCtx) { this.appCtx = appCtx; }
    /**
     * @return the transcript
     */
    public String getTranscript() { return transcript; }
    /**
     * @param transcript the transcript to set
     */
    public void setTranscript(String transcript) {
        this.transcript = transcript;
    }
    /**
     * @return the isCancelled
     */
    public boolean isCancelled() {
        return isCancelled;
    }
    /**
     * @param isCancelled the isCancelled to set
     */
    public void setCancelled(boolean isCancelled) { this.isCancelled = isCancelled; }
    /**
     * @return the hostURL
     */
    public URI getHostURL() {
        return hostURL;
    }
    /**
     * @param hostURL the hostURL to set
     */
    public void setHostURL(URI hostURL) {
        this.hostURL = hostURL;
    }
    /**
     * @return the delegate
     */
    public SpeechDelegate getDelegate() {
        return delegate;
    }
    /**
     * @param delegate the delegate to set
     */
    public void setDelegate(SpeechDelegate delegate) {
        this.delegate = delegate;
    }
    /**
     * Set the recorder delegate for the encoder
     */
    public void setRecorderDelegate(SpeechRecorderDelegate obj){
        if(encoder != null)
            encoder.setDelegate(obj);
    }
    /**
     * Set API credentials
     * @param username
     */
    public void setCredentials(String username, String password) {
        this.username = username;
        this.password = password;
    }
    /**
     * Set token provider (for token based authentication)
     */
    public void setTokenProvider(TokenProvider tokenProvider) { this.tokenProvider = tokenProvider; }

    /**
     * Set STT model
     */
    public void setModel(String model) {
        this.model = model;
    }
}

