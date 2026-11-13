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
#
# ── What the script looks for, and why ──────────────────────────────────
#
# Uplink (agent → caller).  The agent's voice has to reach the modem's
# uplink without going through the loudspeaker.  Two mechanisms exist on
# MediaTek:
#
#   * incall_music — the audio policy declares a mixPort named
#     incall_music_uplink carrying the flag AUDIO_OUTPUT_FLAG_INCALL_MUSIC,
#     and a route mixes it into the devicePort "Telephony Tx"
#     (AUDIO_DEVICE_OUT_TELEPHONY_TX).  Purely a policy path: no mixer
#     control is involved, only the mutes below.
#   * BGS — the HAL exposes Set_BGS_UL_Mute / Set_BGS_DL_Mute and the
#     BGSoundOn / BGSoundConfig entry points (upstream's route).
#
# The policy names its ports with tagName="..." — an earlier version of this
# script looked for name="..." only and reported a working uplink as missing.
# Both spellings are accepted now.
#
# Downlink (caller → agent).  The caller's voice has to reach the app.  The
# only digital path on MediaTek is AudioSource.VOICE_DOWNLINK, which the
# framework exposes as a hidden field of AudioAttributes.  Android 15 refuses
# that field for apps that target API 35, so this is the part of the gateway
# that still needs a working answer on these phones.  A telephony capture PCM
# (a tinycap target) would be an alternative; the script looks for one.
#
# The mixer on a modern MediaTek phone no longer carries BGS controls at all.
# It carries Speech_UL_Mute / Speech_DL_Mute / Speech_Mic_Mute instead —
# the renamed speech-call mutes.  Both generations are looked for.

set -u
SERIAL="${1:-}"
ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

sh_()  { "${ADB[@]}" shell "$@" 2>/dev/null | tr -d '\r'; }
su_()  { "${ADB[@]}" shell "su -c '$1'" 2>/dev/null | tr -d '\r'; }
have_root() { [ "$(su_ 'id -u')" = "0" ]; }

POL=/vendor/etc/audio_policy_configuration.xml

# Does the audio policy contain this (grep -E) pattern?  No root needed.
# The pattern is passed without single quotes — they would break the
# quoting of the shell command that runs on the phone.
pol_has() { sh_ "grep -qE '$1' $POL && echo y" | grep -q y; }
# The policy spells a port either name="..." (older AOSP) or tagName="..."
# (what MediaTek ships).  Both count.
pol_port() { pol_has "devicePort (name|tagName)=\"$1\""; }
# The whole policy as one string, for parsing.
pol_dump() { sh_ "cat $POL"; }

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
# A property that simply does not exist is a finding, not an unanswered
# question: it says the build has no VoLTE at all.
absent() { echo "  [--]      $1"; }

# What was found, and what it means for the route.
BGS_IN_HAL=0        # the vendor HAL carries the background-sound parameters
UPLINK_POLICY=0     # the policy mixes incall_music_uplink into Telephony Tx
CAPTURE_POLICY=0    # the audio policy has a Telephony Rx route
CAPTURE_PCM=0       # a telephony capture PCM exists (tinycap over root)
SPEECH_MUTE=0       # the mixer can mute the handset's own speech path

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
POLICY=$(pol_dump)

if echo "$POLICY" | grep -qE 'mixPort name="[^"]*incall[^"]*"'; then
    ok "incall_music mixPort declared"
    echo "$POLICY" | grep -A4 'mixPort name="incall' | grep -iE 'channelmask|samplerate|flags' \
        | sed 's/^/            /' | head -8
else
    no "no incall_music mixPort — the incall_music route is not available"
fi

if pol_port "Telephony Tx"; then
    ok "Telephony Tx devicePort declared (AUDIO_DEVICE_OUT_TELEPHONY_TX)"
else
    no "no Telephony Tx devicePort — nothing is routed into the modem uplink"
fi

# The route that actually matters: does anything mix into Telephony Tx, and
# is incall_music_uplink one of the sources?
TX_ROUTE=$(echo "$POLICY" | tr '<' '\n' | grep 'route type="mix" sink="Telephony Tx"' | head -1)
if [ -n "$TX_ROUTE" ]; then
    ok "a route mixes into Telephony Tx"
    echo "            $TX_ROUTE" | sed 's/^ *//' | tr -s ' ' | sed 's/^/            /'
    if echo "$TX_ROUTE" | grep -q 'incall_music'; then
        ok "incall_music_uplink is routed into Telephony Tx — the digital uplink exists"
        UPLINK_POLICY=1
    else
        no "incall_music_uplink is NOT among the sources of Telephony Tx"
    fi
else
    no "no route mixes into Telephony Tx"
fi

if pol_port "Telephony Rx"; then
    ok "Telephony Rx devicePort declared — VOICE_DOWNLINK has a route"
    CAPTURE_POLICY=1
