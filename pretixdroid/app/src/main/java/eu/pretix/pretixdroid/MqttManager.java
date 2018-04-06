package eu.pretix.pretixdroid;

import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.Random;

public class MqttManager {

    private static MqttManager instance;

    private MqttAsyncClient client;
    private String clientId;
    private AppConfig config;

    private MqttManager(AppConfig config) {
        this.config = config;
        clientId = config.getMqttClientId();
        if(clientId.length() == 0) {
            Random random = new Random();
            String hexString = Integer.toHexString(random.nextInt());
            clientId = ("00000000" + hexString).substring(hexString.length());
            config.setMqttClientId(clientId);
        }
    }

    public static MqttManager getInstance(AppConfig config) {
        if (MqttManager.instance == null) {
            MqttManager.instance = new MqttManager(config);
        }
        return MqttManager.instance;
    }

    public void start() {

        if(config.getMqttUrl().length() == 0) {
            return;
        }

        if(client != null && client.isConnected()) {
            try {
                client.disconnectForcibly();
            } catch (MqttException me) {
                Log.d("MQTT", "connectComplete/disconnect: " + me.getMessage());
            }
        }

        Log.d("MQTT", "Url: " + config.getMqttUrl());
        Log.d("MQTT", "User: " + config.getMqttUser());
        Log.d("MQTT", "Password: " + config.getMqttPassword());
        Log.d("MQTT", "ClientId: " + config.getMqttClientIdPrefix() + config.getMqttClientId());
        Log.d("MQTT", "Publish: " + config.getMqttPublishPrefix());
        Log.d("MQTT", "Status: " + config.getMqttStatusTopicPrefix());

        try {
            client = new MqttAsyncClient(config.getMqttUrl(), config.getMqttClientIdPrefix() + clientId, new MemoryPersistence());

            client.setCallback(new MqttCallbackExtended() {

                @Override
                public void connectionLost(Throwable throwable) {
                    Log.d("MQTT", "connectionLost");
                }

                @Override
                public void messageArrived(String t, MqttMessage m) {
                    Log.d("MQTT", "messageArrived");
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken t) {
                    Log.d("MQTT", "deliveryComplete");
                }

                @Override
                public  void connectComplete(boolean reconnect, String serverURI) {
                    if(reconnect) {
                        Log.d("MQTT", "reconnnected");
                    } else {
                        Log.d("MQTT", "connnected");
                    }
                    MqttMessage mqttMessage = new MqttMessage("{\"active\":true}".getBytes());
                    mqttMessage.setQos(1);
                    mqttMessage.setRetained(true);
                    try {
                        client.publish(config.getMqttStatusTopicPrefix() + clientId, mqttMessage);
                    } catch (MqttException me) {
                        Log.d("MQTT", "connectComplete/publish: " + me.getMessage());
                    }
                }

            });

            MqttConnectOptions options = new MqttConnectOptions();
            //options.setSSLProperties(); // FIXME: Certificate pinning
            options.setCleanSession(false);
            if(config.getMqttUser().length() > 0 && config.getMqttPassword().length() > 0) {
                options.setUserName(config.getMqttUser());
                options.setPassword(config.getMqttPassword().toCharArray());
            }
            options.setKeepAliveInterval(30);
            options.setConnectionTimeout(15);
            options.setAutomaticReconnect(true);
            options.setWill(config.getMqttStatusTopicPrefix() + clientId, "{\"active\":false}".getBytes(),1,true);

            client.connect(options);

        } catch (MqttException me) {
            Log.d("MQTT", "start: " + me.getMessage());
            me.printStackTrace();
        }
    }

    public void publish(String message) {
        if(client != null) {
            Log.d("MQTT", "message: " + message);
            MqttMessage mqttMessage = new MqttMessage(message.getBytes());
            mqttMessage.setQos(1);
            try {
                client.publish(config.getMqttPublishPrefix(), mqttMessage);
            } catch (Exception e) {
                Log.d("MQTT", "publish: " + e.getMessage());
            }
        }
    }

    public void reconnect() {
        if(client != null && !client.isConnected()) {
            try {
                client.reconnect();
            } catch (Exception e) {
                Log.d("MQTT", "reconnect: " + e.getMessage());
            }
        }
    }

}
