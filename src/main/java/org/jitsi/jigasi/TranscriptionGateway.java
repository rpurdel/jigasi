/*
 * Jigasi, the JItsi GAteway to SIP.
 *
 * Copyright @ 2017 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.jigasi;

import org.jitsi.jigasi.transcription.*;
import org.jitsi.jigasi.transcription.action.*;
import org.jitsi.jigasi.util.Util;
import org.jitsi.utils.logging.*;
import org.json.*;
import org.osgi.framework.*;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * A Gateway which creates a TranscriptionGatewaySession when it has an outgoing
 * call
 *
 * @author Nik Vaessen
 * @author Damian Minkov
 */
public class TranscriptionGateway
    extends AbstractGateway<TranscriptionGatewaySession>
{
    /**
     * The logger
     */
    private final static Logger logger
        = Logger.getLogger(TranscriptionGateway.class);

    /**
     * Property for the class name of a custom transcription service.
     */
    private static final String CUSTOM_TRANSCRIPTION_SERVICE_PROP
        = "org.jitsi.jigasi.transcription.customService";

    /**
     * Property for the class name of a custom transcription service.
     */
    private static final String REMOTE_TRANSCRIPTION_CONFIG_URL
            = "org.jitsi.jigasi.transcription.remoteTranscriptionConfigUrl";

    /**
     * JWT audience for ASAP Auth.
     */
    public final static String JWT_AUDIENCE
            = "org.jitsi.jigasi.transcription.remoteTranscriptionConfigUrl.aud";


    /**
     * The kid header used for signing
     */
    public final static String PRIVATE_KEY_NAME
            = "org.jitsi.jigasi.transcription.remoteTranscriptionConfigUrl.kid";

    /**
     * The base64 encoded private key used for signing
     */
    public final static String PRIVATE_KEY
            = "org.jitsi.jigasi.transcription.remoteTranscriptionConfigUrl.key";

    /**
     * Class which manages the desired {@link TranscriptPublisher} and
     * {@link TranscriptionResultPublisher}
     */
    private TranscriptHandler handler = new TranscriptHandler();

    /**
     * The actions service handler.
     */
    private ActionServicesHandler actionServicesHandler;

    private final static String privateKey;

    private final static String privateKeyName;

    private final static String jwtAudience;

    private static String remoteTranscriptionConfigUrl;

    /**
     * Map of the available transcribers
     */
    private static final Map<String, String> transcriberClasses = new HashMap<String, String>();

    static {
        transcriberClasses.put("GOOGLE", "org.jitsi.jigasi.transcription.GoogleCloudTranscriptionService");
        transcriberClasses.put("ORACLE_CLOUD_AI_SPEECH",
                "org.jitsi.jigasi.transcription.OracleTranscriptionService");
        transcriberClasses.put("EGHT_WHISPER", "org.jitsi.jigasi.transcription.WhisperTranscriptionService");
        transcriberClasses.put("VOSK", "org.jitsi.jigasi.transcription.VoskTranscriptionService");

        privateKey = JigasiBundleActivator.getConfigurationService().getString(PRIVATE_KEY, null);
        privateKeyName = JigasiBundleActivator.getConfigurationService().getString(PRIVATE_KEY_NAME, null);
        jwtAudience = JigasiBundleActivator.getConfigurationService().getString(JWT_AUDIENCE, null);
        remoteTranscriptionConfigUrl = JigasiBundleActivator.getConfigurationService()
                .getString(REMOTE_TRANSCRIPTION_CONFIG_URL, null);
    }

    /**
     * Create a new TranscriptionGateway, which manages
     * TranscriptionGatewaySessions in conferences, such that the audio
     * in those conferences can be transcribed
     *
     * @param context the context containing information about calls
     */
    public TranscriptionGateway(BundleContext context)
    {
        super(context);

        // init action handler
        actionServicesHandler = ActionServicesHandler.init(context);
    }

    @Override
    public void stop()
    {
        // stop action handler
        if (actionServicesHandler != null)
        {
            actionServicesHandler.stop();
            actionServicesHandler = null;
        }
    }

    /**
     * Tries to retrieve a custom defined transcriber by checking
     * multiple sources. First it issues a GET request to the
     * remoteTranscriptionConfigUrl property if defined. It expects
     * a JSON response with a transcriberType property. If not found
     * it tries to read the transcription.customService property. If
     * that also fails it returns the default GoogleCloudTranscriptionService.
     *
     * @param tenant the tenant which is retrieved from the context
     * @param roomJid the roomJid which is retrieved from the context (used only with JaaS)
     */
    private String getCustomTranscriptionServiceClass(String tenant, String roomJid)
    {
        String transcriberClass = null;

        if (remoteTranscriptionConfigUrl != null)
        {
            String tsConfigUrl;

            // this is JaaS specific
            if (remoteTranscriptionConfigUrl.contains("jitsi.net"))
            {
                tsConfigUrl = remoteTranscriptionConfigUrl + "?conferenceFullName="
                        + URLEncoder.encode(roomJid, java.nio.charset.StandardCharsets.UTF_8);
            }
            else
            {
                String maybeTenant = tenant == null ? "" : tenant;
                tsConfigUrl = remoteTranscriptionConfigUrl + "/" + maybeTenant;
            }

            transcriberClass = getTranscriberFromRemote(tsConfigUrl);
            logger.info("Transcriber class retrieved from remote " + remoteTranscriptionConfigUrl
                    + ": " + transcriberClass);
        }

        if (transcriberClass == null)
        {
            transcriberClass = JigasiBundleActivator.getConfigurationService()
                    .getString(
                            CUSTOM_TRANSCRIPTION_SERVICE_PROP,
                            null);
            logger.info("Transcriber class retrieved from config: " + transcriberClass);
        }

        return transcriberClass;
    }

    private String getTranscriberFromRemote(String remoteTsConfigUrl)
    {
        String transcriberClass = null;
        if (logger.isDebugEnabled())
        {
            logger.debug("Calling  " + remoteTsConfigUrl + " to retrieve transcriber.");
        }
        try
        {
            URL url = new URL(remoteTsConfigUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Content-Type", "application/json");
            if (privateKey != null && privateKeyName != null && jwtAudience != null)
            {
                String token = Util.generateAsapToken(privateKey, privateKeyName, jwtAudience, "jitsi");
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            conn.setConnectTimeout(3000);
            int responseCode = conn.getResponseCode();
            if (responseCode == 200)
            {
                BufferedReader inputStream = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                String inputLine;
                StringBuilder responseBody = new StringBuilder();
                while ((inputLine = inputStream.readLine()) != null)
                {
                    responseBody.append(inputLine);
                }
                inputStream.close();
                JSONObject obj = new JSONObject(responseBody.toString());
                String transcriberType = obj.getString("transcriberType");
                if (logger.isDebugEnabled())
                {
                    logger.debug("Retrieved transcriberType: " + transcriberType);
                }
                transcriberClass = transcriberClasses.getOrDefault(transcriberType, null);
            }
            else
            {
                logger.warn("Could not retrieve transcriber from remote URL " + remoteTsConfigUrl
                        + ". Response code: " + responseCode);
            }
            conn.disconnect();
        }
        catch (Exception ex)
        {
            logger.error("Could not retrieve transcriber from remote URL." + ex);
        }
        return transcriberClass;
    }

    @Override
    public TranscriptionGatewaySession createOutgoingCall(CallContext ctx)
    {
        String customTranscriptionServiceClass = getCustomTranscriptionServiceClass(ctx.getTenant(),
                ctx.getRoomJid().toString());
        AbstractTranscriptionService service = null;
        if (customTranscriptionServiceClass != null)
        {
            try
            {
                service = (AbstractTranscriptionService)Class.forName(
                    customTranscriptionServiceClass).getDeclaredConstructor().newInstance();
            }
            catch(Exception e)
            {
                logger.warn("Cannot instantiate custom transcription service", e);
            }
        }

        if (service == null)
        {
            service = new GoogleCloudTranscriptionService();
        }

        TranscriptionGatewaySession outgoingSession =
                new TranscriptionGatewaySession(
                    this,
                    ctx,
                    service,
                    this.handler);
        outgoingSession.addListener(this);
        outgoingSession.createOutgoingCall();

        return outgoingSession;
    }

    /**
     * Whether this gateway is ready to create sessions.
     * @return whether this gateway is ready to create sessions.
     */
    public boolean isReady()
    {
        return true;
    }
}
