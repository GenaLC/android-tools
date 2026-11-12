#!/bin/bash
#
# Decide whether a Cubot X90 / X100 can run the gateway, and which MediaTek
# audio route it should use.
#
# The upstream MediaTek route — the modem's background-sound (BGS) player —
# was verified on an MT6735/MT6753 running Android 8.1.  These phones run
# MT6789 (Helio G99) and MT6855V (Dimensity 7025) on Android 15: a different
# HAL generation, where the BGS player may not exist and where the audio
# policy may or may not offer an incall_music route to the modem uplink.
#
# Nothing about that can be known from a desk, so this script measures the
# phone and prints which route it should use.  It reads the audio policy
# (no root needed), the vendor audio HAL and the mixer/PCM state (root), and
# the IMS/VoLTE/WFC properties, and says what it found.
#
# The audio-policy check needs no root, so a candidate phone can be vetted
# before rooting it.  The HAL and mixer checks need root (Magisk, with
# Superuser access set to "Apps and ADB").
#
# Usage:  tools/check-device-cubot.sh [adb-serial]
#
# Exit:   0 = a usable injection route was found
#         1 = the phone cannot run the gateway (no telephony / no route)
#         2 = the phone could not be checked (no root, device not reachable)

set -u
SERIAL="${1:-}"
ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

sh_()  { "${ADB[@]}" shell "$@" 2>/dev/null | tr -d '\r'; }
su_()  { "${ADB[@]}" shell "su -c '$1'" 2>/dev/null | tr -d '\r'; }
have_root() { [ "$(su_ 'id -u')" = "0" ]; }

POL=/vendor/etc/audio_policy_configuration.xml
# Does the audio policy contain this (grep -E) pattern?  No root needed.
pol_has() { sh_ "grep -qE '$1' $POL && echo y" | grep -q y; }
# Path of a working tinymix, or empty.  The executable bit alone is not
# enough: an overlay can put a wrong-ABI binary on the path.
find_tinymix() {
    for p in /data/local/tmp/tinymix /vendor/bin/tinymix /system/bin/tinymix; do
        su_ "[ -x $p ] && echo yes" | grep -q yes && { echo "$p"; return; }
    done
}

pass=0; fail=0; unknown=0
ok()  { echo "  [ok]      $1"; pass=$((pass+1)); }
no()  { echo "  [MISSING] $1"; fail=$((fail+1)); }
unk() { echo "  [?]       $1"; unknown=$((unknown+1)); }

# What was found, and what it means for the route.
BGS_IN_HAL=0        # the vendor HAL carries the background-sound parameters
CAPTURE_POLICY=0   # the audio policy has a Telephony Rx route (VOICE_DOWNLINK)
CAPTURE_PCM=0      # a telephony capture PCM exists (tinycap over root)

# The route the phone should use, decided from what was found.  Set below.
ROUTE=""
ROUTE_WHY=""

echo "=== Device ==="
echo "  model    : $(sh_ getprop ro.product.model)"
echo "  platform : $(sh_ getprop ro.board.platform)"
echo "  soc      : $(sh_ getprop ro.soc.model)"
echo "  board    : $(sh_ getprop ro.product.board)"
echo "  hardware : $(sh_ getprop ro.hardware)"
echo "  android  : $(sh_ getprop ro.build.version.release) (SDK $(sh_ getprop ro.build.version.sdk))"
echo "  vendor   : $(sh_ getprop ro.vendor.build.fingerprint)"
echo

PLATFORM=$(sh_ getprop ro.board.platform)
SOC=$(sh_ getprop ro.soc.model)
HARDWARE=$(sh_ getprop ro.hardware)

# ── which phone is this? ───────────────────────────────────────────────
echo "=== 1. Platform ==="
case "$PLATFORM $SOC $HARDWARE" in
    *mt6855*|*aza*|*dimensity\ 7025*)
        echo "  Cubot X100 — MediaTek Dimensity 7025 (MT6855V), codename aza"
        ;;
    *mt6789*|*helio\ g99*)
        echo "  Cubot X90 — MediaTek Helio G99 (MT6789)"
        ;;
    *mt67*|*mt68*)
        echo "  Another MediaTek MT67xx/MT68xx phone ($PLATFORM / $SOC)"
        ;;
    *)
        echo "  Neither a Cubot X90 nor an X100 — the MediaTek checks below still run."
        ;;
esac
echo

if sh_ 'pm list features' | grep -q 'feature:android.hardware.telephony'; then
    ok "telephony hardware present"
