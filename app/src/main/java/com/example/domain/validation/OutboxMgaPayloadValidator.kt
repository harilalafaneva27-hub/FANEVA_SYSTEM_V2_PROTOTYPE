package com.example.domain.validation

import com.squareup.moshi.JsonReader
import okio.Buffer
import java.math.BigDecimal

/**
 * Validation stricte des montants MGA avant envoi des mutations Outbox.
 * Les montants restent stockés en Double dans Room, sans arrondi ni troncature.
 */
object OutboxMgaPayloadValidator {

    private val maxSafeInteger = BigDecimal.valueOf(MgaAmountValidator.MAX_SAFE_INTEGER)

    fun validate(entityType: String, payloadJson: String): String? {
        return try {
            val reader = JsonReader.of(Buffer().writeUtf8(payloadJson))
            reader.isLenient = false

            val error = when (entityType) {
                "SALE" -> validateSale(reader)
                "DEBT_PAYMENT" -> validateObjectFields(
                    reader,
                    listOf("amountPaid", "newDebt"),
                    "payload"
                )
                "CUSTOMER" -> validateObjectFields(
                    reader,
                    listOf("creditLimit"),
                    "payload"
                )
                "PRODUCT" -> validateObjectFields(
                    reader,
                    listOf("sellingPrice"),
                    "payload"
                )
                "TRANSFER" -> skipRootObject(reader).let { null }
                else -> {
                    skipRootObject(reader)
                    "Type d'opération non pris en charge : $entityType"
                }
            }

            if (error != null) return error
            if (reader.peek() != JsonReader.Token.END_DOCUMENT) {
                return "Payload JSON invalide : contenu supplémentaire après l'objet."
            }
            null
        } catch (error: Exception) {
            "Payload JSON invalide : ${error.message ?: "format incorrect"}"
        }
    }

    private fun validateObjectFields(
        reader: JsonReader,
        fields: List<String>,
        location: String
    ): String? {
        val seen = mutableSetOf<String>()
        reader.beginObject()

        while (reader.hasNext()) {
            val field = reader.nextName()
            if (field in fields) {
                validateAmount(reader, "$location.$field")?.let { return it }
                seen.add(field)
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()

        val missing = fields.firstOrNull { it !in seen }
        return missing?.let { "$location.$it : montant MGA manquant." }
    }

    private fun validateSale(reader: JsonReader): String? {
        val fields = listOf("totalAmount", "paidAmount", "remainingAmount")
        val seen = mutableSetOf<String>()
        var itemsSeen = false
        reader.beginObject()

        while (reader.hasNext()) {
            when (val field = reader.nextName()) {
                in fields -> {
                    validateAmount(reader, "payload.$field")?.let { return it }
                    seen.add(field)
                }
                "items" -> {
                    if (reader.peek() != JsonReader.Token.BEGIN_ARRAY) {
                        return "SALE : champ items absent ou invalide."
                    }
                    itemsSeen = true
                    reader.beginArray()
                    var index = 0
                    while (reader.hasNext()) {
                        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
                            return "SALE : item[$index] invalide."
                        }
                        validateSaleItem(reader, index)?.let { return it }
                        index++
                    }
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val missing = fields.firstOrNull { it !in seen }
        if (missing != null) {
            return "payload.$missing : montant MGA manquant."
        }
        if (!itemsSeen) {
            return "SALE : champ items absent ou invalide."
        }
        return null
    }

    private fun validateSaleItem(reader: JsonReader, index: Int): String? {
        var priceSeen = false
        reader.beginObject()

        while (reader.hasNext()) {
            when (val field = reader.nextName()) {
                "price" -> {
                    validateAmount(reader, "SALE.items[$index].price")?.let { return it }
                    priceSeen = true
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        return if (priceSeen) null
        else "SALE.items[$index].price : montant MGA manquant."
    }

    private fun validateAmount(reader: JsonReader, location: String): String? {
        if (reader.peek() != JsonReader.Token.NUMBER) {
            return "$location : le montant MGA doit être numérique."
        }

        val rawValue = reader.nextString()
        val amount = try {
            BigDecimal(rawValue)
        } catch (_: NumberFormatException) {
            return "$location : montant MGA invalide."
        }

        if (amount.signum() < 0 ||
            amount.stripTrailingZeros().scale() > 0 ||
            amount > maxSafeInteger
        ) {
            return "$location : montant MGA invalide (entier positif ou nul requis, plage sûre respectée)."
        }
        return null
    }

    private fun skipRootObject(reader: JsonReader) {
        reader.beginObject()
        while (reader.hasNext()) {
            reader.nextName()
            reader.skipValue()
        }
        reader.endObject()
    }
}
