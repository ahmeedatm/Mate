package app.mate.ui.home

import android.content.Context
import app.mate.R
import app.mate.net.DeckPage
import app.mate.net.DeckTile
import app.mate.net.VoiceMember
import app.mate.net.VoiceState

/**
 * Exemple d'un widget d'appel pour le catalogue : un appel Discord en cours (micro coupé, 5 participants, le premier
 * parle) ou une réunion Teams. Les touches ont toutes un symbole ou un emoji, les participants pas de photo : rien ne
 * part sur le réseau.
 */
class CallSample(val meeting: Boolean) : WidgetSample {
    /** Page du Deck, appel et participants qui parlent. */
    data class Call(val page: DeckPage, val voice: VoiceState, val speaking: Set<String>)

    fun build(context: Context): Call {
        val people = context.resources.getStringArray(R.array.call_sample_people).toList()
        return if (meeting) meeting(context, people.take(3)) else discord(context, people)
    }

    private fun discord(context: Context, people: List<String>): Call {
        fun s(id: Int) = context.getString(id)
        val tiles = listOf(
            tile("open", s(R.string.call_sample_open), symbol = "apps"),
            tile("mute", s(R.string.call_sample_mute), symbol = "mic_off", whenState = "call", lit = "muted"),
            tile("deafen", s(R.string.call_sample_deafen), symbol = "volume_off", whenState = "call", lit = "deafened"),
            tile("leave", s(R.string.call_sample_leave), symbol = "call_end", whenState = "call"),
        )
        val members = people.mapIndexed { i, name -> VoiceMember("sample-$i", name, avatar = false, speaking = i == 0) }
        val voice = VoiceState(
            connected = true,
            channel = s(R.string.call_sample_channel),
            people = people,
            muted = true,
            deafened = false,
            photos = "on",
            members = members,
        )
        return Call(DeckPage("Discord", tiles, app = "Discord"), voice, setOf(members.first().id))
    }

    private fun meeting(context: Context, people: List<String>): Call {
        fun s(id: Int) = context.getString(id)
        val tiles = listOf(
            tile("mic", s(R.string.call_sample_mic), symbol = "mic_off", whenState = "call", lit = "muted"),
            tile("camera", s(R.string.call_sample_camera), symbol = "videocam_off", whenState = "call", lit = "camera"),
            tile("share", s(R.string.call_sample_share), symbol = "present", whenState = "share"),
            tile("hand", s(R.string.call_sample_hand), symbol = "hand", whenState = "call"),
            tile("leave", s(R.string.call_sample_leave), symbol = "call_end", whenState = "call", color = LEAVE_COLOR),
        )
        val voice = VoiceState(
            connected = true,
            channel = s(R.string.call_sample_meeting),
            people = people,
            muted = false,
            deafened = null,
            cameraOff = false,
        )
        return Call(DeckPage(s(R.string.widget_meeting), tiles, meeting = true, app = "Microsoft Teams"), voice, emptySet())
    }

    private fun tile(
        id: String,
        label: String,
        symbol: String? = null,
        whenState: String? = null,
        lit: String? = null,
        color: String? = null,
    ) = DeckTile("sample-$id", label, "keys", glyph = null, color = color, whenState = whenState, lit = lit, symbol = symbol)

    private companion object {
        /** Rouge de « Quitter l'appel » de la page Réunion d'exemple (`backend/config/deck.example.json`). */
        const val LEAVE_COLOR = "#C62828"
    }
}
