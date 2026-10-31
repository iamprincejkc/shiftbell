package space.iamjkc.shiftbell.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Local wall-clock values as ISO strings. Never UTC: an alarm at 7:45 means 7:45 wherever the phone is. */
abstract class IsoSerializer<T : Any>(name: String, private val parse: (String) -> T) : KSerializer<T> {
    override val descriptor = PrimitiveSerialDescriptor("space.iamjkc.shiftbell.$name", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): T = parse(decoder.decodeString())
}

object LocalTimeSerializer : IsoSerializer<LocalTime>("LocalTime", LocalTime::parse)

object LocalDateSerializer : IsoSerializer<LocalDate>("LocalDate", LocalDate::parse)

object LocalDateTimeSerializer : IsoSerializer<LocalDateTime>("LocalDateTime", LocalDateTime::parse)