else
    no "no telephony — this device cannot place cellular calls (WiFi-only?)"
fi

# ── 2. audio policy (no root needed) — the decisive one ────────────────
echo
echo "=== 2. Audio policy: is there a route to the modem uplink? ==="
if pol_has 'mixPort name="[^"]*incall[^"]*"'; then
    ok "incall_music mixPort declared"
    sh_ "grep -A4 'mixPort name=\"incall' $POL" | grep -iE 'channelmask|samplerate' | sed 's/^/            /'
else
    no "no incall_music mixPort — the incall_music route is not available"
fi

if pol_has 'devicePort name="Telephony Tx"'; then
    ok "Telephony Tx devicePort declared"
else
    no "no Telephony Tx devicePort — nothing is routed into the modem uplink"
fi

if pol_has 'sink="Telephony Tx"'; then
    ok "some mixPort sinks into Telephony Tx"
    sh_ "grep -B6 'sink=\"Telephony Tx\"' $POL" | grep -i 'mixPort name' | tail -2 | sed 's/^/            /'
else
    no "no mixPort sinks into Telephony Tx"
fi

if pol_has 'devicePort name="Telephony Rx"'; then
    ok "Telephony Rx devicePort declared — VOICE_DOWNLINK has a route"
    CAPTURE_POLICY=1
else
    no "no Telephony Rx devicePort — VOICE_DOWNLINK may not carry the caller"
fi

# ── 3. the audio HAL (root) ────────────────────────────────────────────
echo
echo "=== 3. Audio HAL: the background-sound player ==="
if ! have_root; then
    unk "needs root — enable Magisk Superuser access = 'Apps and ADB', then"
    echo "            run 'adb shell su -c id' once and grant the prompt"
else
    HAL=$(su_ 'ls /vendor/lib64/hw/audio.primary.*.so /vendor/lib64/hw/audio.*.so /vendor/lib32/hw/audio.*.so 2>/dev/null' | grep -v default | head -1)
    if [ -z "$HAL" ]; then
        unk "no vendor audio HAL found"
    else
        echo "            $HAL"
        S=$(su_ "strings $HAL")
        if echo "$S" | grep -q "Set_BGS_UL_Mute"; then
            ok "BGS parameters in the HAL — the background-sound route exists"
            BGS_IN_HAL=1
        else
            no "no BGS parameters in the HAL — the background-sound route is gone"
            BGS_IN_HAL=0
        fi
        echo "$S" | grep -q "Set_SpeechCall_UL_Mute" \
            && ok "speech-call mutes in the HAL — the handset can be taken out of the call" \
            || no "no speech-call mutes in the HAL"
        echo "$S" | grep -qi "incall_music" \
            && ok "incall_music in the HAL" \
            || unk "no incall_music string in the HAL (the policy may still offer it)"
    fi
fi

# ── 4. mixer and PCMs (root) ───────────────────────────────────────────
echo
echo "=== 4. Mixer and PCMs ==="
if ! have_root; then
    unk "needs root"
else
    TM=$(find_tinymix)
    if [ -z "$TM" ]; then
        unk "tinymix not present — push magisk/tinymix to /data/local/tmp/ (chmod 755)"
    else
        ok "tinymix at $TM"
        ABI=$(su_ "file $TM" | grep -oE 'aarch64|ARM|Intel 80386' | head -1)
        DABI=$(sh_ getprop ro.product.cpu.abi)
        if [ -z "$ABI" ]; then
            unk "could not read the tinymix ABI (no file(1) on the device) — device ABI is $DABI"
        else
            case "$ABI:$DABI" in
                aarch64:*64*|*64*:aarch64) ok "tinymix ABI ($ABI) matches the device ($DABI)" ;;
                *) no "tinymix ABI ($ABI) does not match the device ($DABI)" ;;
            esac
        fi
        M=$(su_ "$TM")
        echo "$M" | grep -qi 'bgs' \
            && ok "BGS mixer controls present" \
            || unk "no BGS mixer control (the HAL parameters may be the only route)"
        echo "$M" | grep -qiE 'incall|inc_call' \
            && ok "incall mixer controls present" \
            || unk "no incall mixer control (the policy route may be the only one)"
    fi

    PCMS=$(su_ 'cat /proc/asound/pcm 2>/dev/null')
    if [ -z "$PCMS" ]; then
        unk "no /proc/asound/pcm — cannot look for a telephony capture PCM"
    else
        echo "$PCMS" | grep -iE 'tel|voice|incall|tdm|apd|smartphone' | sed 's/^/            /'
        if echo "$PCMS" | grep -iE 'tel|voice|incall|tdm|apd|smartphone' | grep -q capture; then
            ok "a telephony capture PCM exists — tinycap can read the downlink"
            CAPTURE_PCM=1
        else
            no "no telephony capture PCM — the caller can only be heard acoustically"
        fi
    fi
