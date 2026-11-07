package com.example.domain.validation

/**
 * Validation stricte des montants MGA.
 *
 * Les montants sont conservés en Double dans Room pour éviter une migration,
 * mais seules les valeurs entières exactes et sûres sont autorisées.
 */
object MgaAmountValidator {
    const val MAX_SAFE_INTEGER: Long = 9_007_199_254_740_991L

    fun isValidNonNegative(value: Double): Boolean =
        value.isFinite() &&
            value >= 0.0 &&
            value <= MAX_SAFE_INTEGER.toDouble() &&
            value % 1.0 == 0.0

    fun isValidPositive(value: Double): Boolean =
        isValidNonNegative(value) && value > 0.0

    /**
     * Accepte uniquement une chaîne composée de chiffres ASCII.
     * Retourne null si la saisie est vide, décimale, négative,
     * malformée ou hors de la plage entière sûre.
     */
    fun parseNonNegativeInteger(input: String): Double? {
        if (input.isEmpty() || !input.matches(Regex("[0-9]+"))) {
            return null
        }

        val parsed = input.toLongOrNull() ?: return null
        if (parsed > MAX_SAFE_INTEGER) return null

        return parsed.toDouble()
    }
}
