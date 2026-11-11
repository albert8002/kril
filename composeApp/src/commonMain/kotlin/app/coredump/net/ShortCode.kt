package app.coredump.net

/**
 * The compact join payload carried by the themed sigil (the QR on the host screens):
 *
 *     CD1:<ROOM><broker><kind>        e.g.  CD1:K7Q2M9XA0J
 *
 * ROOM = the 8-character room code ([JoinTicket.ROOM_ALPHABET]), broker = relay index as one base-36 digit
 * (0-9, A-Z), kind = J (1v1 join) or P (MELTDOWN party). 14 bytes: exactly the capacity of the smallest QR code
 * (version 1, 21×21 modules, error correction M), so every module is as big as it can be and cheap cameras read it.
 *
 * [decode] is lenient: case, surrounding spaces and ':' / '-' / ' ' separators after the prefix are ignored
 * ("cd1:k7q2-m9xa:0:j" is fine), and a decimal broker of more than one digit is accepted in that separated form.
 */
object ShortCode {
    const val PREFIX = "CD1:"

    /** What a short code says: join [room] on relay [broker]; [party] = a MELTDOWN party (else a 1v1). */
    data class Decoded(val room: String, val broker: Int, val party: Boolean)

    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** "CD1:K7Q2M9XA0J". [room]: 8 room-alphabet characters (any case, a '-' is ignored); [broker]: 0..35. */
    fun encode(room: String, broker: Int, party: Boolean): String {
        val r = room.uppercase().filter { it != '-' && it != ' ' }
        require(r.length == JoinTicket.ROOM_LEN && r.all { it in JoinTicket.ROOM_ALPHABET }) { "bad room code: $room" }
        require(broker in 0 until DIGITS.length) { "broker index out of range: $broker" }
        return PREFIX + r + DIGITS[broker] + (if (party) 'P' else 'J')
    }

    /** The short code in [raw], or null if [raw] is not one (old coredump:// links, typed codes … → null). */
    fun decode(raw: String): Decoded? {
        val s = raw.trim().uppercase()
        if (!s.startsWith(PREFIX)) return null
        val compact = s.substring(PREFIX.length)
        val body = compact.filter { it != ':' && it != '-' && it != ' ' }
        if (body.length < JoinTicket.ROOM_LEN + 2) return null
        val room = body.substring(0, JoinTicket.ROOM_LEN)
        if (!room.all { it in JoinTicket.ROOM_ALPHABET }) return null
        val party = when (body.last()) {
            'J' -> false
            'P' -> true
            else -> return null
        }
        val mid = body.substring(JoinTicket.ROOM_LEN, body.length - 1)
        val broker = when {
            mid.length == 1 -> DIGITS.indexOf(mid[0])
            // "CD1:ROOM:12:J": several decimal digits only when separated (compact form is always one digit)
            mid.isNotEmpty() && mid.length <= 3 && mid.all { it.isDigit() } && compact.length > body.length -> mid.toInt()
            else -> -1
        }
        if (broker < 0) return null
        return Decoded(room, broker, party)
    }
}
