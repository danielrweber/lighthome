# LightHome

LightHome controls a few things in your home from a Light Phone: lights, switches, scenes, scripts, covers, and thermostats.

The phone talks only to the Home Assistant address you paste in. It does not search your home network. The token stays on the phone. There are no ads and no analytics. The list refreshes when you open LightHome and when you tap Refresh. It does not keep checking in the background.

## What you need

- Home Assistant running on a Raspberry Pi
- A Cloudflare account
- A hostname you control, such as `home.example.com`
- LightHome on your Light Phone

Use your own hostname everywhere this guide says `home.example.com`. Leave real hostnames, tokens, and tunnel credentials out of this repository.

## 1. Create a long-lived access token

1. Open Home Assistant in a browser.
2. Select your name at the bottom of the sidebar.
3. Open the **Security** tab.
4. Under **Long-lived access tokens**, choose **Create token**.
5. Name it `LightHome`.
6. Copy the token when Home Assistant shows it. Home Assistant shows it only once.
7. Keep it somewhere temporary until you paste it into the phone. Do not put it in a file in this project, in a screenshot, or in chat.

## 2. Run a Cloudflare Tunnel on the Raspberry Pi

Home Assistant is running on the Raspberry Pi, so the tunnel starts there too. `cloudflared` opens a connection from that Pi out to Cloudflare. Your phone uses the public `https://` hostname, and Cloudflare sends the traffic back down the tunnel to the Pi. The last hop is only to Home Assistant on that same Pi.

Do this in a terminal on the Raspberry Pi (or an SSH session to the Pi). Leave other computers, including a Mac mini, out of the tunnel. The ingress rules below publish one hostname and Home Assistant's port, and they send every other request to a 404, so the rest of the Pi stays unpublished.

Home Assistant's port on the Pi is `8123` unless you changed it. That address is only for `cloudflared` on the Pi. The phone uses `https://home.example.com`, with no port and no path.

### Install cloudflared

On 64-bit Raspberry Pi OS:

```sh
curl -L --output cloudflared.deb https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64.deb
sudo dpkg -i cloudflared.deb
```

On 32-bit Raspberry Pi OS, use `cloudflared-linux-arm.deb` from the same releases page instead.

If Home Assistant OS is what runs on the Pi, you can use the Cloudflared add-on on that Pi. The rule is the same: one hostname, and the origin is Home Assistant's port on that Pi.

### Create the tunnel

```sh
cloudflared tunnel login
cloudflared tunnel create home-assistant
cloudflared tunnel route dns home-assistant home.example.com
```

`tunnel login` writes a certificate into the Pi's `.cloudflared` folder. `tunnel create` writes a credentials JSON file there too. Both stay on the Pi. Do not copy them into this repository.

### Publish only Home Assistant

Create `~/.cloudflared/config.yml` on the Pi. Replace `<TUNNEL_ID>` with the id `tunnel create` printed, and replace the home path if yours is different:

```yaml
tunnel: <TUNNEL_ID>
credentials-file: /home/pi/.cloudflared/<TUNNEL_ID>.json

ingress:
  - hostname: home.example.com
    service: http://127.0.0.1:8123
  - service: http_status:404
```

The first rule forwards `home.example.com` to Home Assistant on this Pi. The second rule is required. It answers everything else with 404, so the tunnel does not open SSH, file shares, or other ports.

`http://127.0.0.1:8123` is the hop from `cloudflared` to Home Assistant on the same Pi. LightHome will not accept that address, a LAN IP, or a `.local` name.

### Start it with the Pi

```sh
sudo cloudflared --config /home/pi/.cloudflared/config.yml service install
sudo systemctl enable --now cloudflared
sudo systemctl status cloudflared
```

When the status says the tunnel is running, `https://home.example.com` reaches Home Assistant.

## 3. Paste the address and token into LightHome

1. Open LightHome.
2. Tap **Address** and enter `https://home.example.com`. Use your hostname. Leave off any path.
3. Tap **Token** and paste the long-lived access token, or tap **Scan QR code** and scan the code Home Assistant shows for that token.
4. Tap **Connect**.

LightHome checks `https://home.example.com/api/` with `Authorization: Bearer` and your token. That call goes to your Home Assistant. Then you choose which lights, switches, scenes, scripts, covers, and thermostats stay on the phone.

The address and token are stored on the phone. The token is encrypted with the phone's keystore and is not written to the app log.

## Using it

- The list starts empty. Tap **Choose**, then tap each device you want. Tap **Show** to see one kind, or **All**.
- Tap a light or switch to toggle it.
- Tap a scene or script to run it.
- Open a cover to open or close it.
- Open a thermostat to raise or lower the temperature, or to pick a mode.
- Tap Refresh, or leave and open LightHome, to update the list.

## If it does not connect

- **Home Assistant refused this token.** Create a new long-lived access token and paste it again.
- **Home Assistant did not answer.** On the Pi, run `systemctl status cloudflared`. The tunnel should be running, and the ingress origin should be Home Assistant's port.
- **The address has to start with https://.** Use the hostname from the tunnel, such as `https://home.example.com`.

## Building the app

LightHome is a [Light SDK](https://github.com/lightphone/light-sdk) tool. The app code is in `tool/`. The package name is `com.danielrweber.lighthome`.

```sh
./gradlew :tool:assembleDebug
```

The debug build is signed with the public Light SDK development keystore that ships with the scaffold. Its password is the published SDK password, not a secret of yours.

This repository also contains the Light SDK scaffold so the tool can be built locally. Light's own build servers compile the `tool/` directory against a pinned SDK. Keep Home Assistant tokens, tunnel credentials, account ids, and real hostnames out of commits, sample config, and screenshots.
