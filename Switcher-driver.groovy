/**
 *  Switcher Power Outlet Driver
 *  No license here
 *
 *  How to:
 *  You will need to enter the IP and Device ID
 *
 *  The Device ID can be extracted according to the script: https://github.com/NightRang3r/Switcher-V2-Python
 *  ******** Big Thanks to the developers Aviad Golan and Shai Rod that created the scripts and made it possible ********
 *  
 * 
 *  Changes:
 *  1.0.0   -   Starting project
 *  1.1.0   -   First release
*/

metadata {
    definition (name: 'Switcher Power Outlet', namespace: 'switchercomp', author: 'dimdim')

    {
        capability 'Refresh'
        capability 'Switch'
        capability 'Polling'
        capability 'PowerMeter'

        attribute 'DeviceName', 'string'
    }

    preferences {
        refreshRateMap = [:]
        refreshRateMap  << ['1' : 'Refresh every 1 minutes']
        refreshRateMap  << ['2' : 'Refresh every 2 minutes']
        refreshRateMap  << ['3' : 'Refresh every 3 minutes']

        input name: 'deviceIP', type: 'string', title:'IP', description:'Switcher IP Address', defaultValue:'', required: true
        input name: 'deviceId', type: 'string', title: 'Device ID:', description: 'Device ID', required: true
        input name: 'refreshRate', type: 'enum', title: 'Polling Refresh Rate', options: refreshRateMap, defaultValue: '3'
        input name: 'infoLogging', type: 'bool', title: 'Print info output to log', defaultValue: true
        input name: 'debugLogging', type: 'bool', title: 'Print debug output to log', defaultValue: true
    }

}

def off() {
    displayInfoLog('setting switcher to state off')
    sendCommand('SWITCH_OFF')
}

def on() {
    displayInfoLog('setting switcher to state on')
    sendCommand('SWITCH_ON')
}

def poll() {
    displayInfoLog('polling')
    refresh()
}

def refresh() {
    displayInfoLog('refreshing')
    sendCommand('GET_STATE')
}

// Parse incoming device messages to generate events
def parseOff(String response) {
    parseOnOff(response)
}
// Parse incoming device messages to generate events
def parseOn(String response) {
    parseOnOff(response)
}

def parseOnOff(String response) {
    responseAsHexString = getParsedResponseAsHexString(response)

    state.powerStatus = responseAsHexString.substring(88, 92) == '0100'
    updateSwitchStatusEvent(state.powerStatus ? "on" : "off")
    displayInfoLog("Parsed switch status: ${state.powerStatus}")
}

// Parse incoming device messages to generate events
def parseStatus(String response) {
    responseAsHexString = getParsedResponseAsHexString(response)

    state.powerStatus = responseAsHexString.substring(150, 154) == '0100'
    updateSwitchStatusEvent(state.powerStatus ? "on" : "off")
    displayInfoLog("Parsed switch status: ${state.powerStatus}")

    powerInStatus = responseAsHexString.substring(154, 162)
    powerValueFromStatus = powerInStatus.substring(2, 4) + powerInStatus.substring(0, 2)
    state.powerInWatt = hubitat.helper.HexUtils.hexStringToInt(powerValueFromStatus)
    displayInfoLog("Parsed power in Watt status: ${state.powerInWatt}")
    updatePowerStatusEvent(state.powerInWatt)
}

def getParsedResponseAsHexString(response) {
    displayInfoLog("Parsing message: ${response}")
    state.lastCommunicationTime = now()
    
    Byte[] respArray = parseLanMessage(response).payload.decodeBase64()
    responseAsHexString = new String(respArray)
    displayInfoLog("responseAsHexString: ${responseAsHexString}")
    return responseAsHexString
}

def updatePowerStatusEvent(valueInWatts) {
    sendEvent(name: "power", unit: "W", value: valueInWatts)
}

def updateSwitchStatusEvent(valueOnOrOff) {
    sendEvent(name: "switch", value: valueOnOrOff)
}

def installed() {
    displayInfoLog('Installing')
}

def configure() {
    displayInfoLog('Configuring')
    initialize()
}

// updated() runs every time user saves preferences
def updated() {
    displayInfoLog('Updating preference settings')
    displayInfoLog('Info message logging enabled')
    displayDebugLog('Debug message logging enabled')
    displayDebugLog("Device parameters:  IP[$deviceIP]  DEVICE_ID[$deviceId]  REFRESH_RATE[$refreshRate]")
    initialize()
}

def initialize() {
    resetDB()
    resetSchedule()
}

