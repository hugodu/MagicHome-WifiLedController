package ru.ittim.openhab.ledbinding.library;

import org.apache.commons.codec.binary.Hex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.Socket;
import java.util.Arrays;

/**
 * MagicHome wifi led controller
 * Created by Timofey on 21.06.2016.
 */
class LedController {
    //From reverse engineering (Controller bought in May 2016)
    private final static int DEFAULT_CONTROLLER_PORT = 5577;
    private final static Logger logger = LogManager.getLogger();

    private final static byte[] REQUEST_STATE_MSG = {(byte) 0x81, (byte) 0x8a, (byte) 0x8b, (byte) 0x96};
    /**
     * in milliseconds
     *
     * @see java.net.Socket#setSoTimeout(int)
     */
    private static final int TIMEOUT = 1000;

    private final String host;
    private final String mac;
    private final String model;
    private ControllerType type;
    private PowerState power;
    private FunctionalModeRgb mode;
    private LedStripType strip;
    private ControllerChannels channels;

    /**
     * message exchange with controller
     */
    private Socket socket;

    private LedController(String host, String mac, String model, LedStripType strip) {
        this.host = host;
        this.mac = mac;
        this.model = model;
        this.type = ControllerType.UNKNOWN;
        this.power = PowerState.UNKNOWN;
        this.mode = FunctionalModeRgb.UNKNOWN;
        this.strip = strip;

        try {
            socket = new Socket(host, DEFAULT_CONTROLLER_PORT);
            socket.setSoTimeout(TIMEOUT);
        } catch (IOException e) {
            logger.error("Could not create a connection to the controller " + this, e);
        }
    }

    LedController(String host, String mac, String model) {
        this(host, mac, model, LedStripType.UNKNOWN);
    }

    public String getHost() {
        return host;
    }

    public String getMac() {
        return mac;
    }

    public String getModel() {
        return model;
    }

    public ControllerType getType() {
        return type;
    }

    public PowerState getPower() {
        return power;
    }

    public FunctionalModeRgb getMode() {
        return mode;
    }

    @Override
    public String toString() {
        return "LedController{" +
                "host='" + host + '\'' +
                ", mac='" + mac + '\'' +
                ", model='" + model + '\'' +
                ", type=" + type +
                ", power=" + power +
                ", mode=" + mode + "(" + mode.getSpeed() + "-" + mode.getPercentSpeed() + "%)" +
                ", strip=" + strip +
                ", channels=" + channels +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        LedController that = (LedController) o;

        return host.equals(that.host) && mac.equals(that.mac) && model.equals(that.model);

    }

    @Override
    public int hashCode() {
        int result = host.hashCode();
        result = 31 * result + mac.hashCode();
        result = 31 * result + model.hashCode();
        return result;
    }

    /**
     * Send request message {@link LedController#REQUEST_STATE_MSG} to controller and parse response message (14 bytes)
     *<pre>
     * Response structure (reverse engineering with WireShark):
     * response[00] - always 0x81 (-0x7f)
     * response[01] - always 0x25
     * response[02] - on/off
     * response[03] - mode
     * response[04] - ???
     * response[05] - f(speed) [31 -1] -> [0 100]
     * response[06] - red [0-255]*brightness
     * response[07] - green
     * response[08] - blue
     * response[09] - ww channel
     * response[10] - 01
     * response[11] - cw chanenl
     * response[12] - f0 when RGB, RGBW, RGBWW and 0f when DIM, WW, CW
     * response[13] - checksum
     * </pre>
     * @return true if response parsed and correct, else false
     */
    private boolean init() {
        try {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            DataOutputStream dos = new DataOutputStream(out);
            dos.write(REQUEST_STATE_MSG);
            dos.flush();
            byte[] bytes = new byte[14];
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int len;
            while ((len = in.read(bytes)) > -1) {
                baos.write(bytes, 0, len);
                if (baos.size() >= 14) {
                    break;
                }
            }

            if (baos.size() != 14) {
                logger.error("Received response message with incorrect length");
                return false;
            }
            byte[] response = baos.toByteArray();
            if ((response[0] != -0x7f) || (response[1] != 0x25)) {
                logger.error("Wrong response message structure");
                return false;
            }

            this.power = PowerState.get(response[2]);
            this.mode = FunctionalModeRgb.get(response[3]);
            this.mode.setSpeed(response[5]);
            this.type = ControllerType.get(response[12]);
            this.channels = new ControllerChannels(response[6], response[7], response[8], response[9], response[11]);
            System.out.println(Hex.encodeHexString(response));
            return true;


        } catch (IOException e) {
            logger.error("Socket is not operable", e);
            return false;
        }
    }

    private boolean setPowerState(PowerState state) {
        try {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            DataOutputStream dos = new DataOutputStream(out);
            byte[] command = state.getCommand();
            dos.write(command);
            dos.flush();
            byte[] bytes = new byte[command.length];
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int len;
            while ((len = in.read(bytes)) > -1) {
                baos.write(bytes, 0, len);
                if (baos.size() == command.length) {
                    break;
                }
            }
            return Arrays.equals(baos.toByteArray(), command);
        } catch (IOException e) {
            logger.error("Socket is not operable", e);
            return false;
        }
    }


    private boolean turnOn() {
        return setPowerState(PowerState.ON);
    }

    private boolean turnOff() {
        return setPowerState(PowerState.OFF);
    }

    public boolean setChannels(ControllerChannels channels) {
        try {
            OutputStream out = socket.getOutputStream();
            DataOutputStream dos = new DataOutputStream(out);
            byte[] command = channels.getChannelCommand();
            dos.write(command);
            dos.flush();
            return true;
        } catch (IOException e) {
            logger.error("Сокет не операбелен", e);
            return false;
        }
    }

    public boolean setMode(FunctionalModeRgb mode) {
        try {
            OutputStream out = socket.getOutputStream();
            DataOutputStream dos = new DataOutputStream(out);
            byte[] command = mode.getCommand();
            dos.write(command);
            dos.flush();
            return true;
        } catch (IOException e) {
            logger.error("Сокет не операбелен", e);
            return false;
        }

    }

    /**
     * Examples
     * @param ignored not used
     */
    public static void main(String[] ignored)  {
        LedController controller = new LedController("192.168.1.181", "ACCF239939B4", "HF-LPB100-ZJ200", LedStripType.RGB);
        controller.init();
        System.out.println(controller);
        controller.turnOff();
//        controller.turnOn();
//        ControllerChannels channels = new ControllerChannels((byte)0,(byte)0,(byte)0,(byte)0,(byte)0);
//        ControllerChannels channels = new ControllerChannels((byte)0xff,(byte)0xff,(byte)0xff,(byte)0,(byte)0);
//        ControllerChannels channels = new ControllerChannels((byte)0,(byte)0,(byte)0,(byte)0xff,(byte)0xff);
//        ControllerChannels channels = new ControllerChannels((byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff);
//        controller.setChannels(channels);
//        controller.setMode(FunctionalModeRgb.GREEN_STROBE_FADE.setPercentSpeed(100));

    }
}
