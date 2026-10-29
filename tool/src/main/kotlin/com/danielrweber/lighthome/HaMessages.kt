package com.danielrweber.lighthome

internal object HaMessages {
    const val CALLS_ONLY = "Calls go only to your Home Assistant."
    const val TOKEN_STAYS = "The token stays on this phone."
    const val NO_LAN = "Use the https address of your tunnel. LightHome does not search your home network."
    const val CHECKING = "Checking your Home Assistant…"
    const val WORKING = "Working…"
    const val EMPTY =
        "Nothing to control yet. In Home Assistant, add a light, switch, scene, script, cover, or thermostat. Then tap Refresh."
    const val TOKEN_MISSING =
        "Paste a long-lived access token from your Home Assistant profile, under Security."
    const val TOKEN_REJECTED =
        "Home Assistant refused this token. In your profile, open Security, create a long-lived access token, and paste the new one here."
    const val TOKEN_UNREADABLE =
        "The saved token could not be read on this phone. Paste the long-lived access token again."
    const val TOKEN_TOO_SHORT =
        "That token looks too short. Create a long-lived access token in your Home Assistant profile and paste the whole thing."
    const val TOKEN_TOO_LONG = "That token looks too long. Paste only the long-lived access token."
    const val TOKEN_CHARS =
        "The token should be the characters Home Assistant showed, with no spaces or line breaks."
    const val SAVE_FAILED =
        "The token could not be saved on this phone, so LightHome did not keep it. Tap Connect to try again."
    const val CLEAR_FAILED = "The token is still stored on this phone. Tap Forget to try again."
    const val UNREACHABLE =
        "Home Assistant did not answer. On the Raspberry Pi, check that the tunnel is running and that it forwards only to Home Assistant's port. Then tap Refresh."
    const val TLS =
        "The phone could not open a trusted https connection. Use the https hostname from your tunnel, then try again."
    const val REDIRECT = "That address forwarded somewhere else. Paste the final https hostname of your tunnel."
    const val NOT_HA =
        "That address answered, but it does not look like Home Assistant. Point the tunnel at Home Assistant, then try again."
    const val NOT_FOUND =
        "Home Assistant's /api/ was not at that address. On the Raspberry Pi, point the tunnel at Home Assistant's port only, then try again."
    const val UNREADABLE = "Home Assistant sent a response LightHome could not read. Tap Refresh to try again."
    const val UNAVAILABLE = "This is unavailable in Home Assistant. Check it there, then tap Refresh."
    const val NO_TEMPERATURE =
        "Home Assistant did not send a temperature for this thermostat. Tap Refresh, or set the temperature in Home Assistant."
    const val FORGET_BODY = "This removes the address and token from this phone. Home Assistant is not changed."
    const val SETUP_TITLE = "Your Home Assistant"
    const val ADDRESS_LABEL = "Address"
    const val TOKEN_LABEL = "Token"
    const val ADDRESS_PLACEHOLDER = "https://home.example.com"
    const val TOKEN_PLACEHOLDER = "Long-lived access token"
    const val SCAN_TOKEN = "Scan QR code"
    const val SCAN_NOT_TOKEN =
        "That code is not a Home Assistant token. On the token screen, tap the QR button, then scan that code. You can also paste the token."
    const val TOKEN_SAVED = "Saved on this phone"
    const val TOKEN_READY = "New token ready"
    const val FORGET = "Forget this home"
    const val NO_MODES = "This thermostat did not send a list of modes."
    const val URL_EMPTY = "Enter the https address of your tunnel."
    const val URL_HTTP = "The address has to start with https://. The tunnel gives you that address."
    const val URL_HTTPS = "Use an https address, such as https://home.example.com."
    const val URL_IP = "Use the https hostname from your tunnel. LightHome does not connect to IP addresses."
    const val URL_LOCAL = "That looks like a local network name. Use the https hostname from your tunnel."
    const val URL_PATH =
        "Paste only the site address, such as https://home.example.com. LightHome adds the Home Assistant path itself."
    const val URL_USERINFO = "Put the token in the token field. Leave it out of the address."
    const val URL_HOST = "Use the full https hostname from your tunnel, such as https://home.example.com."
    const val URL_BAD =
        "That address could not be read. Use the https hostname from your tunnel, such as https://home.example.com."

    fun httpError(status: Int) = "Home Assistant returned an error ($status). Tap Refresh to try again."
}

internal fun messageFor(result: HaCallResult<*>): String = when (result) {
    is HaCallResult.Ok -> error("messageFor is only for failures")
    is HaCallResult.Http -> when (result.status) {
        401, 403 -> HaMessages.TOKEN_REJECTED
        404 -> HaMessages.NOT_FOUND
        else -> HaMessages.httpError(result.status)
    }
    is HaCallResult.Redirect -> HaMessages.REDIRECT
    is HaCallResult.NotHomeAssistant -> HaMessages.NOT_HA
    is HaCallResult.Unreadable -> HaMessages.UNREADABLE
    is HaCallResult.Transport -> when (result.kind) {
        TransportKind.Unreachable -> HaMessages.UNREACHABLE
        TransportKind.Tls -> HaMessages.TLS
    }
}