private def sendCommand(command) {
    tempWorkingSessionID = '00000000'
    temoWorkingPhoneId = '0000'
    temoWorkingDevicePassword = '00000000'

    switch (command) {
        case 'SWITCH_ON':
            displayDebugLog('Sending switcher on command')
            dataToSend = "fef05d0002320102" + tempWorkingSessionID + "340001000000000000000000" + getTimeStampInHex() + "00000000000000000000f0fe" + deviceId + "00" + temoWorkingPhoneId + "0000" + temoWorkingDevicePassword + "000000000000000000000000000000000000000000000000000000000106000" + "1" + "0000000000"
            sendMessage(dataToSend, "parseOn")
            break
        case 'SWITCH_OFF':
            displayDebugLog('Sending switcher off command')
            dataToSend = "fef05d0002320102" + tempWorkingSessionID + "340001000000000000000000" + getTimeStampInHex() + "00000000000000000000f0fe" + deviceId + "00" + temoWorkingPhoneId + "0000" + temoWorkingDevicePassword + "000000000000000000000000000000000000000000000000000000000106000" + "0" + "0000000000"
            sendMessage(dataToSend, "parseOff")
            break
        case 'GET_STATE':
            displayDebugLog('Sending get switcher state command')
            dataToSend = "fef0300002320103" + tempWorkingSessionID + "340001000000000000000000"  + getTimeStampInHex() + "00000000000000000000f0fe" + deviceId + "00"
            sendMessage(dataToSend, "parseStatus")
            break
        default:
            break
    }
    state.lastCommunicationTime = now()
}

private def getTimeStampInHex() {
    int timeNow = now() / 1000
    return reverseHexString(hubitat.helper.HexUtils.integerToHexString(timeNow, 1))
}

private def reverseHexString(hexString) {
    def reversed = ''
    for (int i = hexString.length(); i > 0; i -= 2) {
        reversed += hexString.substring(i - 2, i )
    }
    return reversed
}

private def sendMessage(packetInHex, callback) {
    waitTimedBuffer(now())
    def port = '9957'
    def deviceIpAndPort = deviceIP + ':' + port
    displayDebugLog("Sending message to IP[$deviceIpAndPort]")

    def packetHexDataToSend = getJChecksum(hubitat.helper.HexUtils.hexStringToByteArray(packetInHex))
    displayDebugLog("Sending message full [$packetHexDataToSend]")
    // hubitat.device.Protocol.values() =           [LAN, RAW_LAN, ZWAVE, ZWAVE_LOW_PRIORITY, ZIGBEE, TELNET, EVENTSTREAM, DELAY, UNKNOWN]
    // hubitat.device.HubAction.Type.values =       [LAN_TYPE_UDPCLIENT, LAN_TYPE_RAW]
    // hubitat.device.HubAction.Encoding.values() = [HEX_STRING]
    def Action = new hubitat.device.HubAction(
        packetHexDataToSend,
        hubitat.device.Protocol.RAW_LAN,
        [
            callback: callback,
            destinationAddress: deviceIpAndPort,
            type: hubitat.device.HubAction.Type.LAN_TYPE_RAW,
            encoding: hubitat.device.HubAction.Encoding.HEX_STRING
        ]
    )
    try {
        displayDebugLog('Sending packet')
        sendHubCommand(Action)
        displayDebugLog('Send packet completed')
    } catch (e) {
        displayErrorLog("Send packet Failed witch exeption $e")
    }
}

private def waitTimedBuffer(time) {
    deviceTimeBuffer = 3000
    if (state.lastCommunicationTime + deviceTimeBuffer > time) {
        displayDebugLog("waitTimedBuffer Starting to wait!!!")
        pauseExecution(deviceTimeBuffer)
    }
    displayDebugLog("waitTimedBuffer Finished to wait!!!")
}

private def getJChecksum(byteArray) {
    key = "3030303030303030303030303030303030303030303030303030303030303030"
    crc = getChecksum(byteArray)
    if (crc.size() < 4) {
        crc = "0000" + crc
    }
    dataWithCRC = hubitat.helper.HexUtils.byteArrayToHexString(byteArray) + crc.substring(2, 4) + crc.substring(0, 2)
    crc = crc.substring(2, 4) + crc.substring(0, 2) + key
    crc = getChecksum(hubitat.helper.HexUtils.hexStringToByteArray(crc))
    if (crc.size() < 4) {
        crc = "0000" + crc
    }
    dataWithCRC = dataWithCRC + crc.substring(2, 4) + crc.substring(0, 2)
    return dataWithCRC
}

