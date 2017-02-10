/** 
 * Copyright 2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Amazon Software License (the "License"). You may not use this file 
 * except in compliance with the License. A copy of the License is located at
 *
 *   http://aws.amazon.com/asl/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, express or implied. See the License for the 
 * specific language governing permissions and limitations under the License.
 */
package com.amazon.alexa.avs;

import java.awt.Component;
import java.awt.Container;
import java.awt.Desktop;
import java.awt.Desktop.Action;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Locale;
import java.util.Properties;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazon.alexa.avs.auth.AccessTokenListener;
import com.amazon.alexa.avs.auth.AuthSetup;
import com.amazon.alexa.avs.auth.companionservice.RegCodeDisplayHandler;
import com.amazon.alexa.avs.config.DeviceConfig;
import com.amazon.alexa.avs.config.DeviceConfigUtils;
import com.amazon.alexa.avs.http.AVSClientFactory;
import com.amazon.alexa.avs.wakeword.WakeWordDetectedHandler;
import com.amazon.alexa.avs.wakeword.WakeWordIPCFactory;

@SuppressWarnings("serial")
public class AVSApp
        implements ExpectSpeechListener, RecordingRMSListener, RegCodeDisplayHandler,
        AccessTokenListener, ExpectStopCaptureListener, WakeWordDetectedHandler {

    private static final Logger log = LoggerFactory.getLogger(AVSApp.class);

    private static final String APP_TITLE = "Alexa Voice Service";
    private static final String LISTEN_LABEL = "Listen";
    private static final String PROCESSING_LABEL = "Processing";
    private static final String PREVIOUS_LABEL = "\u21E4";
    private static final String NEXT_LABEL = "\u21E5";
    private static final String PAUSE_LABEL = "\u275A\u275A";
    private static final String PLAY_LABEL = "\u25B6";
    private final AVSController controller;
    private JButton actionButton;
    private JButton playPauseButton;
    private Container playbackPanel;
    private JTextField tokenTextField;
    private JProgressBar visualizer;
    private final DeviceConfig deviceConfig;
    private final RecordingRMSListener rmsListener = this;

    private String accessToken;

    private AuthSetup authSetup;

    private enum ButtonState {
        START,
        STOP,
        PROCESSING;
    }

    private ButtonState buttonState;

    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            new AVSApp(args[0]);
        } else {
            new AVSApp();
        }
    }

    public AVSApp() throws Exception {
        this(DeviceConfigUtils.readConfigFile());
    }

    public AVSApp(String configName) throws Exception {
        this(DeviceConfigUtils.readConfigFile(configName));
    }

    private AVSApp(DeviceConfig config) throws Exception {
        deviceConfig = config;

        controller =
                new AVSController(this, new AVSAudioPlayerFactory(), new AlertManagerFactory(),
                        getAVSClientFactory(deviceConfig), DialogRequestIdAuthority.getInstance(),
                        new WakeWordIPCFactory(), deviceConfig, this);

        authSetup = new AuthSetup(config, this);
        authSetup.addAccessTokenListener(this);
        authSetup.addAccessTokenListener(controller);
        authSetup.startProvisioningThread();
        
        buttonState = ButtonState.START;
        
        controller.initializeStopCaptureHandler(this);
        controller.startHandlingDirectives();
    }

    private String getAppVersion() {
        final Properties properties = new Properties();
        try (final InputStream stream = getClass().getResourceAsStream("/res/version.properties")) {
            properties.load(stream);
            if (properties.containsKey("version")) {
                return properties.getProperty("version");
            }
        } catch (IOException e) {
            log.warn("version.properties file not found on classpath");
        }
        return null;
    }

    protected AVSClientFactory getAVSClientFactory(DeviceConfig config) {
        return new AVSClientFactory(config);
    }

	private void wake() {
        controller.onUserActivity();

        if (buttonState == ButtonState.START) { // if in idle mode
            buttonState = ButtonState.STOP;
            
            RequestListener requestListener = new RequestListener() {

                @Override
                public void onRequestSuccess() {
                    // In case we get a response from the server without
                    // terminating the stream ourselves.
                    if (buttonState == ButtonState.STOP) {
                        wake();
                    }
                    finishProcessing();
                }

                @Override
                public void onRequestError(Throwable e) {
                    log.error("An error occured creating speech request", e);
                    System.out.println(e.getMessage());
                    wake();
                    finishProcessing();
                }
            };
            controller.startRecording(rmsListener, requestListener);
        } else { // else we must already be in listening
            buttonState = ButtonState.PROCESSING;
            controller.stopRecording(); // stop the recording so the request can complete
        }   
    }
    
    public void finishProcessing() {
        buttonState = ButtonState.START;
        controller.processingFinished();
    }

    @Override
    public void rmsChanged(int rms) { // AudioRMSListener callback
        
    }

    @Override
    public void onExpectSpeechDirective() {
        Thread thread = new Thread() {
            @Override
            public void run() {
                while (controller.isSpeaking()) {
                    try {
                        Thread.sleep(500);
                    } catch (Exception e) {
                    }
                }
            }
        };
        thread.start();
    }

    @Override
    public void onStopCaptureDirective() {
        
    }

    @Override
    public void displayRegCode(String regCode) {
        String title = "Login to Register/Authenticate your Device";
        String regUrl =
                deviceConfig.getCompanionServiceInfo().getServiceUrl() + "/provision/" + regCode;
        System.out.println("Please register your device by visiting the following URL in "
                            + "a web browser and follow the instructions:\n" + regUrl);
            
    }
    
    @Override
    public synchronized void onAccessTokenReceived(String accessToken) {
        
    }

    @Override
    public synchronized void onWakeWordDetected() {
        log.info("Wake Word was detected");
        wake();
    }
}