else
    absent "no Telephony Rx devicePort — the downlink must come from AudioSource.VOICE_DOWNLINK"
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
        if echo "$S" | grep -qE "Set_SpeechCall_(UL|DL)_Mute"; then
            ok "speech-call mutes in the HAL — the handset can be taken out of the call"
        else
            no "no speech-call mutes in the HAL"
        fi
        if echo "$S" | grep -qE "BGSound(On|Off|Config)"; then
            ok "BGSound entry points in the HAL — the BGS player can be driven"
        else
            unk "no BGSound entry points in the HAL (the parameter route may still work)"
        fi
        if echo "$S" | grep -qi "incall"; then
            ok "the HAL knows the incall state"
        else
            unk "no incall string in the HAL (the policy may still offer it)"
        fi
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
        unk "tinymix not present — push magisk/tinymix to /data/local/tmp/ (chmod 755):"
        echo "            adb push magisk/tinymix /data/local/tmp/tinymix && adb shell chmod 755 /data/local/tmp/tinymix"
    else
        ok "tinymix at $TM"
        DABI=$(sh_ getprop ro.product.cpu.abi)
        echo "            device ABI: $DABI"
        M=$(su_ "$TM")
        NCTRL=$(echo "$M" | grep -c "^ *[0-9]")
        echo "            $NCTRL mixer controls"
        if echo "$M" | grep -qiE "bgs|background"; then
            ok "BGS mixer controls present"
            echo "$M" | grep -iE "bgs|background" | sed 's/^/            /' | head -6
        else
            absent "no BGS mixer control — the HAL parameters drive the BGS route here"
        fi
        if echo "$M" | grep -qiE "Speech_(UL|DL|Mic)_Mute|Set_SpeechCall"; then
            ok "speech mutes in the mixer — the handset can be taken out of the call"
            SPEECH_MUTE=1
            echo "$M" | grep -iE "Speech_(UL|DL|Mic)_Mute" | sed 's/^/            /' | head -6
        else
            no "no speech mutes in the mixer"
        fi
    fi

    PCMS=$(su_ 'cat /proc/asound/pcm 2>/dev/null')
    if [ -z "$PCMS" ]; then
        unk "no /proc/asound/pcm — cannot look for a telephony capture PCM"
    else
        echo "            $(echo "$PCMS" | grep -c .) PCM devices"
        echo "$PCMS" | grep -iE 'tel|voice|incall|tdm|apd|smartphone|hostless' | sed 's/^/            /' | head -12
        if echo "$PCMS" | grep -iE 'tel|voice|incall|tdm|apd|smartphone' | grep -q capture; then
            ok "a telephony capture PCM exists — tinycap can read the downlink"
            CAPTURE_PCM=1
        else
            absent "no telephony capture PCM — the downlink must come from AudioSource.VOICE_DOWNLINK"
        fi
    fi
fi

# ── 5. how the call is carried ─────────────────────────────────────────
echo
echo "=== 5. Call type: CS, IMS/VoLTE or WFC ==="
PROPS=$(sh_ getprop)
FOUND=0
for p in ims.mpsdn ims.mpsdn_enabled ims.ims_enabled ims.vowifi_enabled \
         wfc.state volte.enabled volte.hw_enabled gsm.sim.state \
         gsm.version.baseband vendor.radio.mtk.ims_start gsm.operator.alpha; do
    V=$(echo "$PROPS" | sed -n "s/^\[$p\]: \[\(.*\)\]$/\1/p")
    if [ -n "$V" ]; then
        echo "  $p = $V"
        FOUND=1
    fi
done
if [ "$FOUND" = "0" ]; then
    absent "no IMS/VoLTE/WFC properties on this build — the carrier is most likely plain CS"
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
if [ "${UPLINK_POLICY:-0}" = "1" ]; then
    ROUTE="incall_music"
    ROUTE_WHY="the audio policy mixes incall_music_uplink into Telephony Tx"
elif [ "${BGS_IN_HAL:-0}" = "1" ]; then
    ROUTE="BGS"
    ROUTE_WHY="the vendor audio HAL carries the background-sound parameters"
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
    CAPTURE="VOICE_DOWNLINK via the audio policy"
elif [ "$CAPTURE_PCM" = "1" ]; then
    CAPTURE="tinycap on the telephony-rx PCM"
else
    CAPTURE="AudioSource.VOICE_DOWNLINK (framework path)"
fi

if [ "$unknown" -gt 0 ]; then
    echo "  USABLE (uplink): use the $ROUTE injection mode — $ROUTE_WHY."
    echo "  The caller is captured from $CAPTURE."
    if [ "$SPEECH_MUTE" = "1" ]; then
        echo "  The handset can be muted out of the call (speech mutes present in the mixer)."
    else
        echo "  WARNING: no speech mutes found in the mixer — the handset's own voice"
        echo "  may stay in the call."
    fi
    echo
    echo "  $unknown checks could not be made — see the [?] lines above."
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
if [ "$CAPTURE" = "AudioSource.VOICE_DOWNLINK (framework path)" ]; then
    echo "  NOTE: the downlink comes from AudioSource.VOICE_DOWNLINK, which is"
    echo "  set through the hidden AudioAttributes.mSource field.  Android 15"
    echo "  refuses that field for apps that target API 35 — that is the one"
    echo "  open question for these phones."
fi
exit 0