private def getChecksum(buf) {
    int[] crctabHqx = [
        0x0000, 0x1021, 0x2042, 0x3063, 0x4084, 0x50a5, 0x60c6, 0x70e7,
        0x8108, 0x9129, 0xa14a, 0xb16b, 0xc18c, 0xd1ad, 0xe1ce, 0xf1ef,
        0x1231, 0x0210, 0x3273, 0x2252, 0x52b5, 0x4294, 0x72f7, 0x62d6,
        0x9339, 0x8318, 0xb37b, 0xa35a, 0xd3bd, 0xc39c, 0xf3ff, 0xe3de,
        0x2462, 0x3443, 0x0420, 0x1401, 0x64e6, 0x74c7, 0x44a4, 0x5485,
        0xa56a, 0xb54b, 0x8528, 0x9509, 0xe5ee, 0xf5cf, 0xc5ac, 0xd58d,
        0x3653, 0x2672, 0x1611, 0x0630, 0x76d7, 0x66f6, 0x5695, 0x46b4,
        0xb75b, 0xa77a, 0x9719, 0x8738, 0xf7df, 0xe7fe, 0xd79d, 0xc7bc,
        0x48c4, 0x58e5, 0x6886, 0x78a7, 0x0840, 0x1861, 0x2802, 0x3823,
        0xc9cc, 0xd9ed, 0xe98e, 0xf9af, 0x8948, 0x9969, 0xa90a, 0xb92b,
        0x5af5, 0x4ad4, 0x7ab7, 0x6a96, 0x1a71, 0x0a50, 0x3a33, 0x2a12,
        0xdbfd, 0xcbdc, 0xfbbf, 0xeb9e, 0x9b79, 0x8b58, 0xbb3b, 0xab1a,
        0x6ca6, 0x7c87, 0x4ce4, 0x5cc5, 0x2c22, 0x3c03, 0x0c60, 0x1c41,
        0xedae, 0xfd8f, 0xcdec, 0xddcd, 0xad2a, 0xbd0b, 0x8d68, 0x9d49,
        0x7e97, 0x6eb6, 0x5ed5, 0x4ef4, 0x3e13, 0x2e32, 0x1e51, 0x0e70,
        0xff9f, 0xefbe, 0xdfdd, 0xcffc, 0xbf1b, 0xaf3a, 0x9f59, 0x8f78,
        0x9188, 0x81a9, 0xb1ca, 0xa1eb, 0xd10c, 0xc12d, 0xf14e, 0xe16f,
        0x1080, 0x00a1, 0x30c2, 0x20e3, 0x5004, 0x4025, 0x7046, 0x6067,
        0x83b9, 0x9398, 0xa3fb, 0xb3da, 0xc33d, 0xd31c, 0xe37f, 0xf35e,
        0x02b1, 0x1290, 0x22f3, 0x32d2, 0x4235, 0x5214, 0x6277, 0x7256,
        0xb5ea, 0xa5cb, 0x95a8, 0x8589, 0xf56e, 0xe54f, 0xd52c, 0xc50d,
        0x34e2, 0x24c3, 0x14a0, 0x0481, 0x7466, 0x6447, 0x5424, 0x4405,
        0xa7db, 0xb7fa, 0x8799, 0x97b8, 0xe75f, 0xf77e, 0xc71d, 0xd73c,
        0x26d3, 0x36f2, 0x0691, 0x16b0, 0x6657, 0x7676, 0x4615, 0x5634,
        0xd94c, 0xc96d, 0xf90e, 0xe92f, 0x99c8, 0x89e9, 0xb98a, 0xa9ab,
        0x5844, 0x4865, 0x7806, 0x6827, 0x18c0, 0x08e1, 0x3882, 0x28a3,
        0xcb7d, 0xdb5c, 0xeb3f, 0xfb1e, 0x8bf9, 0x9bd8, 0xabbb, 0xbb9a,
        0x4a75, 0x5a54, 0x6a37, 0x7a16, 0x0af1, 0x1ad0, 0x2ab3, 0x3a92,
        0xfd2e, 0xed0f, 0xdd6c, 0xcd4d, 0xbdaa, 0xad8b, 0x9de8, 0x8dc9,
        0x7c26, 0x6c07, 0x5c64, 0x4c45, 0x3ca2, 0x2c83, 0x1ce0, 0x0cc1,
        0xef1f, 0xff3e, 0xcf5d, 0xdf7c, 0xaf9b, 0xbfba, 0x8fd9, 0x9ff8,
        0x6e17, 0x7e36, 0x4e55, 0x5e74, 0x2e93, 0x3eb2, 0x0ed1, 0x1ef0,
    ]
    crc = 0x1021 
    len = buf.size()
    for (int i = 0; i < len; i++) {
        crc = ((crc << 8) & 0xff00) ^ crctabHqx[((crc >> 8) & 0xff) ^ buf[i]]
    }
    return hubitat.helper.HexUtils.integerToHexString(crc, 1)
}

private def resetDB() {
    displayDebugLog('Reseting DB attributes')
    state.lastCommunicationTime = now()
}

private def resetSchedule() {
    unschedule()
    if (refreshRate == null || refreshRate == '0') {
        displayDebugLog('Unscheduled refresh')
    }else {
        schedule('0 */' + refreshRate + ' * ? * *', 'refresh')
        displayDebugLog("Scheduled 'refresh' to run every " + refreshRate + ' minutes')
    }
}

private def displayErrorLog(message) {
    log.error "${device.displayName}: ${message}"
}

private def displayInfoLog(message) {
    if (infoLogging) {
        log.info "${device.displayName}: ${message}"
    }
}

private def displayDebugLog(message) {
    if (debugLogging) {
        log.debug "${device.displayName}: ${message}"
    }
}