fi

# ── 5. how the call is carried ─────────────────────────────────────────
echo
echo "=== 5. Call type: CS, IMS/VoLTE or WFC ==="
for p in ims.mpsdn ims.mpsdn_enabled ims.ims_enabled ims.vowifi_enabled wfc.state volte.enabled volte.hw_enabled; do
    V=$(sh_ getprop "$p")
    if [ -n "$V" ]; then
        echo "  $p = $V"
    fi
done
if [ -z "$(sh_ getprop ims.mpsdn_enabled)$(sh_ getprop ims.ims_enabled)$(sh_ getprop wfc.state)$(sh_ getprop volte.enabled)" ]; then
    unk "no IMS/VoLTE/WFC properties readable — the carrier may be on plain CS"
fi
cat <<'EOF'
  These properties only say what the modem supports, not what the *current*
  call uses.  To classify the call, start it and read the radio log:
      adb logcat -b radio -d | grep -iE 'ims|volte|wfc|csfb'
  A CS call carries the voice in the modem's own DSP; an IMS/VoLTE call
  carries it as RTP, and a WFC call carries it over the Wi-Fi link.  The
  difference matters here because the audio route is chosen by the HAL for
  the call type the modem actually negotiated.
EOF

# ── verdict ────────────────────────────────────────────────────────────
echo
echo "=== Verdict ==="

# The injection route, in the order the app tries it.
if [ "${BGS_IN_HAL:-0}" = "1" ]; then
    ROUTE="BGS"
    ROUTE_WHY="the vendor audio HAL carries the background-sound parameters"
elif pol_has 'mixPort name="[^"]*incall[^"]*"' && pol_has 'devicePort name="Telephony Tx"'; then
    ROUTE="incall_music"
    ROUTE_WHY="the audio policy offers an incall_music mixPort routed to Telephony Tx"
else
    ROUTE="acoustic"
    ROUTE_WHY="no digital injection route was found"
fi

if [ "$fail" -gt 0 ] && [ -z "$(sh_ 'pm list features' | grep telephony)" ]; then
    echo "  NOT USABLE: the phone has no telephony — it cannot place cellular calls."
    exit 1
fi

if [ "$ROUTE" = "acoustic" ]; then
    echo "  NOT USABLE as a digital gateway: $ROUTE_WHY."
    echo "  The gateway would fall back to the speaker and the microphone, which"
    echo "  works but is one-way and echo-prone.  Check the policy dump above:"
    echo "  a missing 'Telephony Tx' device port means this ROM has no route"
    echo "  into the modem uplink at all."
    exit 1
fi

if [ "$CAPTURE_POLICY" = "1" ]; then
    CAPTURE="VOICE_DOWNLINK"
elif [ "$CAPTURE_PCM" = "1" ]; then
    CAPTURE="tinycap on the telephony-rx PCM"
else
    CAPTURE="acoustic (the microphone)"
fi

if [ "$unknown" -gt 0 ]; then
    echo "  Usable, but $unknown checks could not be made: $ROUTE_WHY."
    echo "  Root the phone and re-run for a definite answer."
    exit 2
fi

echo "  USABLE: use the $ROUTE injection mode — $ROUTE_WHY."
case "$ROUTE" in
    BGS)
        echo "  The app sets Set_BGS_UL_Mute=0 / Set_BGS_DL_Mute=1 and the two"
        echo "  speech-call mutes once the call is up, and takes the caller from"
        echo "  AudioSource.VOICE_DOWNLINK."
        ;;
    incall_music)
        echo "  The app sets incall_music_enabled before the playback track is"
        echo "  created and routes the track to the telephony TX device."
        ;;
esac
echo "  The caller is captured from $CAPTURE, never from VOICE_CALL: that one"
echo "  mixes the uplink, which carries the agent's own voice, into the capture."
if [ "$CAPTURE" = "acoustic (the microphone)" ]; then
    echo "  WARNING: no digital downlink was found.  The caller will only be"
    echo "  heard through the phone's own microphone, which needs the call on"
    echo "  the loudspeaker and is prone to echo."
fi
exit 0
